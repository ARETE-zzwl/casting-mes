package com.renyi.mes.execution;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.renyi.mes.engineering.RouteType;
import com.renyi.mes.common.BusinessAccess;
import com.renyi.mes.notification.NotificationApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Service
public class ManualShellDryingAlertApplication {

	private static final Duration MAX_WAIT = Duration.ofHours(48);
	private final JdbcTemplate jdbc;
	private final NotificationApplication notifications;
	private final BusinessAccess access;

	public ManualShellDryingAlertApplication(JdbcTemplate jdbc, NotificationApplication notifications, BusinessAccess access) {
		this.jdbc = jdbc;
		this.notifications = notifications;
		this.access = access;
	}

	@Scheduled(fixedDelayString = "${mes.alerts.manual-shell-drying-check-ms:3600000}")
	@Transactional
	public void refreshScheduled() {
		refresh();
	}

	@Transactional
	public void refresh() {
		Instant now = Instant.now();
		List<Candidate> overdue = jdbc.query("""
			select t.id as task_id, t.task_no, t.operation_name, t.operation_code, w.order_id, o.order_no,
			       w.product_name, w.product_material, w.route_type, sr.id as shell_record_id, sr.layer_count, sr.occurred_at
			from planning_task t
			join planning_batch b on b.id = t.batch_id
			join planning_work_order w on w.id = b.work_order_id
			join customer_order_header o on o.id = w.order_id
			join shell_building_record sr on sr.task_id = t.id
			where t.status = 'IN_PROGRESS'
			  and (t.operation_code = 'MANUAL_SHELL_BUILDING' or t.shell_line_mode = 'MANUAL')
			  and sr.next_action = 'WAIT_NEXT_LAYER'
			  and sr.occurred_at <= ?
			  and not exists (
			    select 1 from shell_building_record later
			    where later.task_id = sr.task_id and later.occurred_at > sr.occurred_at
			  )
			""", ManualShellDryingAlertApplication::candidate, Timestamp.from(now.minus(MAX_WAIT)));
		Set<UUID> overdueTaskIds = new LinkedHashSet<>();
		for (Candidate item : overdue) {
			overdueTaskIds.add(item.taskId());
			List<UUID> existing = jdbc.query("select id from manual_shell_drying_alert where task_id = ?", (rs, row) -> rs.getObject(1, UUID.class), item.taskId());
			if (!existing.isEmpty() && isOpen(existing.getFirst())) {
				jdbc.update("update manual_shell_drying_alert set latest_shell_record_id = ?, last_evaluated_at = ? where task_id = ? and status = 'OPEN'",
					item.shellRecordId(), Timestamp.from(now), item.taskId());
				continue;
			}
			if (existing.isEmpty()) {
				jdbc.update("""
					insert into manual_shell_drying_alert (id, task_id, latest_shell_record_id, status, first_detected_at, last_evaluated_at)
					values (?, ?, ?, 'OPEN', ?, ?)
					""", UUID.randomUUID(), item.taskId(), item.shellRecordId(), Timestamp.from(now), Timestamp.from(now));
			}
			else {
				jdbc.update("""
					update manual_shell_drying_alert
					set latest_shell_record_id = ?, status = 'OPEN', first_detected_at = ?, last_evaluated_at = ?, resolved_at = null
					where id = ?
					""", item.shellRecordId(), Timestamp.from(now), Timestamp.from(now), existing.getFirst());
			}
			notifyRecipients(item, now);
		}
		if (overdueTaskIds.isEmpty()) {
			jdbc.update("update manual_shell_drying_alert set status = 'RESOLVED', resolved_at = ?, last_evaluated_at = ? where status = 'OPEN'", Timestamp.from(now), Timestamp.from(now));
		} else {
			String placeholders = String.join(",", java.util.Collections.nCopies(overdueTaskIds.size(), "?"));
			jdbc.update("update manual_shell_drying_alert set status = 'RESOLVED', resolved_at = ?, last_evaluated_at = ? where status = 'OPEN' and task_id not in (" + placeholders + ")",
				args(Timestamp.from(now), Timestamp.from(now), overdueTaskIds));
		}
	}

	@Transactional
	public List<AlertView> list(String recipientCode) {
		refresh();
		String recipient = normalize(recipientCode);
		return jdbc.query("""
			select a.id, a.task_id, t.task_no, t.operation_name, w.order_id, o.order_no, w.product_name, w.product_material,
			       w.route_type, sr.layer_count, sr.occurred_at, a.first_detected_at, a.last_evaluated_at
			from manual_shell_drying_alert a
			join planning_task t on t.id = a.task_id
			join planning_batch b on b.id = t.batch_id
			join planning_work_order w on w.id = b.work_order_id
			join customer_order_header o on o.id = w.order_id
			join shell_building_record sr on sr.id = a.latest_shell_record_id
			where a.status = 'OPEN' order by sr.occurred_at
			""", ManualShellDryingAlertApplication::view).stream()
			.filter(alert -> canView(recipient, alert.routeType())).toList();
	}

