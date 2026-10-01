package com.renyi.mes.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.engineering.RouteType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Service
public class IdentityAccessApplication {

	private final JdbcTemplate jdbc;

	public IdentityAccessApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<UserView> listUsers() {
		List<UserRow> users = jdbc.query("""
			select m.employee_code, m.name, m.unit_code, u.name as unit_name,
			       m.role_code, m.active, a.employee_code as account_code, a.must_change_password, a.last_login_at
			from organization_member m
			join organization_unit u on u.code = m.unit_code
			left join identity_account a on a.employee_code = m.employee_code
			order by m.employee_code
			""", IdentityAccessApplication::mapUserRow);
		Map<String, List<String>> roles = groupedValues("""
			select mr.employee_code as owner_code, mr.role_code as value_code
			from organization_member_role mr
			join organization_member m on m.employee_code = mr.employee_code
			order by mr.employee_code,
			         case when mr.role_code = m.role_code then 0 else 1 end,
			         mr.role_code
			""");
		Map<String, List<String>> permissions = groupedValues("""
			select distinct mr.employee_code as owner_code, rp.permission_code as value_code
			from organization_member_role mr
			join access_role_permission rp on rp.role_code = mr.role_code
			order by mr.employee_code, rp.permission_code
			""");
		return users.stream()
			.map(user -> new UserView(
				user.employeeCode(), user.name(), user.unitCode(), user.unitName(),
				user.primaryRole(), roles.getOrDefault(user.employeeCode(), List.of()),
				permissions.getOrDefault(user.employeeCode(), List.of()), user.active(), user.loginInitialized(), user.mustChangePassword(), user.lastLoginAt()))
			.toList();
	}

	@Transactional(readOnly = true)
	public UserView getUser(String employeeCode) {
		String code = normalize(employeeCode);
		return listUsers().stream()
			.filter(user -> user.employeeCode().equals(code))
			.findFirst()
			.orElseThrow(() -> DomainException.notFound("ACCESS_USER_NOT_FOUND", "用户不存在"));
	}

	@Transactional(readOnly = true)
	public List<RoleView> listRoles() {
		Map<String, List<String>> permissions = groupedValues("""
			select role_code as owner_code, permission_code as value_code
			from access_role_permission order by role_code, permission_code
			""");
		return jdbc.query("""
			select code, name, description from access_role order by code
			""", (rs, rowNum) -> new RoleView(
				rs.getString("code"),
				rs.getString("name"),
				rs.getString("description"),
				permissions.getOrDefault(rs.getString("code"), List.of())
			));
	}

	@Transactional(readOnly = true)
	public List<PermissionView> listPermissions() {
		return jdbc.query("""
			select code, name, module_code from access_permission order by module_code, code
			""", (rs, rowNum) -> new PermissionView(
				rs.getString("code"), rs.getString("name"), rs.getString("module_code")));
	}

	@Transactional
	public RoleView createRole(CreateRoleCommand command) {
		String code = normalize(command.code());
		try {
			jdbc.update("""
				insert into access_role (code, name, description) values (?, ?, ?)
				""", code, command.name().trim(), command.description().trim());
		}
		catch (DuplicateKeyException exception) {
			throw DomainException.conflict("ACCESS_ROLE_EXISTS", "角色编码已存在");
		}
		return requireRole(code);
	}

	@Transactional
	public UserView setUserRoles(String employeeCode, List<String> requestedRoles) {
		String userCode = normalize(employeeCode);
		requireMember(userCode);
		List<String> roleCodes = normalizedDistinct(requestedRoles);
		requireKnownRoles(roleCodes);
		jdbc.update("delete from organization_member_role where employee_code = ?", userCode);
		for (String roleCode : roleCodes) {
			jdbc.update("""
				insert into organization_member_role (employee_code, role_code) values (?, ?)
				""", userCode, roleCode);
		}
		jdbc.update("""
			update organization_member set role_code = ? where employee_code = ?
			""", roleCodes.getFirst(), userCode);
		return getUser(userCode);
	}

	@Transactional
	public RoleView setRolePermissions(String roleCode, List<String> requestedPermissions) {
		String code = normalize(roleCode);
		requireRole(code);
		List<String> permissionCodes = normalizedDistinct(requestedPermissions);
		requireKnownPermissions(permissionCodes);
		jdbc.update("delete from access_role_permission where role_code = ?", code);
		for (String permissionCode : permissionCodes) {
			jdbc.update("""
				insert into access_role_permission (role_code, permission_code) values (?, ?)
				""", code, permissionCode);
		}
		return requireRole(code);
	}

	@Transactional(readOnly = true)
	public UserScopeView userScopes(String employeeCode) {
		String code = normalize(employeeCode);
		requireMember(code);
		List<String> supervisorRoutes = jdbc.query("""
			select route_type from production_supervisor_scope where employee_code = ? order by route_type
			""", (rs, row) -> rs.getString("route_type"), code);
		List<String> operatorRoutes = jdbc.query("""
			select route_type from production_operator_scope where employee_code = ? order by route_type
			""", (rs, row) -> rs.getString("route_type"), code);
		List<OperationScopeView> operations = jdbc.query("""
			select route_type, operation_code from production_supervisor_operation_scope
			where employee_code = ? order by route_type, operation_code
			""", (rs, row) -> new OperationScopeView(rs.getString("route_type"), rs.getString("operation_code")), code);
		return new UserScopeView(code, supervisorRoutes, operatorRoutes, operations);
	}

