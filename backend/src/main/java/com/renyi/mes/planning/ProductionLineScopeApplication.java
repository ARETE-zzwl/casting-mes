package com.renyi.mes.planning;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.renyi.mes.engineering.RouteType;
import com.renyi.mes.common.BusinessAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductionLineScopeApplication {

	private static final Set<String> GLOBAL_ROLES = Set.of("SYSTEM_ADMIN", "GENERAL_MANAGER", "PRODUCTION_MANAGER");

	private final JdbcTemplate jdbc;
	private final BusinessAccess access;

	public ProductionLineScopeApplication(JdbcTemplate jdbc, BusinessAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public boolean canAccess(String actorCode, RouteType routeType) {
		if (actorCode == null || actorCode.isBlank()) {
			return true;
		}
		String actor = normalize(actorCode);
		if (hasGlobalRole(actor)) {
			return true;
		}
		Integer count = jdbc.queryForObject("""
			select count(*) from production_supervisor_scope
			where employee_code = ? and route_type = ?
			""", Integer.class, actor, routeType.name());
		return count != null && count > 0;
	}

	@Transactional(readOnly = true)
	public boolean canDispatch(String actorCode, RouteType routeType, String operationCode) {
		if (!canAccess(actorCode, routeType) || actorCode == null || actorCode.isBlank() || hasGlobalRole(normalize(actorCode))) return canAccess(actorCode, routeType);
		Integer scopedOperations = jdbc.queryForObject("select count(*) from production_supervisor_operation_scope where employee_code = ? and route_type = ?", Integer.class, normalize(actorCode), routeType.name());
		if (scopedOperations == null || scopedOperations == 0) return true;
		Integer count = jdbc.queryForObject("select count(*) from production_supervisor_operation_scope where employee_code = ? and route_type = ? and operation_code = ?", Integer.class, normalize(actorCode), routeType.name(), operationCode);
		return count != null && count > 0;
	}

	@Transactional(readOnly = true)
	public boolean canOperate(String workerCode, RouteType routeType) {
		if (workerCode == null || workerCode.isBlank()) {
			return false;
		}
		String worker = normalize(workerCode);
		if (hasGlobalRole(worker)) {
			return true;
		}
		Integer count = jdbc.queryForObject("""
			select count(*) from production_operator_scope
			where employee_code = ? and route_type = ?
			""", Integer.class, worker, routeType.name());
		return count != null && count > 0;
	}

	@Transactional(readOnly = true)
	public List<RouteType> visibleRoutes(String actorCode) {
		if (actorCode == null || actorCode.isBlank() || hasGlobalRole(normalize(actorCode))) {
			return List.of(RouteType.values());
		}
		return jdbc.query("""
			select route_type from production_supervisor_scope
			where employee_code = ? order by route_type
			""", (rs, rowNum) -> RouteType.valueOf(rs.getString("route_type")), normalize(actorCode));
	}

	private boolean hasGlobalRole(String actorCode) {
		boolean administrator = !access.secured() || actorCode.equals(access.actor()) && access.role("SYSTEM_ADMIN");
		Integer count = jdbc.queryForObject("""
			select count(*) from organization_member_role
			where employee_code = ? and role_code in ('SYSTEM_ADMIN', 'GENERAL_MANAGER', 'PRODUCTION_MANAGER', 'GLOBAL_SCHEDULER')
			and (role_code <> 'SYSTEM_ADMIN' or ?)
			""", Integer.class, actorCode, administrator);
		return count != null && count > 0;
	}

	private static String normalize(String code) {
		return code.trim().toUpperCase(Locale.ROOT);
	}
}