	private void notifyRecipients(Candidate item, Instant now) {
		String waitingHours = String.valueOf(Math.max(48, Duration.between(item.waitingSince(), now).toHours()));
		String content = "%s · %s · %s；第 %d 层制壳后已等待 %s 小时，请确认干燥状态、现场环境与下一层排程。"
			.formatted(item.orderNo(), item.productName(), item.taskNo(), item.layerCount(), waitingHours);
		for (String recipient : recipients(item.routeType())) {
			notifications.createDirect(new NotificationApplication.CreateCommand(recipient, "TASK", "人工制壳干燥停留预警", content, "/production-alerts"));
		}
	}

	private Set<String> recipients(RouteType routeType) {
		return new LinkedHashSet<>(jdbc.query("""
			select distinct employee_code from production_supervisor_operation_scope
			where route_type = ? and operation_code in ('SHELL_BUILDING', 'MANUAL_SHELL_BUILDING')
			union
			select distinct member.employee_code from organization_member member
			join organization_member_role role on role.employee_code = member.employee_code
			where member.active = true and role.role_code in ('GENERAL_MANAGER', 'SYSTEM_ADMIN', 'PRODUCTION_MANAGER', 'FRONT_DESK_CLERK')
			""", (rs, row) -> rs.getString(1), routeType.name()));
	}

	private boolean canView(String recipientCode, RouteType routeType) {
		Integer central = jdbc.queryForObject("""
			select count(*) from organization_member_role
			where employee_code = ? and role_code in ('GENERAL_MANAGER', 'SYSTEM_ADMIN', 'PRODUCTION_MANAGER', 'FRONT_DESK_CLERK')
			""", Integer.class, recipientCode);
		if (access.secured() ? access.role("GENERAL_MANAGER", "SYSTEM_ADMIN", "PRODUCTION_MANAGER", "FRONT_DESK_CLERK") : central != null && central > 0) return true;
		Integer scoped = jdbc.queryForObject("""
			select count(*) from production_supervisor_operation_scope
			where employee_code = ? and route_type = ? and operation_code in ('SHELL_BUILDING', 'MANUAL_SHELL_BUILDING')
			""", Integer.class, recipientCode, routeType.name());
		return scoped != null && scoped > 0;
	}

	private static Object[] args(Object first, Object second, Set<UUID> remaining) {
		Object[] values = new Object[remaining.size() + 2];
		values[0] = first;
		values[1] = second;
		int index = 2;
		for (UUID id : remaining) values[index++] = id;
		return values;
	}

	private boolean isOpen(UUID alertId) {
		Integer open = jdbc.queryForObject("select count(*) from manual_shell_drying_alert where id = ? and status = 'OPEN'", Integer.class, alertId);
		return open != null && open > 0;
	}

	private static Candidate candidate(ResultSet rs, int row) throws SQLException {
		return new Candidate(rs.getObject("task_id", UUID.class), rs.getString("task_no"), rs.getString("operation_name"),
			rs.getObject("order_id", UUID.class), rs.getString("order_no"), rs.getString("product_name"), rs.getString("product_material"),
			RouteType.valueOf(rs.getString("route_type")), rs.getObject("shell_record_id", UUID.class), rs.getInt("layer_count"),
			rs.getTimestamp("occurred_at").toInstant());
	}

	private static AlertView view(ResultSet rs, int row) throws SQLException {
		Instant waitingSince = rs.getTimestamp("occurred_at").toInstant();
		return new AlertView(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getString("task_no"),
			rs.getString("operation_name"), rs.getObject("order_id", UUID.class), rs.getString("order_no"), rs.getString("product_name"),
			rs.getString("product_material"), RouteType.valueOf(rs.getString("route_type")), rs.getInt("layer_count"), waitingSince,
			Math.max(0, Duration.between(waitingSince, Instant.now()).toHours()), rs.getTimestamp("first_detected_at").toInstant(),
			rs.getTimestamp("last_evaluated_at").toInstant());
	}

	private static String normalize(String value) { return value.trim().toUpperCase(Locale.ROOT); }

	private record Candidate(UUID taskId, String taskNo, String operationName, UUID orderId, String orderNo, String productName,
		String productMaterial, RouteType routeType, UUID shellRecordId, int layerCount, Instant waitingSince) { }

	public record AlertView(UUID id, UUID taskId, String taskNo, String operationName, UUID orderId, String orderNo,
		String productName, String productMaterial, RouteType routeType, int layerCount, Instant waitingSince, long waitingHours,
		Instant firstDetectedAt, Instant lastEvaluatedAt) { }
}

@RestController
@RequestMapping("/api/manual-shell-drying-alerts")
class ManualShellDryingAlertController {
	private final ManualShellDryingAlertApplication alerts;
	ManualShellDryingAlertController(ManualShellDryingAlertApplication alerts) { this.alerts = alerts; }
	@GetMapping List<ManualShellDryingAlertApplication.AlertView> list(@RequestParam String recipientCode) {
		return alerts.list(recipientCode);
	}
}
