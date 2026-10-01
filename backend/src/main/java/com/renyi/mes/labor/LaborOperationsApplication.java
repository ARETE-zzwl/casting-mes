package com.renyi.mes.labor;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.BusinessAccess;
import com.renyi.mes.execution.ExecutionApplication;
import com.renyi.mes.execution.ExecutionApplication.SupervisorReportCommand;
import com.renyi.mes.execution.ManualShellDryingAlertApplication;
import com.renyi.mes.planning.PlanningApplication;
import com.renyi.mes.planning.ProductionLineScopeApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Service
public class LaborOperationsApplication {
	private final JdbcTemplate jdbc;
	private final PlanningApplication planning;
	private final ExecutionApplication execution;
	private final ProductionLineScopeApplication lineScopes;
	private final ManualShellDryingAlertApplication dryingAlerts;
	private final BusinessAccess access;

	public LaborOperationsApplication(JdbcTemplate jdbc, PlanningApplication planning, ExecutionApplication execution, ProductionLineScopeApplication lineScopes,
			ManualShellDryingAlertApplication dryingAlerts, BusinessAccess access) {
		this.jdbc = jdbc; this.planning = planning; this.execution = execution; this.lineScopes = lineScopes; this.dryingAlerts = dryingAlerts;
		this.access = access;
	}

	@Transactional
	public TimeEntryView recordTime(TimeCommand command) {
		access.requireManageTask(command.taskId());
		access.requireAssignedWorker(command.taskId(), command.workerCode());
		var task = planning.lockTask(command.taskId());
		if (!"HOURLY".equals(task.compensationMode())) throw DomainException.conflict("LABOR_TIME_MODE_INVALID", "This task is not paid by hours");
		UUID id = UUID.randomUUID(); Instant now = Instant.now();
		jdbc.update("""
			insert into labor_time_entry (id, entry_no, task_id, worker_code, hours, recorded_by, source, status, occurred_at)
			values (?, ?, ?, ?, ?, ?, ?, 'CANDIDATE', ?)""", id, no("LT"), task.id(), norm(command.workerCode()), command.hours(), norm(command.recordedBy()), command.source(), Timestamp.from(now));
		return time(id);
	}

	@Transactional
	public ManualSheetView createSheet(ManualSheetCommand command) {
		access.requireManageTask(command.taskId());
		access.requireAssignedWorker(command.taskId(), command.workerCode());
		var task = planning.getTask(command.taskId());
		ensureScope(command.enteredBy(), task);
		boolean quantity = "QUANTITY".equals(norm(command.reportKind()));
		if (!quantity && !"HOURS".equals(command.reportKind())) throw DomainException.badRequest("MANUAL_SHEET_KIND_INVALID", "Report kind must be QUANTITY or HOURS");
		if (quantity && (command.goodQuantity() == null || command.scrapQuantity() == null)) throw DomainException.badRequest("MANUAL_SHEET_QUANTITY_REQUIRED", "Good and scrap quantities are required");
		if (!quantity && command.hours() == null) throw DomainException.badRequest("MANUAL_SHEET_HOURS_REQUIRED", "Hours are required");
		if (quantity && (command.goodQuantity().signum() < 0 || command.scrapQuantity().signum() < 0
				|| command.goodQuantity().stripTrailingZeros().scale() > 0 || command.scrapQuantity().stripTrailingZeros().scale() > 0
				|| command.goodQuantity().add(command.scrapQuantity()).signum() == 0)) {
			throw DomainException.badRequest("MANUAL_SHEET_QUANTITY_INVALID", "合格数和报废数必须为非负整数，合计必须大于零");
		}
		if (!quantity && command.hours().signum() <= 0) throw DomainException.badRequest("MANUAL_SHEET_HOURS_INVALID", "工时必须大于零");
		UUID id = UUID.randomUUID(); Instant now = Instant.now();
		jdbc.update("""
			insert into manual_report_sheet (id, sheet_no, paper_form_no, paper_image_url, task_id, worker_code, report_kind, good_quantity, scrap_quantity, hours, note, entered_by, entered_at, status)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')""", id, no("PS"), blank(command.paperFormNo()) == null ? no("PF") : command.paperFormNo().trim(), blank(command.paperImageUrl()), command.taskId(), norm(command.workerCode()), norm(command.reportKind()), command.goodQuantity(), command.scrapQuantity(), command.hours(), blank(command.note()), norm(command.enteredBy()), Timestamp.from(now));
		return sheet(id);
	}

