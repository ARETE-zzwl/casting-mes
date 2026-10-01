package com.renyi.mes.planning;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.renyi.mes.engineering.RouteType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DispatchRecommendationApplication {

	static final Map<String, Set<String>> OPERATION_ROLES = Map.ofEntries(
		Map.entry("MOLD_ISSUE", Set.of("MOLD_KEEPER")),
		Map.entry("WAX_INJECTION", Set.of("WAX_INJECTION_OPERATOR", "OPERATOR")),
		Map.entry("WAX_REPAIR", Set.of("WAX_REPAIR_OPERATOR", "OPERATOR")),
		Map.entry("TREE_ASSEMBLY", Set.of("TREE_ASSEMBLY_OPERATOR", "OPERATOR")),
		Map.entry("SHELL_BUILDING", Set.of("SHELL_BUILDING_OPERATOR", "OPERATOR")),
		Map.entry("MANUAL_SHELL_BUILDING", Set.of("SHELL_BUILDING_OPERATOR", "OPERATOR")),
		Map.entry("DEWAX", Set.of("DEWAX_OPERATOR", "OPERATOR")),
		Map.entry("POURING", Set.of("POURING_OPERATOR", "OPERATOR")),
		Map.entry("KNOCKOUT_CUTTING", Set.of("KNOCKOUT_OPERATOR", "OPERATOR")),
		Map.entry("KNOCKOUT", Set.of("KNOCKOUT_OPERATOR", "OPERATOR")),
		Map.entry("CUTTING", Set.of("CUTTING_OPERATOR", "OPERATOR")),
		Map.entry("SEMI_FINISHED_COUNT", Set.of("FINISHING_OPERATOR", "OPERATOR")),
		Map.entry("OPTIONAL_FINISHING", Set.of("FINISHING_OPERATOR", "OPERATOR")),
		Map.entry("FINAL_COUNT", Set.of("FINISHED_GOODS_KEEPER", "OPERATOR")),
		Map.entry("OUTSOURCE_DISPATCH", Set.of("PRODUCTION_MANAGER", "WORKSHOP_SUPERVISOR")),
		Map.entry("OUTSOURCE_PROGRESS", Set.of("PRODUCTION_MANAGER", "WORKSHOP_SUPERVISOR")),
		Map.entry("INCOMING_INSPECTION", Set.of("PRODUCTION_MANAGER", "WORKSHOP_SUPERVISOR"))
	);

	static Set<String> rolesForOperation(String operationCode) {
		String operation = operationCode == null ? "" : operationCode.trim().toUpperCase(Locale.ROOT);
		return OPERATION_ROLES.getOrDefault(operation, Set.of("OPERATOR"));
	}

	private final JdbcTemplate jdbc;
	private final ProductionLineScopeApplication lineScopes;

	public DispatchRecommendationApplication(JdbcTemplate jdbc, ProductionLineScopeApplication lineScopes) {
		this.jdbc = jdbc;
		this.lineScopes = lineScopes;
	}

	@Transactional(readOnly = true)
	public DispatchRecommendationView recommendations(String operationCode) {
		return recommendations(operationCode, null);
	}

	@Transactional(readOnly = true)
	public DispatchRecommendationView recommendations(String operationCode, String supervisorCode) {
		return recommendations(operationCode, supervisorCode, null);
	}

	@Transactional(readOnly = true)
	public DispatchRecommendationView recommendations(String operationCode, String supervisorCode, RouteType routeType) {
		return new DispatchRecommendationView(readyTasks(supervisorCode), workersFor(operationCode, routeType));
	}

	@Transactional(readOnly = true)
	public List<DispatchWorkerView> workersFor(String operationCode) {
		return workersFor(operationCode, null);
	}

	@Transactional(readOnly = true)
	public List<DispatchWorkerView> workersFor(String operationCode, RouteType routeType) {
		String operation = operationCode == null ? "" : operationCode.trim().toUpperCase(Locale.ROOT);
		Set<String> preferred = rolesForOperation(operation);
		List<WorkerRow> rows = jdbc.query("""
			select distinct m.employee_code, m.name, m.unit_code, mr.role_code,
			       (select count(*) from planning_task t where t.assigned_to = m.employee_code
			        and t.status in ('ASSIGNED', 'IN_PROGRESS')) as active_task_count
			from organization_member m
			join organization_member_role mr on mr.employee_code = m.employee_code
			join access_role_permission rp on rp.role_code = mr.role_code
			where m.active = true and rp.permission_code in ('TASK_EXECUTE', 'MOBILE_REPORT', 'SUPERVISOR_REPORT')
			order by m.employee_code, mr.role_code
			""", DispatchRecommendationApplication::mapWorker);
		Map<String, WorkerRow> distinctWorkers = new LinkedHashMap<>();
		for (WorkerRow row : rows) {
			if (!preferred.contains(row.roleCode()) || (routeType != null && !lineScopes.canOperate(row.employeeCode(), routeType))) {
				continue;
			}
			distinctWorkers.merge(row.employeeCode(), row,
				(current, candidate) -> current.roleCode().equals("OPERATOR") ? candidate : current);
		}
		return distinctWorkers.values().stream()
			.map(row -> new DispatchWorkerView(row.employeeCode(), row.name(), row.unitCode(),
				row.roleCode(), row.activeTaskCount(), true))
			.sorted(Comparator.comparing(DispatchWorkerView::recommended).reversed()
				.thenComparing(DispatchWorkerView::activeTaskCount)
				.thenComparing(DispatchWorkerView::employeeCode))
			.toList();
	}

	private List<DispatchTaskView> readyTasks(String supervisorCode) {
		return jdbc.query("""
			select t.id, t.task_no, t.operation_code, t.operation_name, t.planned_quantity, w.route_type,
			       w.work_order_no, w.product_code, w.product_name, o.order_no, o.priority,
			       o.requested_delivery_date
			from planning_task t
			join planning_batch b on b.id = t.batch_id
			join planning_work_order w on w.id = b.work_order_id
			join customer_order_header o on o.id = w.order_id
			where t.status = 'READY'
			  and (t.sequence_no = 1 or exists (
			    select 1 from planning_task previous_task
			    where previous_task.batch_id = t.batch_id
			      and previous_task.sequence_no = t.sequence_no - 1
			      and previous_task.status = 'COMPLETED'
			  ))
			order by case o.priority when 'URGENT' then 0 when 'SAMPLE' then 1 else 2 end,
			         o.requested_delivery_date nulls last, t.created_at
			limit 36
			""", DispatchRecommendationApplication::mapTask).stream()
			.filter(task -> lineScopes.canDispatch(supervisorCode, task.routeType(), task.operationCode()))
			.limit(12)
			.toList();
	}

	private static WorkerRow mapWorker(ResultSet rs, int rowNum) throws SQLException {
		return new WorkerRow(rs.getString("employee_code"), rs.getString("name"),
			rs.getString("unit_code"), rs.getString("role_code"), rs.getInt("active_task_count"));
	}

	private static DispatchTaskView mapTask(ResultSet rs, int rowNum) throws SQLException {
		return new DispatchTaskView(rs.getObject("id", java.util.UUID.class), rs.getString("task_no"),
			rs.getString("order_no"), rs.getString("work_order_no"), rs.getString("product_code"),
			rs.getString("product_name"), rs.getString("operation_code"), rs.getString("operation_name"),
			rs.getBigDecimal("planned_quantity"), RouteType.valueOf(rs.getString("route_type")), rs.getString("priority"),
			rs.getObject("requested_delivery_date", LocalDate.class));
	}

	private record WorkerRow(String employeeCode, String name, String unitCode, String roleCode,
			int activeTaskCount) {
	}

	public record DispatchRecommendationView(List<DispatchTaskView> tasks, List<DispatchWorkerView> workers) {
	}

	public record DispatchTaskView(java.util.UUID taskId, String taskNo, String orderNo,
			String workOrderNo, String productCode, String productName, String operationCode,
			String operationName, BigDecimal plannedQuantity, RouteType routeType, String priority, LocalDate requestedDeliveryDate) {
	}

	public record DispatchWorkerView(String employeeCode, String name, String unitCode,
			String roleCode, int activeTaskCount, boolean recommended) {
	}
}
