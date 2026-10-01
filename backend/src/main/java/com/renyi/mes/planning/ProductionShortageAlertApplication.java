package com.renyi.mes.planning;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.BusinessAccess;
import com.renyi.mes.notification.NotificationApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Service
public class ProductionShortageAlertApplication {

	private final JdbcTemplate jdbc;
	private final NotificationApplication notifications;
	private final BusinessAccess access;

	public ProductionShortageAlertApplication(JdbcTemplate jdbc, NotificationApplication notifications, BusinessAccess access) {
		this.jdbc = jdbc;
		this.notifications = notifications;
		this.access = access;
	}

	@Transactional
	public void refreshForWorkOrder(UUID workOrderId) {
		Calculation calculation = calculation(workOrderId);
		BigDecimal shortage = calculation.demandQuantity().subtract(calculation.projectedQuantity()).max(BigDecimal.ZERO);
		AlertRow existing = find(workOrderId).orElse(null);
		Instant now = Instant.now();
		if (shortage.signum() == 0) {
			if (existing != null && "OPEN".equals(existing.status())) {
				jdbc.update("""
					update production_shortage_alert
					set projected_quantity = ?, shortage_quantity = 0, status = 'RESOLVED', last_evaluated_at = ?, resolved_at = ?
					where work_order_id = ?
					""", calculation.projectedQuantity(), Timestamp.from(now), Timestamp.from(now), workOrderId);
			}
			return;
		}

		boolean notify = existing == null || !"OPEN".equals(existing.status()) || existing.shortageQuantity().compareTo(shortage) != 0;
		if (existing == null) {
			jdbc.update("""
				insert into production_shortage_alert (
					id, work_order_id, order_id, order_line_id, route_type, demand_quantity, projected_quantity, shortage_quantity,
					status, detected_at, last_evaluated_at
				) values (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
				""", UUID.randomUUID(), calculation.workOrderId(), calculation.orderId(), calculation.orderLineId(), calculation.routeType(),
				calculation.demandQuantity(), calculation.projectedQuantity(), shortage, Timestamp.from(now), Timestamp.from(now));
		} else {
			jdbc.update("""
				update production_shortage_alert
				set demand_quantity = ?, projected_quantity = ?, shortage_quantity = ?, status = 'OPEN', last_evaluated_at = ?, resolved_at = null
				where work_order_id = ?
				""", calculation.demandQuantity(), calculation.projectedQuantity(), shortage, Timestamp.from(now), workOrderId);
		}
		if (notify) notifyRecipients(calculation, shortage);
	}

	@Transactional(readOnly = true)
	public List<AlertView> list(String recipientCode) {
		String recipient = normalize(recipientCode);
		return jdbc.query("""
			select alert.*, work_order.work_order_no, work_order.product_name, order_header.order_no
			from production_shortage_alert alert
			join planning_work_order work_order on work_order.id = alert.work_order_id
			join customer_order_header order_header on order_header.id = alert.order_id
			where alert.status = 'OPEN'
			order by shortage_quantity desc, last_evaluated_at desc
			""", (rs, row) -> new AlertView(rs.getObject("id", UUID.class), rs.getObject("work_order_id", UUID.class),
			rs.getObject("order_id", UUID.class), rs.getObject("order_line_id", UUID.class), rs.getString("order_no"),
			rs.getString("work_order_no"), rs.getString("product_name"), rs.getString("route_type"),
			rs.getBigDecimal("demand_quantity"), rs.getBigDecimal("projected_quantity"), rs.getBigDecimal("shortage_quantity"),
			rs.getString("status"), rs.getTimestamp("detected_at").toInstant(), rs.getTimestamp("last_evaluated_at").toInstant()))
			.stream().filter(alert -> canView(recipient, alert.routeType())).toList();
	}

