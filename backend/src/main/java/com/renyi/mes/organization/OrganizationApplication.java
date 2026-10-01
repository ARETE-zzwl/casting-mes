package com.renyi.mes.organization;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class OrganizationApplication {

	private static final List<String> UNIT_TYPES = List.of("COMPANY", "FACTORY", "WORKSHOP", "TEAM");
	private final JdbcTemplate jdbc;

	public OrganizationApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public UnitView createUnit(CreateUnitCommand command) {
		String code = normalize(command.code());
		String type = normalize(command.unitType());
		if (!UNIT_TYPES.contains(type)) {
			throw DomainException.badRequest("ORGANIZATION_TYPE_INVALID", "组织类型不受支持");
		}
		String parentCode = blankToNull(command.parentCode());
		if (parentCode != null && !existsUnit(normalize(parentCode))) {
			throw DomainException.badRequest("ORGANIZATION_PARENT_NOT_FOUND", "上级组织不存在");
		}
		UUID id = UUID.randomUUID();
		try {
			jdbc.update("""
				insert into organization_unit (id, code, name, unit_type, parent_code, active, created_at)
				values (?, ?, ?, ?, ?, true, ?)
				""", id, code, command.name().trim(), type,
				parentCode == null ? null : normalize(parentCode), Timestamp.from(Instant.now()));
		}
		catch (DuplicateKeyException exception) {
			throw DomainException.conflict("ORGANIZATION_CODE_EXISTS", "组织编码已存在");
		}
		return requireUnit(id);
	}

	@Transactional
	public MemberView createMember(CreateMemberCommand command) {
		String unitCode = normalize(command.unitCode());
		String roleCode = normalize(command.roleCode());
		if (!existsUnit(unitCode)) {
			throw DomainException.badRequest("ORGANIZATION_UNIT_NOT_FOUND", "成员所属组织不存在");
		}
		Integer roleCount = jdbc.queryForObject(
			"select count(*) from access_role where code = ?", Integer.class, roleCode);
		if (roleCount == null || roleCount == 0) {
			throw DomainException.badRequest("ACCESS_ROLE_UNKNOWN", "岗位角色不存在");
		}
		UUID id = UUID.randomUUID();
		try {
			jdbc.update("""
				insert into organization_member (
					id, employee_code, name, unit_code, role_code, active, created_at
				) values (?, ?, ?, ?, ?, true, ?)
				""", id, normalize(command.employeeCode()), command.name().trim(), unitCode,
				roleCode, Timestamp.from(Instant.now()));
			jdbc.update("""
				insert into organization_member_role (employee_code, role_code) values (?, ?)
				""", normalize(command.employeeCode()), roleCode);
		}
		catch (DuplicateKeyException exception) {
			throw DomainException.conflict("EMPLOYEE_CODE_EXISTS", "员工工号已存在");
		}
		return requireMember(id);
	}

	@Transactional
	public MemberView maintainMember(String employeeCode, MaintainMemberCommand command) {
		String code = normalize(employeeCode);
		String unitCode = normalize(command.unitCode());
		String roleCode = normalize(command.roleCode());
		if (!existsUnit(unitCode)) {
			throw DomainException.badRequest("ORGANIZATION_UNIT_NOT_FOUND", "The member unit does not exist");
		}
		Integer roleCount = jdbc.queryForObject("select count(*) from access_role where code = ?", Integer.class, roleCode);
		if (roleCount == null || roleCount == 0) {
			throw DomainException.badRequest("ACCESS_ROLE_UNKNOWN", "The primary role does not exist");
		}
		int changed = jdbc.update("""
			update organization_member set name = ?, unit_code = ?, role_code = ?, active = ?
			where employee_code = ?
			""", command.name().trim(), unitCode, roleCode, command.active(), code);
		if (changed == 0) throw DomainException.notFound("MEMBER_NOT_FOUND", "The member does not exist");
		jdbc.update("""
			insert into organization_member_role (employee_code, role_code) values (?, ?)
			on conflict do nothing
			""", code, roleCode);
		return jdbc.query("select * from organization_member where employee_code = ?", OrganizationApplication::mapMember, code)
			.stream().findFirst().orElseThrow(() -> DomainException.notFound("MEMBER_NOT_FOUND", "The member does not exist"));
	}

	@Transactional(readOnly = true)
	public List<UnitView> listUnits() {
		return jdbc.query("select * from organization_unit order by code", OrganizationApplication::mapUnit);
	}

	@Transactional(readOnly = true)
	public List<MemberView> listMembers() {
		return jdbc.query("select * from organization_member order by employee_code",
			OrganizationApplication::mapMember);
	}

	private boolean existsUnit(String code) {
		Integer count = jdbc.queryForObject(
			"select count(*) from organization_unit where code = ? and active = true", Integer.class, code);
		return count != null && count > 0;
	}

	private UnitView requireUnit(UUID id) {
		return jdbc.query("select * from organization_unit where id = ?",
				OrganizationApplication::mapUnit, id).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("ORGANIZATION_NOT_FOUND", "组织不存在"));
	}

	private MemberView requireMember(UUID id) {
		return jdbc.query("select * from organization_member where id = ?",
				OrganizationApplication::mapMember, id).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("MEMBER_NOT_FOUND", "成员不存在"));
	}

	private static UnitView mapUnit(ResultSet rs, int rowNum) throws SQLException {
		return new UnitView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
			rs.getString("unit_type"), rs.getString("parent_code"), rs.getBoolean("active"),
			rs.getTimestamp("created_at").toInstant());
	}

	private static MemberView mapMember(ResultSet rs, int rowNum) throws SQLException {
		return new MemberView(rs.getObject("id", UUID.class), rs.getString("employee_code"),
			rs.getString("name"), rs.getString("unit_code"), rs.getString("role_code"),
			rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant());
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	public record CreateUnitCommand(String code, String name, String unitType, String parentCode) {
	}

	public record CreateMemberCommand(String employeeCode, String name, String unitCode, String roleCode) {
	}

	public record MaintainMemberCommand(String name, String unitCode, String roleCode, boolean active) {
	}

	public record UnitView(UUID id, String code, String name, String unitType, String parentCode,
			boolean active, Instant createdAt) {
	}

	public record MemberView(UUID id, String employeeCode, String name, String unitCode,
			String roleCode, boolean active, Instant createdAt) {
	}
}