	@Transactional
	public ManualSheetView approveSheet(UUID id, String reviewer) {
		ManualSheetView sheet = sheetForUpdate(id);
		access.requireManageTask(sheet.taskId());
		access.requireAssignedWorker(sheet.taskId(), sheet.workerCode());
		ensureScope(reviewer, planning.getTask(sheet.taskId()));
		if (!"PENDING".equals(sheet.status())) throw DomainException.conflict("MANUAL_SHEET_STATE_INVALID", "Only pending paper reports can be approved");
		if ("QUANTITY".equals(sheet.reportKind())) {
			if (planning.getTask(sheet.taskId()).status() == com.renyi.mes.planning.TaskStatus.ASSIGNED) planning.start(sheet.taskId(), sheet.workerCode());
			execution.reportBySupervisor(new SupervisorReportCommand(UUID.randomUUID(), sheet.taskId(), sheet.goodQuantity(), sheet.scrapQuantity(), reviewer, "PAPER_ENTRY", null));
		}
		else recordTime(new TimeCommand(sheet.taskId(), sheet.workerCode(), sheet.hours(), reviewer, "PAPER"));
		jdbc.update("update manual_report_sheet set status = 'APPROVED', reviewed_by = ?, reviewed_at = ? where id = ?", norm(reviewer), Timestamp.from(Instant.now()), id);
		return sheet(id);
	}

	@Transactional
	public ManualSheetView rejectSheet(UUID id, String reviewer, String reason) {
		ManualSheetView sheet = sheetForUpdate(id);
		access.requireManageTask(sheet.taskId());
		ensureScope(reviewer, planning.getTask(sheet.taskId()));
		if (!"PENDING".equals(sheet.status())) throw DomainException.conflict("MANUAL_SHEET_STATE_INVALID", "仅待审核纸单可以退回");
		jdbc.update("update manual_report_sheet set status = 'REJECTED', rejected_by = ?, rejected_at = ?, rejection_reason = ? where id = ?",
			norm(reviewer), Timestamp.from(Instant.now()), reason.trim(), id);
		return sheet(id);
	}

	@Transactional(readOnly = true)
	public List<ManualSheetView> listSheets(String supervisorCode) {
		return jdbc.query("select * from manual_report_sheet order by entered_at desc", (rs, row) -> mapSheet(rs)).stream()
			.filter(sheet -> lineScopes.canDispatch(supervisorCode, planning.getTask(sheet.taskId()).routeType(), planning.getTask(sheet.taskId()).operationCode()))
			.toList();
	}