	@Transactional
	public UserScopeView setUserScopes(String employeeCode, UserScopeCommand command) {
		String code = normalize(employeeCode);
		requireMember(code);
		List<String> supervisorRoutes = normalizedRoutes(command.supervisorRoutes());
		List<String> operatorRoutes = normalizedRoutes(command.operatorRoutes());
		List<OperationScopeView> operations = normalizedOperations(command.supervisorOperations(), supervisorRoutes);
		jdbc.update("delete from production_supervisor_operation_scope where employee_code = ?", code);
		jdbc.update("delete from production_supervisor_scope where employee_code = ?", code);
		jdbc.update("delete from production_operator_scope where employee_code = ?", code);
		for (String route : supervisorRoutes) {
			jdbc.update("insert into production_supervisor_scope (employee_code, route_type) values (?, ?)", code, route);
		}
		for (String route : operatorRoutes) {
			jdbc.update("insert into production_operator_scope (employee_code, route_type) values (?, ?)", code, route);
		}
		for (OperationScopeView operation : operations) {
			jdbc.update("""
				insert into production_supervisor_operation_scope (employee_code, route_type, operation_code)
				values (?, ?, ?)
				""", code, operation.routeType(), operation.operationCode());
		}
		return userScopes(code);
	}

	private RoleView requireRole(String roleCode) {
		return listRoles().stream()
			.filter(role -> role.code().equals(roleCode))
			.findFirst()
			.orElseThrow(() -> DomainException.notFound("ACCESS_ROLE_NOT_FOUND", "角色不存在"));
	}

	private void requireMember(String employeeCode) {
		Integer count = jdbc.queryForObject("""
			select count(*) from organization_member where employee_code = ? and active = true
			""", Integer.class, employeeCode);
		if (count == null || count == 0) {
			throw DomainException.notFound("ACCESS_USER_NOT_FOUND", "用户不存在");
		}
	}

	private void requireKnownRoles(List<String> roleCodes) {
		if (roleCodes.isEmpty()) {
			throw DomainException.badRequest("ACCESS_ROLE_REQUIRED", "用户至少需要一个角色");
		}
		Integer count = jdbc.queryForObject("""
			select count(*) from access_role where code in (%s)
			""".formatted(placeholders(roleCodes.size())), Integer.class, roleCodes.toArray());
		if (count == null || count != roleCodes.size()) {
			throw DomainException.badRequest("ACCESS_ROLE_UNKNOWN", "包含未知角色");
		}
	}

	private void requireKnownPermissions(List<String> permissionCodes) {
		if (permissionCodes.isEmpty()) {
			return;
		}
		Integer count = jdbc.queryForObject("""
			select count(*) from access_permission where code in (%s)
			""".formatted(placeholders(permissionCodes.size())), Integer.class,
			permissionCodes.toArray());
		if (count == null || count != permissionCodes.size()) {
			throw DomainException.badRequest("ACCESS_PERMISSION_UNKNOWN", "包含未知权限");
		}
	}

	private static List<String> normalizedRoutes(List<String> routes) {
		return routes == null ? List.of() : routes.stream().map(IdentityAccessApplication::normalize).distinct()
			.peek(route -> RouteType.valueOf(route)).toList();
	}

	private static List<OperationScopeView> normalizedOperations(List<OperationScopeView> operations, List<String> supervisorRoutes) {
		if (operations == null) return List.of();
		return operations.stream()
			.map(operation -> new OperationScopeView(normalize(operation.routeType()), normalize(operation.operationCode())))
			.peek(operation -> {
				RouteType.valueOf(operation.routeType());
				if (!supervisorRoutes.contains(operation.routeType())) {
					throw DomainException.badRequest("ACCESS_SCOPE_ROUTE_REQUIRED", "工序范围必须属于已选择的主管产线");
				}
			})
			.distinct().toList();
	}

	private Map<String, List<String>> groupedValues(String sql) {
		Map<String, Set<String>> grouped = new LinkedHashMap<>();
		jdbc.query(sql, (RowCallbackHandler) rs -> grouped
			.computeIfAbsent(rs.getString("owner_code"), ignored -> new LinkedHashSet<>())
			.add(rs.getString("value_code")));
		Map<String, List<String>> result = new LinkedHashMap<>();
		grouped.forEach((key, value) -> result.put(key, new ArrayList<>(value)));
		return result;
	}

	private static UserRow mapUserRow(ResultSet rs, int rowNum) throws SQLException {
		return new UserRow(rs.getString("employee_code"), rs.getString("name"),
			rs.getString("unit_code"), rs.getString("unit_name"), rs.getString("role_code"),
			rs.getBoolean("active"), rs.getString("account_code") != null, rs.getBoolean("must_change_password"),
			rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toInstant());
	}

