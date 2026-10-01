package com.renyi.mes.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.BusinessAccess;
import com.renyi.mes.common.PageResult;
import com.renyi.mes.execution.internal.ExecutionReportEntity;
import com.renyi.mes.execution.internal.ExecutionReportRepository;
import com.renyi.mes.planning.PlanningApplication;
import com.renyi.mes.planning.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionApplication {

	private final ExecutionReportRepository reports;
	private final PlanningApplication planning;
	private final OperationalAuditApplication audit;
	private final JdbcTemplate jdbc;
	private final ReportFormProfileApplication reportForms;
	private final BusinessAccess access;

	public ExecutionApplication(ExecutionReportRepository reports, PlanningApplication planning, OperationalAuditApplication audit,
			JdbcTemplate jdbc, ReportFormProfileApplication reportForms, BusinessAccess access) {
		this.reports = reports;
		this.planning = planning;
		this.audit = audit;
		this.jdbc = jdbc;
		this.reportForms = reportForms;
		this.access = access;
	}

	@Transactional
	public ReportResult report(ReportCommand command) {
		planning.lockTask(command.taskId());
		ExecutionReportEntity existing = reports.findByOperationId(command.operationId()).orElse(null);
		if (existing != null) {
			if (!existing.taskId().equals(command.taskId())) {
				throw DomainException.conflict("OPERATION_ID_REUSED", "操作号已被其他任务使用");
			}
			if (existing.goodQuantity().compareTo(command.goodQuantity()) != 0
					|| existing.scrapQuantity().compareTo(command.scrapQuantity()) != 0
					|| !existing.operatorCode().equals(normalizeOperator(command.operatorCode()))) {
				throw DomainException.conflict(
					"OPERATION_ID_PAYLOAD_MISMATCH", "相同操作号的报工内容不一致");
			}
			return new ReportResult(toView(existing), true);
		}

		PlanningApplication.TaskView task = planning.applyReport(
			command.taskId(),
			command.goodQuantity(),
			command.scrapQuantity(),
			command.operatorCode()
		);
		validateConfiguredFields(task.operationCode(), command.deviceCode(), command.workstationCode(), command.photoUrl());
		ExecutionReportEntity report = reports.save(new ExecutionReportEntity(
			UUID.randomUUID(),
			command.operationId(),
			task.id(),
			command.goodQuantity(),
			command.scrapQuantity(),
			normalizeOperator(command.operatorCode()),
			task.goodQuantity(),
			task.scrapQuantity(),
			task.status(),
			command.deviceCode(),
			command.workstationCode(), command.photoUrl(),
			Instant.now()
		));
		audit.record("REPORT", "TASK", task.id(), command.operationId(), command.operatorCode(), command.deviceCode(), command.workstationCode(), "报工");
		return new ReportResult(toView(report), false);
	}

	@Transactional(readOnly = true)
	public List<ReportView> reportsForTask(UUID taskId) {
		planning.getTask(taskId);
		return reports.findByTaskIdOrderByOccurredAt(taskId).stream()
			.map(ExecutionApplication::toView)
			.toList();
	}

	@Transactional(readOnly = true)
	public List<ReportLedgerView> reportLedger(String viewerCode, String workerCode, UUID orderId,
			String operationCode, Instant from, Instant to) {
		LedgerQuery query = ledgerQuery(viewerCode, workerCode, orderId, operationCode, from, to);
		return jdbc.query(selectLedger() + query.where() + " order by r.occurred_at desc", ExecutionApplication::mapLedger, query.parameters().toArray());
	}

	@Transactional(readOnly = true)
	public PageResult<ReportLedgerView> searchReportLedger(String viewerCode, String workerCode, UUID orderId,
			String operationCode, Instant from, Instant to, int page, int size) {
		if (page < 0 || size < 1 || size > 100) {
			throw DomainException.badRequest("INVALID_PAGE_REQUEST", "页码必须从 0 开始，单页数量为 1 至 100");
		}
		LedgerQuery query = ledgerQuery(viewerCode, workerCode, orderId, operationCode, from, to);
		Long total = jdbc.queryForObject("select count(*) " + ledgerFrom() + query.where(), Long.class, query.parameters().toArray());
		List<Object> parameters = new ArrayList<>(query.parameters());
		parameters.add(size);
		parameters.add(Math.multiplyExact((long) page, size));
		List<ReportLedgerView> items = jdbc.query(selectLedger() + query.where() + " order by r.occurred_at desc limit ? offset ?",
			ExecutionApplication::mapLedger, parameters.toArray());
		return PageResult.of(items, page, size, total == null ? 0 : total);
	}

	private LedgerQuery ledgerQuery(String viewerCode, String workerCode, UUID orderId, String operationCode, Instant from, Instant to) {
		String viewer = normalizeOperator(viewerCode);
		boolean elevated = hasRole(viewer, "PRODUCTION_MANAGER", "WORKSHOP_SUPERVISOR", "MID_WAX_SUPERVISOR", "LOW_WAX_SUPERVISOR", "MID_SHELL_SUPERVISOR", "LOW_SHELL_SUPERVISOR", "POST_PROCESS_SUPERVISOR", "FINISHING_SUPERVISOR", "GENERAL_MANAGER", "SYSTEM_ADMIN", "GLOBAL_SCHEDULER", "FINANCE_REVIEWER", "QUALITY_INSPECTOR", "QUALITY_ENGINEER");
		boolean global = hasRole(viewer, "PRODUCTION_MANAGER", "GENERAL_MANAGER", "SYSTEM_ADMIN", "GLOBAL_SCHEDULER", "FINANCE_REVIEWER", "QUALITY_INSPECTOR", "QUALITY_ENGINEER");
		String effectiveWorker = !elevated ? viewer : blankToNull(workerCode) == null ? null : normalizeOperator(workerCode);
		List<String> clauses = new ArrayList<>();
		List<Object> parameters = new ArrayList<>();
		if (effectiveWorker != null) { clauses.add("r.operator_code = ?"); parameters.add(effectiveWorker); }
		if (orderId != null) { clauses.add("w.order_id = ?"); parameters.add(orderId); }
		if (blankToNull(operationCode) != null) { clauses.add("t.operation_code = ?"); parameters.add(normalizeOperator(operationCode)); }
		if (from != null) { clauses.add("r.occurred_at >= ?"); parameters.add(from); }
		if (to != null) { clauses.add("r.occurred_at <= ?"); parameters.add(to); }
		if (elevated && !global) {
			clauses.add("exists (select 1 from production_supervisor_scope scope where scope.employee_code = ? and scope.route_type = w.route_type)");
			parameters.add(viewer);
			clauses.add("(not exists (select 1 from production_supervisor_operation_scope operation_scope where operation_scope.employee_code = ? and operation_scope.route_type = w.route_type) or exists (select 1 from production_supervisor_operation_scope operation_scope where operation_scope.employee_code = ? and operation_scope.route_type = w.route_type and operation_scope.operation_code = t.operation_code))");
			parameters.add(viewer); parameters.add(viewer);
		}
		return new LedgerQuery(clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses), parameters);
	}

	private boolean hasRole(String employeeCode, String... roleCodes) {
		if (access.secured()) return employeeCode.equals(access.actor()) && access.role(roleCodes);
		String placeholders = String.join(",", java.util.Collections.nCopies(roleCodes.length, "?"));
		List<Object> parameters = new ArrayList<>(); parameters.add(employeeCode); parameters.addAll(List.of(roleCodes));
		Integer count = jdbc.queryForObject("select count(*) from organization_member_role where employee_code = ? and role_code in (" + placeholders + ")", Integer.class, parameters.toArray());
		return count != null && count > 0;
	}

	private static String selectLedger() { return "select r.id, r.task_id, r.operator_code, coalesce(r.recorded_by,r.operator_code) as recorded_by, r.good_quantity, r.scrap_quantity, r.device_code, r.workstation_code, r.photo_url, r.occurred_at, t.task_no, t.operation_code, t.operation_name, w.order_id, o.order_no, w.product_code, w.product_name, w.route_type " + ledgerFrom(); }
	private static String ledgerFrom() { return "from execution_report r join planning_task t on t.id = r.task_id join planning_batch b on b.id = t.batch_id join planning_work_order w on w.id = b.work_order_id join customer_order_header o on o.id = w.order_id "; }
	private static ReportLedgerView mapLedger(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new ReportLedgerView(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getString("task_no"), rs.getObject("order_id", UUID.class), rs.getString("order_no"), rs.getString("product_code"), rs.getString("product_name"), rs.getString("route_type"), rs.getString("operation_code"), rs.getString("operation_name"), rs.getString("operator_code"), rs.getBigDecimal("good_quantity"), rs.getBigDecimal("scrap_quantity"), rs.getString("device_code"), rs.getString("workstation_code"), rs.getString("photo_url"), rs.getTimestamp("occurred_at").toInstant(), rs.getString("recorded_by")); }
	private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
	private record LedgerQuery(String where, List<Object> parameters) { }

	/**
	 * The receiving operation sees only its direct upstream operation.  For a leading batch,
	 * reports are capped at the release moment so later source reports cannot leak into it.
	 */
	@Transactional(readOnly = true)
	public UpstreamReportsView upstreamReportsForTask(UUID taskId) {
		PlanningApplication.TaskView receivingTask = planning.getTask(taskId);
		List<PartialSource> partialSources = jdbc.query("""
			select source.id, source.task_no, source.operation_code, source.operation_name, release.released_at
			from partial_flow_release release
			join planning_task receiving on receiving.batch_id = release.target_batch_id
			join planning_task source on source.id = release.source_task_id
			where receiving.id = ?
			""", (rs, row) -> new PartialSource(
				rs.getObject("id", UUID.class), rs.getString("task_no"), rs.getString("operation_code"),
				rs.getString("operation_name"), rs.getTimestamp("released_at").toInstant()), taskId);
		if (!partialSources.isEmpty()) {
			PartialSource source = partialSources.getFirst();
			return new UpstreamReportsView(source.taskId(), source.taskNo(), source.operationCode(), source.operationName(),
				reports.findByTaskIdAndOccurredAtLessThanEqualOrderByOccurredAt(source.taskId(), source.releasedAt()).stream()
					.map(ExecutionApplication::toView).toList());
		}
		if (receivingTask.sequenceNo() <= 1) {
			return new UpstreamReportsView(null, null, null, null, List.of());
		}
		return planning.tasksForBatch(receivingTask.batchId()).stream()
			.filter(task -> task.sequenceNo() == receivingTask.sequenceNo() - 1)
			.findFirst()
			.map(source -> new UpstreamReportsView(source.id(), source.taskNo(), source.operationCode(), source.operationName(),
				reports.findByTaskIdOrderByOccurredAt(source.id()).stream().map(ExecutionApplication::toView).toList()))
			.orElseGet(() -> new UpstreamReportsView(null, null, null, null, List.of()));
	}

	@Transactional
	public ReportResult reportBySupervisor(SupervisorReportCommand command) {
		planning.lockTask(command.taskId());
		ExecutionReportEntity existing = reports.findByOperationId(command.operationId()).orElse(null);
		if (existing != null) {
			if (!existing.taskId().equals(command.taskId())
					|| existing.goodQuantity().compareTo(command.goodQuantity()) != 0
					|| existing.scrapQuantity().compareTo(command.scrapQuantity()) != 0
					|| !existing.recordedBy().equals(normalizeOperator(command.supervisorCode()))) {
				throw DomainException.conflict("OPERATION_ID_PAYLOAD_MISMATCH", "The supervisor report does not match its original payload");
			}
			return new ReportResult(toView(existing), true);
		}
		PlanningApplication.TaskView task = planning.applySupervisorReport(
			command.taskId(), command.goodQuantity(), command.scrapQuantity());
		validateConfiguredFields(task.operationCode(), command.deviceCode(), command.workstationCode(), command.photoUrl());
		ExecutionReportEntity report = reports.save(new ExecutionReportEntity(
			UUID.randomUUID(), command.operationId(), task.id(), command.goodQuantity(), command.scrapQuantity(),
			task.assignedTo() == null ? normalizeOperator(command.supervisorCode()) : task.assignedTo(), task.goodQuantity(), task.scrapQuantity(), task.status(),
			command.deviceCode(), command.workstationCode(), command.photoUrl(), Instant.now(), normalizeOperator(command.supervisorCode())));
		audit.record("SUPERVISOR_REPORT", "TASK", task.id(), command.operationId(), command.supervisorCode(), command.deviceCode(), command.workstationCode(), "主管代报");
		return new ReportResult(toView(report), false);
	}

	@Transactional
	public PlanningApplication.TaskView handoffWithoutCount(UUID taskId, String operatorCode) {
		return planning.handoffWithoutCount(taskId, operatorCode);
	}

	@Transactional
	public TreeReportResult reportTree(TreeReportCommand command) {
		planning.lockTask(command.taskId());
		ExecutionReportEntity existing = reports.findByOperationId(command.operationId()).orElse(null);
		BigDecimal calculatedGood = command.treeCount().multiply(command.piecesPerTree());
		if (existing != null) {
			if (!existing.taskId().equals(command.taskId())
					|| existing.goodQuantity().compareTo(calculatedGood) != 0
					|| existing.scrapQuantity().compareTo(command.scrapQuantity()) != 0
					|| !existing.operatorCode().equals(normalizeOperator(command.operatorCode()))) {
				throw DomainException.conflict("OPERATION_ID_PAYLOAD_MISMATCH", "The reporting operation does not match its original payload");
			}
			return new TreeReportResult(toView(existing), command.treeCount(), command.piecesPerTree(), true);
		}
		PlanningApplication.TaskView task = planning.applyTreeReport(
			command.taskId(), command.treeCount(), command.piecesPerTree(), command.scrapQuantity(), command.operatorCode());
		validateConfiguredFields(task.operationCode(), command.deviceCode(), command.workstationCode(), command.photoUrl());
		ExecutionReportEntity report = reports.save(new ExecutionReportEntity(
			UUID.randomUUID(),
			command.operationId(),
			task.id(),
			calculatedGood,
			command.scrapQuantity(),
			normalizeOperator(command.operatorCode()),
			task.goodQuantity(),
			task.scrapQuantity(),
			task.status(),
			command.deviceCode(),
			command.workstationCode(), command.photoUrl(),
			Instant.now()
		));
		audit.record("TREE_REPORT", "TASK", task.id(), command.operationId(), command.operatorCode(), command.deviceCode(), command.workstationCode(), "组树报工");
		return new TreeReportResult(toView(report), command.treeCount(), command.piecesPerTree(), false);
	}

	private static ReportView toView(ExecutionReportEntity report) {
		return new ReportView(
			report.id(),
			report.operationId(),
			report.taskId(),
			report.goodQuantity(),
			report.scrapQuantity(),
			report.operatorCode(),
			report.taskGoodTotal(),
			report.taskScrapTotal(),
			report.taskStatus(),
			report.deviceCode(),
				report.workstationCode(),
				report.photoUrl(),
			report.occurredAt(), report.recordedBy()
		);
	}

	private void validateConfiguredFields(String operationCode, String deviceCode, String workstationCode, String photoUrl) {
		ReportFormProfileApplication.ReportFormProfileView profile = reportForms.forOperation(operationCode);
		if (profile.requirePhoto() && isBlank(photoUrl)) {
			throw DomainException.badRequest("REPORT_PHOTO_REQUIRED", "该工序报工必须上传现场照片");
		}
		if (profile.requireDevice() && isBlank(deviceCode)) {
			throw DomainException.badRequest("REPORT_DEVICE_REQUIRED", "该工序报工必须填写设备编号");
		}
		if (profile.requireWorkstation() && isBlank(workstationCode)) {
			throw DomainException.badRequest("REPORT_WORKSTATION_REQUIRED", "该工序报工必须填写工位编号");
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static String normalizeOperator(String operatorCode) {
		return operatorCode.trim().toUpperCase(Locale.ROOT);
	}

	public record ReportCommand(
		UUID operationId,
		UUID taskId,
		BigDecimal goodQuantity,
		BigDecimal scrapQuantity,
		String operatorCode,
		String deviceCode,
		String workstationCode,
		String photoUrl
	) {
		public ReportCommand(UUID operationId, UUID taskId, BigDecimal goodQuantity, BigDecimal scrapQuantity,
				String operatorCode, String deviceCode, String workstationCode) {
			this(operationId, taskId, goodQuantity, scrapQuantity, operatorCode, deviceCode, workstationCode, null);
		}
	}

	public record ReportResult(ReportView report, boolean duplicate) {
	}

	public record UpstreamReportsView(
		UUID sourceTaskId,
		String sourceTaskNo,
		String sourceOperationCode,
		String sourceOperationName,
		List<ReportView> reports
	) {
	}

	public record TreeReportCommand(
		UUID operationId,
		UUID taskId,
		BigDecimal treeCount,
		BigDecimal piecesPerTree,
		BigDecimal scrapQuantity,
		String operatorCode,
		String deviceCode,
		String workstationCode,
		String photoUrl
	) {
		public TreeReportCommand(UUID operationId, UUID taskId, BigDecimal treeCount, BigDecimal piecesPerTree,
				BigDecimal scrapQuantity, String operatorCode, String deviceCode, String workstationCode) {
			this(operationId, taskId, treeCount, piecesPerTree, scrapQuantity, operatorCode, deviceCode, workstationCode, null);
		}
	}

	public record SupervisorReportCommand(
		UUID operationId,
		UUID taskId,
		BigDecimal goodQuantity,
		BigDecimal scrapQuantity,
		String supervisorCode,
		String deviceCode,
		String workstationCode,
		String photoUrl
	) {
		public SupervisorReportCommand(UUID operationId, UUID taskId, BigDecimal goodQuantity, BigDecimal scrapQuantity,
				String supervisorCode, String deviceCode, String workstationCode) {
			this(operationId, taskId, goodQuantity, scrapQuantity, supervisorCode, deviceCode, workstationCode, null);
		}
	}

	public record TreeReportResult(
		ReportView report,
		BigDecimal treeCount,
		BigDecimal piecesPerTree,
		boolean duplicate
	) {
	}

	public record ReportView(
		UUID id,
		UUID operationId,
		UUID taskId,
		BigDecimal goodQuantity,
		BigDecimal scrapQuantity,
		String operatorCode,
		BigDecimal taskGoodTotal,
		BigDecimal taskScrapTotal,
		TaskStatus taskStatus,
		String deviceCode,
		String workstationCode,
		String photoUrl,
		Instant occurredAt,
		String recordedBy
	) {
	}

	public record ReportLedgerView(
		UUID id, UUID taskId, String taskNo, UUID orderId, String orderNo, String productCode,
		String productName, String routeType, String operationCode, String operationName,
		String operatorCode, BigDecimal goodQuantity, BigDecimal scrapQuantity, String deviceCode,
		String workstationCode, String photoUrl, Instant occurredAt, String recordedBy
	) { }

	private record PartialSource(UUID taskId, String taskNo, String operationCode, String operationName, Instant releasedAt) {
	}
}