	@Transactional
	public ShellRecordView recordShell(ShellRecordCommand command) {
		access.requireManageTask(command.taskId());
		var task = planning.lockTask(command.taskId());
		if (!task.operationCode().equals("SHELL_BUILDING") && !task.operationCode().equals("MANUAL_SHELL_BUILDING")) throw DomainException.conflict("SHELL_RECORD_TASK_INVALID", "Shell records are only allowed for shell-building tasks");
		String nextAction = normalizeShellAction(command.nextAction());
		if (isManualShellTask(task)) {
			Integer lastLayer = jdbc.queryForObject("select coalesce(max(layer_count), 0) from shell_building_record where task_id = ?", Integer.class, task.id());
			if (command.layerCount() != (lastLayer == null ? 0 : lastLayer) + 1) {
				throw DomainException.conflict("MANUAL_SHELL_LAYER_SEQUENCE_INVALID", "人工制壳必须按下一层顺序逐层报备");
			}
			if ("FLOW_TO_NEXT".equals(nextAction) && command.quantity().compareTo(task.plannedQuantity()) != 0) {
				throw DomainException.conflict("MANUAL_SHELL_FLOW_QUANTITY_INVALID", "流转下工序时，制壳数量必须与当前任务数量一致");
			}
		}
		UUID id = UUID.randomUUID(); Instant now = Instant.now();
		jdbc.update("""
			insert into shell_building_record (id, task_id, operation_id, method, layer_count, drying_minutes, quantity, operator_code, note, next_action, photo_url, occurred_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, task.id(), command.operationId(), command.method(), command.layerCount(), command.dryingMinutes(), command.quantity(), norm(command.operatorCode()), command.note(), nextAction, blank(command.photoUrl()), Timestamp.from(now));
		if (isManualShellTask(task) && "FLOW_TO_NEXT".equals(nextAction)) {
			if ("HANDOFF_TO_NEXT".equals(task.reportingMode())) planning.handoffWithoutCount(task.id(), command.operatorCode(), command.quantity());
			else planning.applyReport(task.id(), command.quantity(), BigDecimal.ZERO, command.operatorCode());
		}
		dryingAlerts.refresh();
		return shell(id);
	}

	@Transactional
	public ShellRecordView recordScannedManualShell(ScannedShellCommand command) {
		UUID taskId = taskIdFromScan(command.scannedValue());
		var task = planning.getTask(taskId);
		if (!isManualShellTask(task)) {
			throw DomainException.conflict("MANUAL_SHELL_TASK_REQUIRED", "扫码内容未关联人工制壳任务");
		}
		if ("READY".equals(task.status().name()) || "ASSIGNED".equals(task.status().name())) {
			planning.claimAndStart(taskId, command.operatorCode());
		}
		var activeTask = planning.getTask(taskId);
		if (!norm(command.operatorCode()).equals(activeTask.assignedTo())) {
			throw DomainException.forbidden("SHELL_PROGRESS_OPERATOR_FORBIDDEN", "该人工制壳任务已由其他员工执行");
		}
		return recordShell(new ShellRecordCommand(taskId, UUID.randomUUID(), "MANUAL", command.layerCount(),
			command.dryingMinutes(), command.quantity(), command.operatorCode(), command.note(), command.nextAction(), command.photoUrl()));
	}

	@Transactional(readOnly = true) public List<ShellRecordView> shellRecords(UUID taskId) { return jdbc.query("select * from shell_building_record where task_id = ? order by occurred_at", (rs, n) -> new ShellRecordView(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getString("method"), rs.getInt("layer_count"), rs.getInt("drying_minutes"), rs.getBigDecimal("quantity"), rs.getString("operator_code"), rs.getString("note"), rs.getString("next_action"), rs.getString("photo_url"), rs.getTimestamp("occurred_at").toInstant()), taskId); }
	@Transactional(readOnly = true)
	public List<ShellProgressSummary> shellSummaries(List<UUID> taskIds) {
		if (taskIds == null || taskIds.isEmpty()) return List.of();
		taskIds.forEach(access::requireTask);
		String placeholders = String.join(",", java.util.Collections.nCopies(taskIds.size(), "?"));
		return jdbc.query("""
			select task_id, count(*) as record_count, coalesce(max(layer_count), 0) as latest_layer,
			       max(case when next_action = 'FLOW_TO_NEXT' then 1 else 0 end) as flowed_to_next,
			       max(occurred_at) as latest_occurred_at
			from shell_building_record where task_id in (%s) group by task_id
			""".formatted(placeholders), (rs, n) -> new ShellProgressSummary(
				rs.getObject("task_id", UUID.class), rs.getInt("record_count"), rs.getInt("latest_layer"),
				rs.getInt("flowed_to_next") > 0, rs.getTimestamp("latest_occurred_at").toInstant()), taskIds.toArray());
	}
	private TimeEntryView time(UUID id) { return jdbc.query("select * from labor_time_entry where id = ?", (rs,n) -> new TimeEntryView(rs.getObject("id", UUID.class),rs.getString("entry_no"),rs.getObject("task_id", UUID.class),rs.getString("worker_code"),rs.getBigDecimal("hours"),rs.getString("recorded_by"),rs.getString("source"),rs.getString("status"),rs.getTimestamp("occurred_at").toInstant()), id).getFirst(); }
	private ManualSheetView sheet(UUID id) { return jdbc.query("select * from manual_report_sheet where id = ?", (rs,n) -> mapSheet(rs), id).getFirst(); }
	private ManualSheetView sheetForUpdate(UUID id) {
		List<UUID> rows = jdbc.query("select id from manual_report_sheet where id = ? for update", (rs, n) -> rs.getObject(1, UUID.class), id);
		if (rows.isEmpty()) throw DomainException.notFound("MANUAL_SHEET_NOT_FOUND", "Paper report sheet not found");
		return sheet(id);
	}
	private ShellRecordView shell(UUID id) { return jdbc.query("select * from shell_building_record where id = ?", (rs,n) -> new ShellRecordView(rs.getObject("id", UUID.class),rs.getObject("task_id", UUID.class),rs.getString("method"),rs.getInt("layer_count"),rs.getInt("drying_minutes"),rs.getBigDecimal("quantity"),rs.getString("operator_code"),rs.getString("note"),rs.getString("next_action"),rs.getString("photo_url"),rs.getTimestamp("occurred_at").toInstant()), id).getFirst(); }
	private UUID taskIdFromScan(String scannedValue) {
		String value = scannedValue == null ? "" : scannedValue.trim();
		if (value.startsWith("MES:TASK:")) {
			try { return UUID.fromString(value.substring("MES:TASK:".length())); }
			catch (IllegalArgumentException error) { throw DomainException.badRequest("SHELL_SCAN_INVALID", "工单二维码格式无效"); }
		}
		String batchNo = value.startsWith("MES:BATCH:") ? value.substring("MES:BATCH:".length()) : value;
		UUID batchId;
		try { batchId = UUID.fromString(batchNo.trim()); } catch (IllegalArgumentException invalid) { batchId = null; }
		return jdbc.query("""
			select t.id from planning_task t join planning_batch b on b.id = t.batch_id
			where %s
			  and (t.operation_code = 'MANUAL_SHELL_BUILDING'
			       or (t.operation_code = 'SHELL_BUILDING' and t.shell_line_mode = 'MANUAL'))
			order by t.created_at desc limit 1
			""".formatted(batchId == null ? "b.batch_no = ?" : "b.id = ?"), (rs, row) -> rs.getObject(1, UUID.class), batchId == null ? batchNo.trim().toUpperCase(Locale.ROOT) : batchId)
			.stream().findFirst().orElseThrow(() -> DomainException.notFound("MANUAL_SHELL_BATCH_NOT_FOUND", "未找到该批次的人工制壳任务"));
	}
	private void ensureScope(String supervisorCode, PlanningApplication.TaskView task) {
		if (!lineScopes.canDispatch(supervisorCode, task.routeType(), task.operationCode())) throw DomainException.forbidden("PAPER_REPORT_SCOPE_FORBIDDEN", "当前主管无权处理该产线工序的纸质报工");
	}
	private static ManualSheetView mapSheet(java.sql.ResultSet rs) throws java.sql.SQLException {
		Timestamp entered = rs.getTimestamp("entered_at"); Timestamp reviewed = rs.getTimestamp("reviewed_at"); Timestamp rejected = rs.getTimestamp("rejected_at");
		return new ManualSheetView(rs.getObject("id", UUID.class), rs.getString("sheet_no"), rs.getString("paper_form_no"), rs.getString("paper_image_url"), rs.getObject("task_id", UUID.class), rs.getString("worker_code"), rs.getString("report_kind"), rs.getBigDecimal("good_quantity"), rs.getBigDecimal("scrap_quantity"), rs.getBigDecimal("hours"), rs.getString("note"), rs.getString("entered_by"), entered == null ? null : entered.toInstant(), rs.getString("status"), rs.getString("reviewed_by"), reviewed == null ? null : reviewed.toInstant(), rs.getString("rejected_by"), rejected == null ? null : rejected.toInstant(), rs.getString("rejection_reason"));
	}
	private static String norm(String s) { return s.trim().toUpperCase(Locale.ROOT); }
	private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
	private static String normalizeShellAction(String value) {
		String action = value == null || value.isBlank() ? "WAIT_NEXT_LAYER" : value.trim().toUpperCase(Locale.ROOT);
		if (!action.equals("WAIT_NEXT_LAYER") && !action.equals("FLOW_TO_NEXT")) throw DomainException.badRequest("SHELL_NEXT_ACTION_INVALID", "制壳后续动作无效");
		return action;
	}
	private static boolean isManualShellTask(PlanningApplication.TaskView task) {
		return "MANUAL_SHELL_BUILDING".equals(task.operationCode()) || "MANUAL".equals(task.shellLineMode());
	}
	private static String no(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT); }
	public record TimeCommand(UUID taskId,String workerCode,BigDecimal hours,String recordedBy,String source) { }
	public record ManualSheetCommand(UUID taskId,String workerCode,String reportKind,BigDecimal goodQuantity,BigDecimal scrapQuantity,BigDecimal hours,String note,String enteredBy,String paperFormNo,String paperImageUrl) { }
	public record ShellRecordCommand(UUID taskId,UUID operationId,String method,int layerCount,int dryingMinutes,BigDecimal quantity,String operatorCode,String note,String nextAction,String photoUrl) {
		public ShellRecordCommand(UUID taskId, UUID operationId, String method, int layerCount, int dryingMinutes, BigDecimal quantity, String operatorCode, String note) { this(taskId, operationId, method, layerCount, dryingMinutes, quantity, operatorCode, note, null, null); }
	}
	public record ScannedShellCommand(String scannedValue,int layerCount,int dryingMinutes,BigDecimal quantity,String operatorCode,String note,String nextAction,String photoUrl) { }
	public record TimeEntryView(UUID id,String entryNo,UUID taskId,String workerCode,BigDecimal hours,String recordedBy,String source,String status,Instant occurredAt) { }
	public record ManualSheetView(UUID id,String sheetNo,String paperFormNo,String paperImageUrl,UUID taskId,String workerCode,String reportKind,BigDecimal goodQuantity,BigDecimal scrapQuantity,BigDecimal hours,String note,String enteredBy,Instant enteredAt,String status,String reviewedBy,Instant reviewedAt,String rejectedBy,Instant rejectedAt,String rejectionReason) { }
	public record ShellRecordView(UUID id,UUID taskId,String method,int layerCount,int dryingMinutes,BigDecimal quantity,String operatorCode,String note,String nextAction,String photoUrl,Instant occurredAt) { }
	public record ShellProgressSummary(UUID taskId,int recordCount,int latestLayer,boolean flowedToNext,Instant latestOccurredAt) { }
}

@RestController
@RequestMapping("/api/labor")
class LaborOperationsController {
	private final LaborOperationsApplication labor;
	LaborOperationsController(LaborOperationsApplication labor) { this.labor = labor; }
	@PostMapping("/time-entries") @ResponseStatus(HttpStatus.CREATED) LaborOperationsApplication.TimeEntryView time(@Valid @RequestBody TimeRequest r) { return labor.recordTime(new LaborOperationsApplication.TimeCommand(r.taskId(),r.workerCode(),r.hours(),r.recordedBy(),r.source() == null ? "SUPERVISOR" : r.source())); }
	@PostMapping("/paper-sheets") @ResponseStatus(HttpStatus.CREATED) LaborOperationsApplication.ManualSheetView paper(@Valid @RequestBody PaperRequest r) { return labor.createSheet(new LaborOperationsApplication.ManualSheetCommand(r.taskId(),r.workerCode(),r.reportKind(),r.goodQuantity(),r.scrapQuantity(),r.hours(),r.note(),r.enteredBy(),r.paperFormNo(),r.paperImageUrl())); }
	@PostMapping("/paper-sheets/{id}/approval") LaborOperationsApplication.ManualSheetView approve(@PathVariable UUID id,@Valid @RequestBody ApprovalRequest r) { return labor.approveSheet(id,r.reviewerCode()); }
	@PostMapping("/paper-sheets/{id}/rejection") LaborOperationsApplication.ManualSheetView reject(@PathVariable UUID id,@Valid @RequestBody RejectionRequest r) { return labor.rejectSheet(id,r.reviewerCode(),r.reason()); }
	@GetMapping("/paper-sheets") List<LaborOperationsApplication.ManualSheetView> paperSheets(@RequestParam String supervisorCode) { return labor.listSheets(supervisorCode); }
	@PostMapping("/shell-records") @ResponseStatus(HttpStatus.CREATED) LaborOperationsApplication.ShellRecordView shell(@Valid @RequestBody ShellRequest r) { return labor.recordShell(new LaborOperationsApplication.ShellRecordCommand(r.taskId(),r.operationId(),r.method(),r.layerCount(),r.dryingMinutes(),r.quantity(),r.operatorCode(),r.note(),r.nextAction(),r.photoUrl())); }
	@PostMapping("/manual-shell/scan-progress") @ResponseStatus(HttpStatus.CREATED) LaborOperationsApplication.ShellRecordView scannedShell(@Valid @RequestBody ScannedShellRequest r) { return labor.recordScannedManualShell(new LaborOperationsApplication.ScannedShellCommand(r.scannedValue(),r.layerCount(),r.dryingMinutes(),r.quantity(),r.operatorCode(),r.note(),r.nextAction(),r.photoUrl())); }
	@GetMapping("/shell-records") List<LaborOperationsApplication.ShellRecordView> shells(@RequestParam UUID taskId) { return labor.shellRecords(taskId); }
	@GetMapping("/shell-records/summary") List<LaborOperationsApplication.ShellProgressSummary> shellSummaries(@RequestParam(required = false) List<UUID> taskId) { return labor.shellSummaries(taskId); }
	record TimeRequest(@NotNull UUID taskId,@NotBlank String workerCode,@NotNull @DecimalMin("0.001") BigDecimal hours,@NotBlank String recordedBy,String source) { }
	record PaperRequest(@NotNull UUID taskId,@NotBlank String workerCode,@NotBlank String reportKind,BigDecimal goodQuantity,BigDecimal scrapQuantity,BigDecimal hours,@Size(max=500) String note,@NotBlank String enteredBy,@Size(max=64) String paperFormNo,@Size(max=1000) String paperImageUrl) { }
	record ApprovalRequest(@NotBlank String reviewerCode) { }
	record RejectionRequest(@NotBlank String reviewerCode,@NotBlank @Size(max=500) String reason) { }
	record ShellRequest(@NotNull UUID taskId,@NotNull UUID operationId,@NotBlank String method,@DecimalMin("0") int layerCount,@DecimalMin("0") int dryingMinutes,@NotNull @DecimalMin("0.001") BigDecimal quantity,@NotBlank String operatorCode,@Size(max=500) String note,@Size(max=32) String nextAction,@Size(max=1000) String photoUrl) { }
	record ScannedShellRequest(@NotBlank @Size(max=256) String scannedValue,@DecimalMin("0") int layerCount,@DecimalMin("0") int dryingMinutes,@NotNull @DecimalMin("0.001") BigDecimal quantity,@NotBlank String operatorCode,@Size(max=500) String note,@Size(max=32) String nextAction,@Size(max=1000) String photoUrl) { }
}