	private static List<String> normalizedDistinct(List<String> values) {
		return values.stream().map(IdentityAccessApplication::normalize).distinct().toList();
	}

	private static String placeholders(int count) {
		return String.join(",", java.util.Collections.nCopies(count, "?"));
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private record UserRow(String employeeCode, String name, String unitCode,
			String unitName, String primaryRole, boolean active, boolean loginInitialized, boolean mustChangePassword, java.time.Instant lastLoginAt) {
	}

	public record CreateRoleCommand(String code, String name, String description) {
	}

	public record UserView(String employeeCode, String name, String unitCode, String unitName,
			String primaryRole, List<String> roles, List<String> permissions, boolean active,
			boolean loginInitialized, boolean mustChangePassword, java.time.Instant lastLoginAt) {
	}

	public record RoleView(String code, String name, String description, List<String> permissions) {
	}

	public record PermissionView(String code, String name, String moduleCode) {
	}

	public record UserScopeCommand(List<String> supervisorRoutes, List<String> operatorRoutes,
			List<OperationScopeView> supervisorOperations) { }

	public record UserScopeView(String employeeCode, List<String> supervisorRoutes, List<String> operatorRoutes,
			List<OperationScopeView> supervisorOperations) { }

	public record OperationScopeView(String routeType, String operationCode) { }
}

@RestController
@RequestMapping("/api/access")
class IdentityAccessController {

	private final IdentityAccessApplication access;
	private final com.renyi.mes.common.BusinessAccess business;

	IdentityAccessController(IdentityAccessApplication access, com.renyi.mes.common.BusinessAccess business) {
		this.access = access;
		this.business = business;
	}

	@GetMapping("/users")
	List<IdentityAccessApplication.UserView> users() {
		return access.listUsers().stream().filter(user -> business.canBrowseEmployee(user.employeeCode()))
			.map(user -> !business.secured() || business.role("SYSTEM_ADMIN") || user.employeeCode().equals(business.actor()) ? user
				: new IdentityAccessApplication.UserView(user.employeeCode(), user.name(), user.unitCode(), user.unitName(), user.primaryRole(), user.roles(), List.of(), user.active(), false, false, null)).toList();
	}

	@GetMapping("/users/{employeeCode}")
	IdentityAccessApplication.UserView user(@PathVariable String employeeCode) {
		return access.getUser(employeeCode);
	}

	@GetMapping("/users/{employeeCode}/scopes")
	IdentityAccessApplication.UserScopeView scopes(@PathVariable String employeeCode) {
		return access.userScopes(employeeCode);
	}

	@PostMapping("/users/{employeeCode}/scopes")
	IdentityAccessApplication.UserScopeView setScopes(@PathVariable String employeeCode,
			@Valid @RequestBody UserScopeRequest request) {
		return access.setUserScopes(employeeCode, new IdentityAccessApplication.UserScopeCommand(
			request.supervisorRoutes(), request.operatorRoutes(), request.supervisorOperations() == null ? List.of()
				: request.supervisorOperations().stream()
					.map(scope -> new IdentityAccessApplication.OperationScopeView(scope.routeType(), scope.operationCode()))
					.toList()));
	}

	@PostMapping("/users/{employeeCode}/roles")
	IdentityAccessApplication.UserView setRoles(@PathVariable String employeeCode,
			@Valid @RequestBody CodeListRequest request) {
		return access.setUserRoles(employeeCode, request.roleCodes());
	}

	@GetMapping("/roles")
	List<IdentityAccessApplication.RoleView> roles() {
		return access.listRoles();
	}

	@PostMapping("/roles")
	@ResponseStatus(HttpStatus.CREATED)
	IdentityAccessApplication.RoleView createRole(@Valid @RequestBody CreateRoleRequest request) {
		return access.createRole(new IdentityAccessApplication.CreateRoleCommand(
			request.code(), request.name(), request.description()));
	}

	@PostMapping("/roles/{roleCode}/permissions")
	IdentityAccessApplication.RoleView setPermissions(@PathVariable String roleCode,
			@Valid @RequestBody PermissionListRequest request) {
		return access.setRolePermissions(roleCode, request.permissionCodes());
	}

	@GetMapping("/permissions")
	List<IdentityAccessApplication.PermissionView> permissions() {
		return access.listPermissions();
	}

	record CodeListRequest(@NotEmpty List<@NotBlank @Size(max = 64) String> roleCodes) {
	}

	record PermissionListRequest(@NotNull List<@NotBlank @Size(max = 64) String> permissionCodes) {
	}

	record UserScopeRequest(List<@NotBlank @Size(max = 32) String> supervisorRoutes,
			List<@NotBlank @Size(max = 32) String> operatorRoutes,
			List<@Valid OperationScopeRequest> supervisorOperations) {
	}

	record OperationScopeRequest(@NotBlank @Size(max = 32) String routeType,
			@NotBlank @Size(max = 64) String operationCode) {
	}

	record CreateRoleRequest(@NotBlank @Size(max = 64) String code,
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Size(max = 500) String description) {
	}
}
