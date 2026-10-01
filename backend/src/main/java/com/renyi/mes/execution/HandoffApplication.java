package com.renyi.mes.execution;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.notification.NotificationApplication;
import com.renyi.mes.planning.PlanningApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandoffApplication {

	private final JdbcTemplate jdbc;
	private final PlanningApplication planning;
	private final NotificationApplication notifications;

	public HandoffApplication(JdbcTemplate jdbc, PlanningApplication planning, NotificationApplication notifications) {
		this.jdbc = jdbc;
		this.planning = planning;
		this.notifications = notifications;
	}

	@Transactional
	public HandoffView handoff(HandoffCommand command) {
		PlanningApplication.TaskView before = planning.lockTask(command.taskId());
		String operator = normalize(command.handedOverBy());
		PlanningApplication.TaskView task = planning.handoffWithoutCount(command.taskId(), operator, command.receivedQuantity());
		BigDecimal received = command.receivedQuantity();
		String status = received == null ? "PENDING_RECEIPT" : received.compareTo(before.plannedQuantity()) == 0 ? "MATCHED" : "EXCEPTION";
		String reason = status.equals("EXCEPTION") ? optional(command.exceptionReason(), "交接数量与应交数量不一致") : command.exceptionReason();
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			insert into production_handoff_event (
				id, task_id, handoff_no, expected_quantity, received_quantity, status, exception_reason,
				handed_over_by, received_by, occurred_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""", id, task.id(), identifier(), before.plannedQuantity(), received, status, reason,
			operator, nullableNormalized(command.receivedBy()), Timestamp.from(now));
		if ("EXCEPTION".equals(status)) notifySupervisors(before, before.plannedQuantity(), received, reason);
		return new HandoffView(id, task.id(), before.plannedQuantity(), received, task.status().name(), task.countingDeferred(), status, reason, operator,
			nullableNormalized(command.receivedBy()), now);
	}

	@Transactional(readOnly = true)
	public List<HandoffView> exceptions() {
		return jdbc.query("""
			select id, task_id, expected_quantity, received_quantity, status, exception_reason,
				handed_over_by, received_by, occurred_at
			from production_handoff_event where status = 'EXCEPTION' order by occurred_at desc
			""", (rs, rowNum) -> new HandoffView(
			rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getBigDecimal("expected_quantity"),
			rs.getBigDecimal("received_quantity"), "COMPLETED", true, rs.getString("status"), rs.getString("exception_reason"),
			rs.getString("handed_over_by"), rs.getString("received_by"), rs.getTimestamp("occurred_at").toInstant()));
	}

	@Transactional(readOnly = true)
	public ReceiptView receipt(UUID receivingTaskId) {
		PlanningApplication.TaskView receivingTask = planning.getTask(receivingTaskId);
		SourceTask source = sourceFor(receivingTask);
		return existingReceipt(receivingTask.id()).orElseGet(() -> pendingReceipt(receivingTask, source));
	}

	@Transactional
	public ReceiptView acceptReceipt(ReceiptCommand command) {
		PlanningApplication.TaskView receivingTask = planning.lockTask(command.receivingTaskId());
		String receiver = normalize(command.receivedBy());
		if (receivingTask.status() == com.renyi.mes.planning.TaskStatus.BLOCKED) {
			throw DomainException.conflict("HANDOFF_RECEIPT_NOT_READY", "当前任务尚无可接收的上游流转");
		}
		if (!receiver.equals(receivingTask.assignedTo())) {
			throw DomainException.forbidden("HANDOFF_RECEIPT_OPERATOR_FORBIDDEN", "只有当前任务的受派操作工可以确认接收");
		}
		SourceTask source = sourceFor(receivingTask);
		if (!"COMPLETED".equals(source.status()) && !source.partialFlow()) {
			throw DomainException.conflict("HANDOFF_RECEIPT_SOURCE_INCOMPLETE", "上游工序尚未完成，不能确认接收");
		}
		ReceiptView existing = existingReceipt(receivingTask.id()).orElse(null);
		if (existing != null) return existing;
		if (source.goodQuantity().signum() <= 0) return pendingReceipt(receivingTask, source);

		BigDecimal received = command.receivedQuantity();
		String exceptionType = normalizeExceptionType(command.exceptionType());
		boolean quantityMismatch = received.compareTo(source.goodQuantity()) != 0;
		boolean hasException = quantityMismatch || exceptionType != null;
		String status = hasException ? "EXCEPTION" : "MATCHED";
		String reason = hasException
			? optional(command.exceptionReason(), defaultExceptionReason(exceptionType, quantityMismatch))
			: blank(command.exceptionReason());
		UUID eventId = pendingSourceHandoff(source.id()).orElseGet(UUID::randomUUID);
		Instant now = Instant.now();
		if (existingEvent(eventId)) {
			jdbc.update("""
				update production_handoff_event
				set receiving_task_id = ?, expected_quantity = ?, received_quantity = ?, status = ?, exception_type = ?, exception_reason = ?,
					received_by = ?, photo_url = ?, received_device_code = ?, received_workstation_code = ?, occurred_at = ?
				where id = ?
				""", receivingTask.id(), source.goodQuantity(), received, status, exceptionType, reason, receiver,
				blank(command.photoUrl()), blank(command.deviceCode()), blank(command.workstationCode()), Timestamp.from(now), eventId);
		} else {
			jdbc.update("""
				insert into production_handoff_event (
					id, task_id, receiving_task_id, handoff_no, expected_quantity, received_quantity, status, exception_type, exception_reason,
					photo_url, handed_over_by, received_by, received_device_code, received_workstation_code, occurred_at
				) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", eventId, source.id(), receivingTask.id(), identifier(), source.goodQuantity(), received, status, exceptionType, reason,
				blank(command.photoUrl()), source.assignedTo() == null ? "SYSTEM" : source.assignedTo(), receiver,
				blank(command.deviceCode()), blank(command.workstationCode()), Timestamp.from(now));
		}
		ReceiptView result = receipt(receivingTask.id());
		if ("EXCEPTION".equals(status)) notifySupervisors(receivingTask, source.goodQuantity(), received, reason);
		return result;
	}

	private ReceiptView pendingReceipt(PlanningApplication.TaskView receivingTask, SourceTask source) {
		return new ReceiptView(receivingTask.id(), source.id(), source.taskNo(), source.operationName(), source.goodQuantity(), null,
			source.goodQuantity().signum() <= 0 ? "NOT_REQUIRED" : "PENDING_RECEIPT", null, null, null, null, null);
	}

	private SourceTask sourceFor(PlanningApplication.TaskView receivingTask) {
		List<SourceTask> partialSources = jdbc.query("""
			select source.id, source.task_no, source.operation_name, release.quantity, source.assigned_to, source.status
			from partial_flow_release release
			join planning_task receiving on receiving.batch_id = release.target_batch_id
			join planning_task source on source.id = release.source_task_id
			where receiving.id = ?
			""", (rs, row) -> new SourceTask(rs.getObject("id", UUID.class), rs.getString("task_no"),
			rs.getString("operation_name"), rs.getBigDecimal("quantity"), rs.getString("assigned_to"), rs.getString("status"), true), receivingTask.id());
		if (!partialSources.isEmpty()) return partialSources.getFirst();
		if (receivingTask.sequenceNo() == 1) throw DomainException.conflict("HANDOFF_RECEIPT_SOURCE_MISSING", "首道工序没有上游交接");
		return jdbc.query("""
			select source.id, source.task_no, source.operation_name, source.good_quantity, source.assigned_to, source.status
			from planning_task receiving
			join planning_task source on source.batch_id = receiving.batch_id and source.sequence_no = receiving.sequence_no - 1
			where receiving.id = ?
			""", (rs, row) -> new SourceTask(rs.getObject("id", UUID.class), rs.getString("task_no"), rs.getString("operation_name"),
			rs.getBigDecimal("good_quantity"), rs.getString("assigned_to"), rs.getString("status"), false), receivingTask.id()).stream()
			.findFirst().orElseThrow(() -> DomainException.conflict("HANDOFF_RECEIPT_SOURCE_MISSING", "未找到上游工序"));
	}

	private java.util.Optional<ReceiptView> existingReceipt(UUID receivingTaskId) {
		return jdbc.query("""
			select h.id, h.task_id, source.task_no, source.operation_name, h.expected_quantity, h.received_quantity, h.status,
				h.exception_type, h.exception_reason, h.photo_url, h.received_by, h.occurred_at
			from production_handoff_event h
			join planning_task source on source.id = h.task_id
			where h.receiving_task_id = ?
			order by h.occurred_at desc
			""", (rs, row) -> new ReceiptView(receivingTaskId, rs.getObject("task_id", UUID.class), rs.getString("task_no"),
			rs.getString("operation_name"), rs.getBigDecimal("expected_quantity"), rs.getBigDecimal("received_quantity"), rs.getString("status"),
			rs.getString("exception_type"), rs.getString("exception_reason"), rs.getString("photo_url"), rs.getString("received_by"),
			rs.getTimestamp("occurred_at").toInstant()), receivingTaskId).stream().findFirst();
	}

	private java.util.Optional<UUID> pendingSourceHandoff(UUID sourceTaskId) {
		return jdbc.query("""
			select id from production_handoff_event
			where task_id = ? and status = 'PENDING_RECEIPT' and receiving_task_id is null
			order by occurred_at desc
			""", (rs, row) -> rs.getObject(1, UUID.class), sourceTaskId).stream().findFirst();
	}

	private boolean existingEvent(UUID eventId) {
		Integer count = jdbc.queryForObject("select count(*) from production_handoff_event where id = ?", Integer.class, eventId);
		return count != null && count > 0;
	}

	private void notifySupervisors(PlanningApplication.TaskView task, BigDecimal expectedQuantity, BigDecimal receivedQuantity, String reason) {
		List<String> recipients = jdbc.query("""
			select distinct member.employee_code
			from organization_member member
			join organization_member_role role on role.employee_code = member.employee_code
			where member.active = true and (
				role.role_code = 'PRODUCTION_MANAGER'
				or exists (
					select 1 from production_supervisor_operation_scope scope
					where scope.employee_code = member.employee_code and scope.route_type = ? and scope.operation_code = ?
				)
			)
			""", (rs, row) -> rs.getString(1), task.routeType().name(), task.operationCode());
		String content = "%s / %s：应交 %s，实收 %s。%s".formatted(task.taskNo(), task.operationName(),
			expectedQuantity.stripTrailingZeros().toPlainString(), receivedQuantity.stripTrailingZeros().toPlainString(), reason);
		for (String recipient : recipients) {
			notifications.create(new NotificationApplication.CreateCommand(recipient, "TASK", "工序交接异常待处理", content, "/handoff-exceptions"));
		}
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) throw DomainException.badRequest("HANDOFF_OPERATOR_REQUIRED", "Handoff operator is required");
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String nullableNormalized(String value) {
		return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
	}

	private static String optional(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
	private static String normalizeExceptionType(String value) {
		if (value == null || value.isBlank() || "NONE".equalsIgnoreCase(value)) return null;
		String type = value.trim().toUpperCase(Locale.ROOT);
		if (!List.of("QUANTITY_MISMATCH", "MISSING_ITEMS", "QUALITY_SUSPECT", "DAMAGED", "OTHER").contains(type)) {
			throw DomainException.badRequest("HANDOFF_EXCEPTION_TYPE_INVALID", "不支持的交接异常类型");
		}
		return type;
	}
	private static String defaultExceptionReason(String exceptionType, boolean quantityMismatch) {
		if ("MISSING_ITEMS".equals(exceptionType)) return "接收时发现缺件，待主管复核";
		if ("QUALITY_SUSPECT".equals(exceptionType)) return "接收时发现质量待判，待主管复核";
		if ("DAMAGED".equals(exceptionType)) return "接收时发现破损，待主管复核";
		if (quantityMismatch) return "接收数量与上游合格数量不一致，待主管复核";
		return "接收时发现异常，待主管复核";
	}

	private static String identifier() {
		return "HO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

	public record HandoffCommand(UUID taskId, BigDecimal receivedQuantity, String exceptionReason,
		String handedOverBy, String receivedBy) { }
	public record HandoffView(UUID id, UUID taskId, BigDecimal expectedQuantity, BigDecimal receivedQuantity,
		String status, boolean countingDeferred, String handoffStatus, String exceptionReason, String handedOverBy,
		String receivedBy, Instant occurredAt) { }
	public record ReceiptCommand(UUID receivingTaskId, BigDecimal receivedQuantity, String exceptionType, String exceptionReason,
		String photoUrl, String receivedBy, String deviceCode, String workstationCode) { }
	public record ReceiptView(UUID receivingTaskId, UUID sourceTaskId, String sourceTaskNo, String sourceOperationName,
		BigDecimal expectedQuantity, BigDecimal receivedQuantity, String status, String exceptionType, String exceptionReason,
		String photoUrl, String receivedBy, Instant receivedAt) { }
	private record SourceTask(UUID id, String taskNo, String operationName, BigDecimal goodQuantity, String assignedTo, String status,
		boolean partialFlow) { }
}