@RestController
@RequestMapping("/api/organization")
class OrganizationController {

	private final OrganizationApplication organization;
	private final com.renyi.mes.common.BusinessAccess access;

	OrganizationController(OrganizationApplication organization, com.renyi.mes.common.BusinessAccess access) {
		this.organization = organization;
		this.access = access;
	}

	@GetMapping("/units")
	List<OrganizationApplication.UnitView> units() {
		return organization.listUnits();
	}

	@PostMapping("/units")
	@ResponseStatus(HttpStatus.CREATED)
	OrganizationApplication.UnitView createUnit(@Valid @RequestBody UnitRequest request) {
		return organization.createUnit(new OrganizationApplication.CreateUnitCommand(
			request.code(), request.name(), request.unitType(), request.parentCode()));
	}

	@GetMapping("/members")
	List<OrganizationApplication.MemberView> members() {
		return organization.listMembers().stream().filter(member -> access.canBrowseEmployee(member.employeeCode())).toList();
	}

	@PostMapping("/members")
	@ResponseStatus(HttpStatus.CREATED)
	OrganizationApplication.MemberView createMember(@Valid @RequestBody MemberRequest request) {
		return organization.createMember(new OrganizationApplication.CreateMemberCommand(
			request.employeeCode(), request.name(), request.unitCode(), request.roleCode()));
	}

	@PostMapping("/members/{employeeCode}/maintenance")
	OrganizationApplication.MemberView maintainMember(@PathVariable String employeeCode, @Valid @RequestBody MemberMaintenanceRequest request) {
		return organization.maintainMember(employeeCode, new OrganizationApplication.MaintainMemberCommand(
			request.name(), request.unitCode(), request.roleCode(), request.active()));
	}

	record UnitRequest(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 160) String name,
			@NotBlank String unitType, @Size(max = 64) String parentCode) {
	}

	record MemberRequest(@NotBlank @Size(max = 64) String employeeCode,
			@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 64) String unitCode,
			@NotBlank @Size(max = 64) String roleCode) {
	}

	record MemberMaintenanceRequest(@NotBlank @Size(max = 100) String name,
			@NotBlank @Size(max = 64) String unitCode, @NotBlank @Size(max = 64) String roleCode,
			boolean active) {
	}
}