	private Calculation calculation(UUID workOrderId) {
		WorkOrderRow workOrder = jdbc.query("""
			select w.id, w.order_id, w.order_line_id, w.route_type, w.work_order_no, w.product_name, o.order_no, l.ordered_quantity
			from planning_work_order w
			join customer_order_header o on o.id = w.order_id
			join customer_order_line l on l.id = w.order_line_id
			where w.id = ?
			""", (rs, row) -> new WorkOrderRow(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
			rs.getObject("order_line_id", UUID.class), rs.getString("route_type"), rs.getString("work_order_no"), rs.getString("product_name"),
			rs.getString("order_no"), rs.getBigDecimal("ordered_quantity")), workOrderId).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("WORK_ORDER_NOT_FOUND", "工单不存在"));
		BigDecimal projected = jdbc.queryForObject("""
			select coalesce(sum(batch_quantity), 0) from (
				select b.id, min(case when t.status = 'COMPLETED' then t.good_quantity else t.planned_quantity end) batch_quantity
				from planning_batch b join planning_task t on t.batch_id = b.id
				where b.work_order_id = ?
				group by b.id
			) forecast
			""", BigDecimal.class, workOrderId);
		return new Calculation(workOrder.id(), workOrder.orderId(), workOrder.orderLineId(), workOrder.routeType(),
			workOrder.workOrderNo(), workOrder.orderNo(), workOrder.productName(), workOrder.demandQuantity(), projected == null ? BigDecimal.ZERO : projected);
	}

	private void notifyRecipients(Calculation calculation, BigDecimal shortage) {
		List<String> recipients = jdbc.query("""
			select distinct member.employee_code
			from organization_member member
			join organization_member_role role on role.employee_code = member.employee_code
			where member.active = true and (
				role.role_code in ('SYSTEM_ADMIN', 'GENERAL_MANAGER', 'PRODUCTION_MANAGER')
				or exists (select 1 from production_supervisor_scope scope where scope.employee_code = member.employee_code and scope.route_type = ?)
			)
			""", (rs, row) -> rs.getString(1), calculation.routeType());
		String content = "%s / %s：订单需求 %s，当前可交付上限 %s，预计缺口 %s。请评估补投、返工或交期处理。".formatted(
			calculation.orderNo(), calculation.productName(), number(calculation.demandQuantity()), number(calculation.projectedQuantity()), number(shortage));
		for (String recipient : recipients) {
			notifications.create(new NotificationApplication.CreateCommand(recipient, "TASK", "订单生产缺口预警", content, "/production-alerts"));
		}
	}

	private java.util.Optional<AlertRow> find(UUID workOrderId) {
		return jdbc.query("select work_order_id, shortage_quantity, status from production_shortage_alert where work_order_id = ? for update",
			(rs, row) -> new AlertRow(rs.getObject("work_order_id", UUID.class), rs.getBigDecimal("shortage_quantity"), rs.getString("status")), workOrderId).stream().findFirst();
	}

	private boolean canView(String recipientCode, String routeType) {
		Integer manager = jdbc.queryForObject("""
			select count(*) from organization_member_role
			where employee_code = ? and role_code in ('SYSTEM_ADMIN', 'GENERAL_MANAGER', 'PRODUCTION_MANAGER')
			""", Integer.class, recipientCode);
		if (access.secured() ? access.role("SYSTEM_ADMIN", "GENERAL_MANAGER", "PRODUCTION_MANAGER") : manager != null && manager > 0) return true;
		Integer scoped = jdbc.queryForObject("""
			select count(*) from production_supervisor_scope where employee_code = ? and route_type = ?
			""", Integer.class, recipientCode, routeType);
		return scoped != null && scoped > 0;
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) throw DomainException.badRequest("ALERT_RECIPIENT_REQUIRED", "预警查看人不能为空");
		return value.trim().toUpperCase(Locale.ROOT);
	}
	private static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

	public record AlertView(UUID id, UUID workOrderId, UUID orderId, UUID orderLineId, String orderNo, String workOrderNo,
		String productName, String routeType,
		BigDecimal demandQuantity, BigDecimal projectedQuantity, BigDecimal shortageQuantity, String status, Instant detectedAt, Instant lastEvaluatedAt) { }
	private record WorkOrderRow(UUID id, UUID orderId, UUID orderLineId, String routeType, String workOrderNo, String productName,
		String orderNo, BigDecimal demandQuantity) { }
	private record Calculation(UUID workOrderId, UUID orderId, UUID orderLineId, String routeType, String workOrderNo,
		String orderNo, String productName, BigDecimal demandQuantity, BigDecimal projectedQuantity) { }
	private record AlertRow(UUID workOrderId, BigDecimal shortageQuantity, String status) { }
}

@RestController
@RequestMapping("/api/production-alerts")
class ProductionShortageAlertController {
	private final ProductionShortageAlertApplication alerts;
	ProductionShortageAlertController(ProductionShortageAlertApplication alerts) { this.alerts = alerts; }
	@GetMapping List<ProductionShortageAlertApplication.AlertView> list(@RequestParam String recipientCode) {
		return alerts.list(recipientCode);
	}
}
