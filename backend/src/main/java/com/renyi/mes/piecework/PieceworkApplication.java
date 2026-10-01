package com.renyi.mes.piecework;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.BusinessAccess;
import com.renyi.mes.common.OperationalAuditPort;
import com.renyi.mes.engineering.RouteType;
import com.renyi.mes.planning.PlanningApplication;
import com.renyi.mes.planning.ProductionLineScopeApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
public class PieceworkApplication {

	private final JdbcTemplate jdbc;
	private final PlanningApplication planning;
	private final ProductionLineScopeApplication lineScopes;
	private final OperationalAuditPort audit;
	private final BusinessAccess access;

	public PieceworkApplication(JdbcTemplate jdbc, PlanningApplication planning, ProductionLineScopeApplication lineScopes,
			OperationalAuditPort audit, BusinessAccess access) {
		this.jdbc = jdbc;
		this.planning = planning;
		this.lineScopes = lineScopes;
		this.audit = audit;
		this.access = access;
	}

	@Transactional
	public RateView createRate(RateCommand command) {
		String operationCode = normalize(command.operationCode());
		if (isLowWaxProgressOnly(command.routeType(), operationCode)) {
			throw DomainException.conflict("LOW_WAX_PROGRESS_ONLY", "低温蜡间仅统计进度，不发布射蜡、修蜡或组树工资单价");
		}
		if (!lineScopes.canDispatch(command.supervisorCode(), command.routeType(), operationCode)) {
			throw DomainException.forbidden("PIECEWORK_RATE_SCOPE_DENIED", "主管无权发布该产线或工序的计件单价");
		}
		String settlementUnit = normalizeSettlementUnit(command.settlementUnit());
		String productCode = normalizeOptional(command.productCode());
		String productName = command.productName() == null || command.productName().isBlank() ? null : command.productName().trim();
		assertRateOperationEligible(operationCode, settlementUnit);
		jdbc.update("""
			update piecework_rate set active = false
			where operation_code = ? and route_type = ? and coalesce(product_code, '') = coalesce(?, '')
				and effective_from = ? and active = true
			""", operationCode, command.routeType().name(), productCode, command.effectiveFrom());
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			insert into piecework_rate (
				id, operation_code, operation_name, route_type, product_code, product_name, version, settlement_unit, unit_rate, effective_from, active, created_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?)
			""",
			id,
			operationCode,
			command.operationName().trim(),
			command.routeType().name(),
			productCode,
			productName,
			normalize(command.version()),
			settlementUnit,
			command.unitRate(),
			command.effectiveFrom(),
			Timestamp.from(now)
		);
		return requireRate(id);
	}

	@Transactional
	public EntryResult createEntry(EntryCommand command) {
		if (access.secured()) access.requireManageTask(command.taskId());
		EntryView existing = findEntry(command.operationId());
		if (existing != null) {
			return duplicate(existing, command);
		}

		PlanningApplication.TaskView task = planning.lockTask(command.taskId());
		existing = findEntry(command.operationId());
		if (existing != null) {
			return duplicate(existing, command);
		}
		String worker = normalize(command.workerCode());
		String recordedBy = recordedBy(command);
		if (task.assignedTo() == null || !task.assignedTo().equals(worker)) {
			throw DomainException.conflict("PIECEWORK_WORKER_MISMATCH", "计件人员必须是任务执行人");
		}
		if (isLowWaxProgressOnly(task.routeType(), task.operationCode())) {
			throw DomainException.conflict("LOW_WAX_PROGRESS_ONLY", "低温蜡间仅统计进度，不生成蜡间工资计件记录");
		}
		if (!recordedBy.equals(worker) && !lineScopes.canDispatch(recordedBy, task.routeType(), task.operationCode())) {
			throw DomainException.forbidden("PIECEWORK_RECORD_SCOPE_DENIED", "Current user cannot record piecework for this operation");
		}
		LocalDate settlementDate = settlementDate(task);
		RateView rate = activeRate(task.operationCode(), task.routeType(), task.productCode(), settlementDate);
		assertCompensationCompatible(task, rate);
		BigDecimal settlementLimit = settlementLimit(task, rate);
		BigDecimal registered = jdbc.queryForObject(
			"select coalesce(sum(quantity), 0) from piecework_entry where task_id = ? and status <> 'REVERSED'",
			BigDecimal.class,
			task.id()
		);
		if (registered.add(command.quantity()).compareTo(settlementLimit) > 0) {
			throw DomainException.conflict("PIECEWORK_QUANTITY_EXCEEDED", "累计计件数不能超过任务合格数");
		}
		BigDecimal amount = command.quantity().multiply(rate.unitRate());
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			insert into piecework_entry (
				id, operation_id, entry_no, task_id, task_no, operation_code, operation_name,
				worker_code, recorded_by, quantity, settlement_unit, rate_version, unit_rate, amount, status, settlement_date, occurred_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CANDIDATE', ?, ?)
			""",
			id,
			command.operationId(),
			identifier("PW"),
			task.id(),
			task.taskNo(),
			task.operationCode(),
			task.operationName(),
			worker,
			recordedBy,
			command.quantity(),
			rate.settlementUnit(),
			rate.version(),
			rate.unitRate(),
			amount,
			settlementDate,
			Timestamp.from(now)
		);
		return new EntryResult(requireEntry(id), false);
	}

	private EntryResult duplicate(EntryView existing, EntryCommand command) {
		if (!existing.taskId().equals(command.taskId())) {
			throw DomainException.conflict("PIECEWORK_OPERATION_REUSED", "计件操作号已被其他任务使用");
		}
		if (existing.quantity().compareTo(command.quantity()) != 0
				|| !existing.workerCode().equals(normalize(command.workerCode()))
				|| !existing.recordedBy().equals(recordedBy(command))) {
			throw DomainException.conflict(
				"PIECEWORK_OPERATION_PAYLOAD_MISMATCH", "相同操作号的计件内容不一致");
		}
		return new EntryResult(existing, true);
	}

	private static String recordedBy(EntryCommand command) {
		return normalize(command.recordedBy() == null || command.recordedBy().isBlank() ? command.workerCode() : command.recordedBy());
	}

	@Transactional
	public EntryView confirm(UUID entryId, String supervisorCode) {
		lockEntry(entryId);
		EntryView entry = requireEntry(entryId);
		String supervisor = normalize(supervisorCode);
		if (entry.status().equals("CANDIDATE") && (entry.workerCode().equals(supervisor) || entry.recordedBy().equals(supervisor))) {
			throw DomainException.conflict("PIECEWORK_SELF_APPROVAL", "计件人员不能确认自己的计件记录");
		}
		PlanningApplication.TaskView task = planning.getTask(entry.taskId());
		if (!hasPayrollGlobalRole(supervisor) && !lineScopes.canDispatch(supervisor, task.routeType(), task.operationCode())) {
			throw DomainException.forbidden("PIECEWORK_CONFIRMATION_SCOPE_DENIED", "Current user cannot confirm piecework for this operation");
		}
		if (entry.status().equals("CONFIRMED")) {
			return entry;
		}
		if (!entry.status().equals("CANDIDATE")) {
			throw DomainException.conflict("PIECEWORK_STATUS_INVALID", "只有候选计件可以确认");
		}
		jdbc.update("""
			update piecework_entry set status = 'CONFIRMED', confirmed_by = ?, confirmed_at = ?
			where id = ? and status = 'CANDIDATE'
			""", supervisor, Timestamp.from(Instant.now()), entryId);
		return requireEntry(entryId);
	}

	private void lockEntry(UUID entryId) {
		List<UUID> rows = jdbc.query(
			"select id from piecework_entry where id = ? for update",
			(rs, rowNum) -> rs.getObject("id", UUID.class),
			entryId
		);
		if (rows.isEmpty()) {
			throw DomainException.notFound("PIECEWORK_ENTRY_NOT_FOUND", "计件记录不存在");
		}
	}

	@Transactional(readOnly = true)
	public List<RateView> listRates() {
		return jdbc.query("""
			select id, operation_code, operation_name, route_type, product_code, product_name, version, settlement_unit, unit_rate, effective_from, active, created_at
			from piecework_rate order by route_type, product_code nulls first, operation_code, effective_from desc
			""", PieceworkApplication::mapRate).stream()
			.filter(rate -> !access.secured() || access.role("FINANCE_REVIEWER") || access.canManageOperation(rate.routeType().name(), rate.operationCode())).toList();
	}

	@Transactional(readOnly = true)
	public List<EntryView> listEntries(String viewerCode) {
		viewerCode = access.viewer(viewerCode);
		List<EntryView> entries = jdbc.query("""
			select id, operation_id, entry_no, task_id, task_no, operation_code, operation_name,
				worker_code, recorded_by, quantity, settlement_unit, rate_version, unit_rate, amount, status,
				confirmed_by, settlement_date, occurred_at, confirmed_at
			from piecework_entry order by occurred_at desc
			""", PieceworkApplication::mapEntry);
		if (viewerCode == null || viewerCode.isBlank()) {
			return entries;
		}
		String viewer = normalize(viewerCode);
		if (!hasPayrollExportAccess(viewer)) {
			throw DomainException.forbidden("PIECEWORK_LEDGER_SCOPE_DENIED", "Current user cannot view piecework ledger");
		}
		return entries.stream().filter(entry -> canExport(viewer, entry)).toList();
	}

	@Transactional
	public PayrollExport exportConfirmedPayroll(String viewerCode, String monthText) {
		String viewer = normalize(viewerCode);
		if (!hasPayrollExportAccess(viewer)) {
			throw DomainException.forbidden("PIECEWORK_EXPORT_SCOPE_DENIED", "Current user cannot export piecework payroll");
		}
		YearMonth month = parseMonth(monthText);
		LocalDate firstDay = month.atDay(1);
		LocalDate nextMonth = month.plusMonths(1).atDay(1);
		List<PayrollLine> entries = confirmedPayrollLines(firstDay, nextMonth).stream()
			.filter(entry -> canExport(viewer, entry.entry()))
			.toList();
		byte[] content = payrollCsv(month, entries);
		audit.record(
			"PIECEWORK_PAYROLL_EXPORT",
			"PIECEWORK_PAYROLL",
			UUID.nameUUIDFromBytes(("PIECEWORK_PAYROLL:" + month).getBytes(StandardCharsets.UTF_8)),
			UUID.randomUUID(),
			viewer,
			null,
			null,
			"month=" + month + "; entries=" + entries.size()
		);
		return new PayrollExport(month, content, entries.size());
	}

	@Transactional
	public PayrollExport exportWorkerPayroll(String viewerCode, String workerCode, String monthText) {
		String viewer = normalize(viewerCode);
		if (!hasPayrollExportAccess(viewer)) {
			throw DomainException.forbidden("PIECEWORK_EXPORT_SCOPE_DENIED", "Current user cannot export piecework payroll");
		}
		String worker = normalize(workerCode);
		YearMonth month = parseMonth(monthText);
		List<PayrollLine> entries = confirmedPayrollLines(month.atDay(1), month.plusMonths(1).atDay(1)).stream()
			.filter(entry -> entry.entry().workerCode().equals(worker))
			.filter(entry -> canExport(viewer, entry.entry()))
			.toList();
		byte[] content = workerPayrollCsv(month, worker, entries);
		audit.record("PIECEWORK_WORKER_PAYSLIP_EXPORT", "PIECEWORK_PAYROLL", UUID.nameUUIDFromBytes(("PIECEWORK_PAYSLIP:" + month + ":" + worker).getBytes(StandardCharsets.UTF_8)), UUID.randomUUID(), viewer, null, null, "month=" + month + "; worker=" + worker + "; entries=" + entries.size());
		return new PayrollExport(month, content, entries.size());
	}

	@Transactional(readOnly = true)
	public List<WorkerPayrollDetailView> listWorkerPayroll(String viewerCode, String workerCode, String monthText) {
		String viewer = normalize(viewerCode);
		if (!hasPayrollExportAccess(viewer)) {
			throw DomainException.forbidden("PIECEWORK_LEDGER_SCOPE_DENIED", "Current user cannot view piecework payroll");
		}
		String worker = normalize(workerCode);
		YearMonth month = parseMonth(monthText);
		return confirmedPayrollLines(month.atDay(1), month.plusMonths(1).atDay(1)).stream()
			.filter(entry -> entry.entry().workerCode().equals(worker))
			.filter(entry -> canExport(viewer, entry.entry()))
			.map(PieceworkApplication::workerPayrollDetail)
			.toList();
	}

	@Transactional(readOnly = true)
	public OutputExport exportWorkerOutput(String viewerCode, String workerCode, String monthText, RouteType routeType) {
		String viewer = normalize(viewerCode);
		if (!hasPayrollExportAccess(viewer)) {
			throw DomainException.forbidden("WORKER_OUTPUT_EXPORT_SCOPE_DENIED", "Current user cannot export worker output");
		}
		String worker = normalize(workerCode);
		YearMonth month = parseMonth(monthText);
		List<WorkerOutputLine> rows = workerOutputLines(viewer, worker, month, routeType);
		byte[] content = workerOutputCsv(month, worker, rows);
		audit.record("WORKER_OUTPUT_EXPORT", "WORKER_OUTPUT", UUID.nameUUIDFromBytes(("WORKER_OUTPUT:" + month + ":" + worker + ":" + (routeType == null ? "ALL" : routeType.name())).getBytes(StandardCharsets.UTF_8)), UUID.randomUUID(), viewer, null, null, "month=" + month + "; worker=" + worker + "; route=" + (routeType == null ? "ALL" : routeType.name()) + "; rows=" + rows.size());
		return new OutputExport(month, content, rows.size());
	}

	@Transactional(readOnly = true)
	public List<WorkerOutputDetailView> listWorkerOutput(String viewerCode, String workerCode, String monthText, RouteType routeType) {
		String viewer = normalize(viewerCode);
		if (!hasPayrollExportAccess(viewer)) {
			throw DomainException.forbidden("WORKER_OUTPUT_SCOPE_DENIED", "Current user cannot view worker output");
		}
		String worker = normalize(workerCode);
		YearMonth month = parseMonth(monthText);
		return workerOutputLines(viewer, worker, month, routeType).stream()
			.map(PieceworkApplication::workerOutputDetail)
			.toList();
	}

	private List<WorkerOutputLine> workerOutputLines(String viewer, String worker, YearMonth month, RouteType routeType) {
		Instant from = month.atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
		Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
		String routeFilter = routeType == null ? "" : " and work_order.route_type = ?";
		Object[] reportParameters = routeType == null
			? new Object[] { worker, Timestamp.from(from), Timestamp.from(to) }
			: new Object[] { worker, Timestamp.from(from), Timestamp.from(to), routeType.name() };
		List<WorkerOutputLine> electronicRows = jdbc.query("""
			select report.id, report.operator_code, coalesce(member.name, report.operator_code) as worker_name,
				header.order_no, header.customer_name, work_order.work_order_no, batch.batch_no,
				work_order.product_code, work_order.product_name, coalesce(work_order.product_material, '') as product_material,
				work_order.route_type, task.task_no, task.operation_code, task.operation_name,
				report.good_quantity, report.scrap_quantity, report.occurred_at, 'ELECTRONIC_REPORT' as source
			from execution_report report
			join planning_task task on task.id = report.task_id
			join planning_batch batch on batch.id = task.batch_id
			join planning_work_order work_order on work_order.id = batch.work_order_id
			join customer_order_header header on header.id = work_order.order_id
			left join organization_member member on member.employee_code = report.operator_code
			where report.operator_code = ? and report.occurred_at >= ? and report.occurred_at < ?
			""" + routeFilter, PieceworkApplication::mapWorkerOutputLine, reportParameters);
		Object[] settlementParameters = routeType == null
			? new Object[] { worker, month.atDay(1), month.plusMonths(1).atDay(1), worker, Timestamp.from(from), Timestamp.from(to) }
			: new Object[] { worker, month.atDay(1), month.plusMonths(1).atDay(1), worker, Timestamp.from(from), Timestamp.from(to), routeType.name() };
		List<WorkerOutputLine> settlementRows = jdbc.query("""
			select entry.id, entry.worker_code as operator_code, coalesce(member.name, entry.worker_code) as worker_name,
				header.order_no, header.customer_name, work_order.work_order_no, batch.batch_no,
				work_order.product_code, work_order.product_name, coalesce(work_order.product_material, '') as product_material,
				work_order.route_type, task.task_no, task.operation_code, task.operation_name,
				entry.quantity as good_quantity, cast(0 as decimal(20, 4)) as scrap_quantity, entry.occurred_at, 'PIECEWORK_SETTLEMENT' as source
			from piecework_entry entry
			join planning_task task on task.id = entry.task_id
			join planning_batch batch on batch.id = task.batch_id
			join planning_work_order work_order on work_order.id = batch.work_order_id
			join customer_order_header header on header.id = work_order.order_id
			left join organization_member member on member.employee_code = entry.worker_code
			where entry.worker_code = ? and entry.status = 'CONFIRMED' and entry.settlement_date >= ? and entry.settlement_date < ?
			and not exists (
				select 1 from execution_report report where report.task_id = entry.task_id and report.operator_code = ?
				and report.occurred_at >= ? and report.occurred_at < ?
			)
			""" + routeFilter, PieceworkApplication::mapWorkerOutputLine, settlementParameters);
		return java.util.stream.Stream.concat(electronicRows.stream(), settlementRows.stream())
			.filter(row -> hasPayrollGlobalRole(viewer) || lineScopes.canDispatch(viewer, row.routeType(), row.operationCode()))
			.sorted(Comparator.comparing(WorkerOutputLine::occurredAt).thenComparing(WorkerOutputLine::taskNo))
			.toList();
	}

	private List<PayrollLine> confirmedPayrollLines(LocalDate firstDay, LocalDate nextMonth) {
		return jdbc.query("""
			select e.id, e.operation_id, e.entry_no, e.task_id, e.task_no, e.operation_code, e.operation_name,
				e.worker_code, e.recorded_by, e.quantity, e.settlement_unit, e.rate_version, e.unit_rate, e.amount, e.status,
				e.confirmed_by, e.settlement_date, e.occurred_at, e.confirmed_at,
				coalesce(member.name, e.worker_code) as worker_name,
				header.order_no, header.customer_name, work_order.work_order_no, batch.batch_no,
				work_order.product_code, work_order.product_name, coalesce(work_order.product_material, '') as product_material,
				work_order.route_type
			from piecework_entry e
			join planning_task task on task.id = e.task_id
			join planning_batch batch on batch.id = task.batch_id
			join planning_work_order work_order on work_order.id = batch.work_order_id
			join customer_order_header header on header.id = work_order.order_id
			left join organization_member member on member.employee_code = e.worker_code
			where e.status = 'CONFIRMED' and e.settlement_date >= ? and e.settlement_date < ?
			order by e.worker_code, e.settlement_date, e.occurred_at, e.entry_no
			""", PieceworkApplication::mapPayrollLine, firstDay, nextMonth);
	}

	private boolean canExport(String viewerCode, EntryView entry) {
		if (hasPayrollGlobalRole(viewerCode)) {
			return true;
		}
		PlanningApplication.TaskView task = planning.getTask(entry.taskId());
		return lineScopes.canDispatch(viewerCode, task.routeType(), task.operationCode());
	}

	private boolean hasPayrollExportAccess(String viewerCode) {
		return hasPayrollGlobalRole(viewerCode) || !lineScopes.visibleRoutes(viewerCode).isEmpty();
	}

	private boolean hasPayrollGlobalRole(String viewerCode) {
		if (access.secured()) return viewerCode.equals(access.actor()) && access.role("SYSTEM_ADMIN", "GENERAL_MANAGER", "PRODUCTION_MANAGER", "FINANCE_REVIEWER");
		Integer count = jdbc.queryForObject("""
			select count(*) from organization_member_role
			where employee_code = ? and role_code in ('SYSTEM_ADMIN', 'GENERAL_MANAGER', 'PRODUCTION_MANAGER', 'FINANCE_REVIEWER')
			""", Integer.class, viewerCode);
		return count != null && count > 0;
	}

	private static YearMonth parseMonth(String monthText) {
		try {
			return YearMonth.parse(monthText);
		} catch (DateTimeParseException | NullPointerException exception) {
			throw DomainException.badRequest("PIECEWORK_EXPORT_MONTH_INVALID", "Month must use yyyy-MM");
		}
	}

	private static byte[] payrollCsv(YearMonth month, List<PayrollLine> entries) {
		StringBuilder csv = new StringBuilder("\ufeff");
		csv.append(csvRow(List.of("结算月份", "工资归属工号", "员工姓名", "订单号", "客户", "工单号", "生产批次", "产品编码", "产品名称", "产品材质", "产线", "任务号", "工序", "计件单号", "结算单位", "数量", "单位工价", "金额", "工价版本", "代录人", "确认人", "确认时间", "结算归属日")));
		for (PayrollLine line : entries) {
			EntryView entry = line.entry();
			csv.append(csvRow(List.of(
				month.toString(), entry.workerCode(), line.workerName(), line.orderNo(), line.customerName(), line.workOrderNo(), line.batchNo(),
				line.productCode(), line.productName(), line.productMaterial(), line.routeType().name(), entry.taskNo(), entry.operationName(), entry.entryNo(), entry.settlementUnit(),
				entry.quantity().toPlainString(), entry.unitRate().toPlainString(), entry.amount().toPlainString(), entry.rateVersion(),
				entry.recordedBy(), entry.confirmedBy(), entry.confirmedAt() == null ? "" : entry.confirmedAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime().toString(),
				entry.settlementDate().toString()
			)));
		}
		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] workerPayrollCsv(YearMonth month, String workerCode, List<PayrollLine> entries) {
		String workerName = entries.isEmpty() ? workerCode : entries.getFirst().workerName();
		BigDecimal total = entries.stream().map(line -> line.entry().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
		StringBuilder csv = new StringBuilder("\ufeff");
		csv.append(csvRow(List.of("个人计件工资条", "结算月份", month.toString(), "员工工号", workerCode, "员工姓名", workerName, "已确认金额", total.toPlainString())));
		csv.append(csvRow(List.of("订单号", "客户", "产品编码", "产品名称", "产品材质", "生产批次", "任务号", "工序", "结算单位", "数量", "单位工价", "金额", "工价版本", "代录人", "确认人", "结算归属日")));
		for (PayrollLine line : entries) {
			EntryView entry = line.entry();
			csv.append(csvRow(List.of(line.orderNo(), line.customerName(), line.productCode(), line.productName(), line.productMaterial(), line.batchNo(), entry.taskNo(), entry.operationName(), entry.settlementUnit(), entry.quantity().toPlainString(), entry.unitRate().toPlainString(), entry.amount().toPlainString(), entry.rateVersion(), entry.recordedBy(), entry.confirmedBy(), entry.settlementDate().toString())));
		}
		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] workerOutputCsv(YearMonth month, String workerCode, List<WorkerOutputLine> rows) {
		String workerName = rows.isEmpty() ? workerCode : rows.getFirst().workerName();
		StringBuilder csv = new StringBuilder("\ufeff");
		csv.append(csvRow(List.of("员工月度产出明细", "统计月份", month.toString(), "员工工号", workerCode, "员工姓名", workerName)));
		csv.append(csvRow(List.of("来源", "产线", "订单号", "客户", "工单号", "生产批次", "产品编码", "产品名称", "产品材质", "任务号", "工序", "本次合格数", "本次报废数", "报工时间")));
		for (WorkerOutputLine row : rows) {
			csv.append(csvRow(List.of(row.source(), row.routeType().name(), row.orderNo(), row.customerName(), row.workOrderNo(), row.batchNo(), row.productCode(), row.productName(), row.productMaterial(), row.taskNo(), row.operationName(), row.goodQuantity().toPlainString(), row.scrapQuantity().toPlainString(), row.occurredAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime().toString())));
		}
		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String csvRow(List<String> values) {
		return values.stream().map(PieceworkApplication::csvValue).collect(java.util.stream.Collectors.joining(",", "", "\r\n"));
	}

	private static String csvValue(String value) {
		String text = value == null ? "" : value;
		if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
			text = "'" + text;
		}
		return '"' + text.replace("\"", "\"\"") + '"';
	}

	private RateView activeRate(String operationCode, RouteType routeType, String productCode, LocalDate settlementDate) {
		List<RateView> rows = jdbc.query("""
			select id, operation_code, operation_name, route_type, product_code, product_name, version, settlement_unit, unit_rate, effective_from, active, created_at
			from piecework_rate
			where operation_code = ? and route_type = ? and (product_code = ? or product_code is null)
				and active = true and effective_from <= ?
			order by case when product_code = ? then 0 else 1 end, effective_from desc, created_at desc
			""", PieceworkApplication::mapRate, operationCode, routeType.name(), productCode, settlementDate, productCode);
		if (rows.isEmpty()) {
			throw DomainException.conflict("PIECEWORK_RATE_NOT_FOUND", "当前工序没有生效的计件单价");
		}
		return rows.getFirst();
	}

	private RateView requireRate(UUID id) {
		return jdbc.query("""
			select id, operation_code, operation_name, route_type, product_code, product_name, version, settlement_unit, unit_rate, effective_from, active, created_at
			from piecework_rate where id = ?
			""", PieceworkApplication::mapRate, id).getFirst();
	}

	private EntryView findEntry(UUID operationId) {
		List<EntryView> rows = jdbc.query("""
			select id, operation_id, entry_no, task_id, task_no, operation_code, operation_name,
				worker_code, recorded_by, quantity, settlement_unit, rate_version, unit_rate, amount, status,
				confirmed_by, settlement_date, occurred_at, confirmed_at
			from piecework_entry where operation_id = ?
			""", PieceworkApplication::mapEntry, operationId);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private EntryView requireEntry(UUID id) {
		List<EntryView> rows = jdbc.query("""
			select id, operation_id, entry_no, task_id, task_no, operation_code, operation_name,
				worker_code, recorded_by, quantity, settlement_unit, rate_version, unit_rate, amount, status,
				confirmed_by, settlement_date, occurred_at, confirmed_at
			from piecework_entry where id = ?
			""", PieceworkApplication::mapEntry, id);
		if (rows.isEmpty()) {
			throw DomainException.notFound("PIECEWORK_ENTRY_NOT_FOUND", "计件记录不存在");
		}
		return rows.getFirst();
	}

	private static RateView mapRate(ResultSet rs, int rowNum) throws SQLException {
		return new RateView(
			rs.getObject("id", UUID.class),
			rs.getString("operation_code"),
			rs.getString("operation_name"),
			RouteType.valueOf(rs.getString("route_type")),
			rs.getString("product_code"),
			rs.getString("product_name"),
			rs.getString("version"),
			rs.getString("settlement_unit"),
			rs.getBigDecimal("unit_rate"),
			rs.getDate("effective_from").toLocalDate(),
			rs.getBoolean("active"),
			rs.getTimestamp("created_at").toInstant()
		);
	}

	private static EntryView mapEntry(ResultSet rs, int rowNum) throws SQLException {
		Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
		return new EntryView(
			rs.getObject("id", UUID.class),
			rs.getObject("operation_id", UUID.class),
			rs.getString("entry_no"),
			rs.getObject("task_id", UUID.class),
			rs.getString("task_no"),
			rs.getString("operation_code"),
			rs.getString("operation_name"),
			rs.getString("worker_code"),
			rs.getString("recorded_by"),
			rs.getBigDecimal("quantity"),
			rs.getString("settlement_unit"),
			rs.getString("rate_version"),
			rs.getBigDecimal("unit_rate"),
			rs.getBigDecimal("amount"),
			rs.getString("status"),
			rs.getString("confirmed_by"),
			rs.getDate("settlement_date").toLocalDate(),
			rs.getTimestamp("occurred_at").toInstant(),
			confirmedAt == null ? null : confirmedAt.toInstant()
		);
	}

	private static PayrollLine mapPayrollLine(ResultSet rs, int rowNum) throws SQLException {
		return new PayrollLine(
			mapEntry(rs, rowNum),
			rs.getString("worker_name"),
			rs.getString("order_no"),
			rs.getString("customer_name"),
			rs.getString("work_order_no"),
			rs.getString("batch_no"),
			rs.getString("product_code"),
			rs.getString("product_name"),
			rs.getString("product_material"),
			RouteType.valueOf(rs.getString("route_type"))
		);
	}

	private static WorkerOutputLine mapWorkerOutputLine(ResultSet rs, int rowNum) throws SQLException {
		return new WorkerOutputLine(
			rs.getString("operator_code"), rs.getString("worker_name"), rs.getString("order_no"), rs.getString("customer_name"),
			rs.getString("work_order_no"), rs.getString("batch_no"), rs.getString("product_code"), rs.getString("product_name"),
			rs.getString("product_material"), RouteType.valueOf(rs.getString("route_type")), rs.getString("task_no"),
			rs.getString("operation_code"), rs.getString("operation_name"), rs.getBigDecimal("good_quantity"),
			rs.getBigDecimal("scrap_quantity"), rs.getTimestamp("occurred_at").toInstant(), rs.getString("source")
		);
	}

	private static WorkerPayrollDetailView workerPayrollDetail(PayrollLine line) {
		EntryView entry = line.entry();
		return new WorkerPayrollDetailView(entry.workerCode(), line.workerName(), line.orderNo(), line.customerName(), line.workOrderNo(), line.batchNo(), line.productCode(), line.productName(), line.productMaterial(), line.routeType(), entry.taskNo(), entry.operationName(), entry.settlementUnit(), entry.quantity(), entry.unitRate(), entry.amount(), entry.rateVersion(), entry.recordedBy(), entry.confirmedBy(), entry.settlementDate());
	}

	private static WorkerOutputDetailView workerOutputDetail(WorkerOutputLine line) {
		return new WorkerOutputDetailView(line.workerCode(), line.workerName(), line.orderNo(), line.customerName(), line.workOrderNo(), line.batchNo(), line.productCode(), line.productName(), line.productMaterial(), line.routeType(), line.taskNo(), line.operationName(), line.goodQuantity(), line.scrapQuantity(), line.occurredAt(), line.source());
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String normalizeOptional(String value) {
		return value == null || value.isBlank() ? null : normalize(value);
	}

	private static String normalizeSettlementUnit(String value) {
		String unit = value == null || value.isBlank() ? "PCS" : value.trim().toUpperCase(Locale.ROOT);
		if (!unit.equals("PCS") && !unit.equals("TREE") && !unit.equals("KG")) {
			throw DomainException.badRequest("PIECEWORK_SETTLEMENT_UNIT_INVALID", "Settlement unit must be PCS, TREE or KG");
		}
		return unit;
	}

	private static boolean isLowWaxProgressOnly(RouteType routeType, String operationCode) {
		return routeType == RouteType.LOW_TEMP_WAX
			&& List.of("WAX_INJECTION", "WAX_REPAIR", "TREE_ASSEMBLY").contains(operationCode);
	}

	private static BigDecimal settlementLimit(PlanningApplication.TaskView task, RateView rate) {
		if ("KG".equals(rate.settlementUnit())) {
			if (!"PIECE_KG".equals(task.compensationMode()) || task.completedWeightKg() == null) {
				throw DomainException.conflict("PIECEWORK_WEIGHT_UNAVAILABLE", "Kilogram piecework requires a completed weight record");
			}
			return task.completedWeightKg();
		}
		if (!"TREE".equals(rate.settlementUnit())) {
			return task.goodQuantity();
		}
		if (!"TREE_COUNT".equals(task.reportingMode()) || task.treeCount() == null) {
			throw DomainException.conflict("PIECEWORK_TREE_COUNT_UNAVAILABLE", "Tree piecework requires a completed tree report");
		}
		return task.treeCount();
	}

	private static void assertRateOperationEligible(String operationCode, String settlementUnit) {
		String expectedUnit = switch (operationCode) {
			case "WAX_INJECTION", "WAX_REPAIR" -> "PCS";
			case "TREE_ASSEMBLY" -> "TREE";
			case "OPTIONAL_FINISHING" -> "KG";
			default -> null;
		};
		if (expectedUnit == null) {
			throw DomainException.conflict("PIECEWORK_OPERATION_NOT_ELIGIBLE", "This operation is not eligible for piecework");
		}
		if (!expectedUnit.equals(settlementUnit)) {
			throw DomainException.conflict("PIECEWORK_SETTLEMENT_UNIT_MISMATCH", "Settlement unit does not match the operation rule");
		}
	}

	private static void assertCompensationCompatible(PlanningApplication.TaskView task, RateView rate) {
		String expectedUnit = switch (task.compensationMode()) {
			case "PIECE_PCS" -> "PCS";
			case "PIECE_TREE" -> "TREE";
			case "PIECE_KG" -> "KG";
			default -> null;
		};
		if (expectedUnit == null || !expectedUnit.equals(rate.settlementUnit())) {
			throw DomainException.conflict("PIECEWORK_COMPENSATION_MODE_INVALID", "Task compensation mode is not compatible with piecework");
		}
	}

	private static LocalDate settlementDate(PlanningApplication.TaskView task) {
		return task.completedAt() == null
			? LocalDate.now()
			: task.completedAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
	}

	private static String identifier(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

	public record RateCommand(
		String supervisorCode,
		String operationCode,
		String operationName,
		RouteType routeType,
		String version,
		String settlementUnit,
		BigDecimal unitRate,
		LocalDate effectiveFrom,
		String productCode,
		String productName
	) {
	}

	public record EntryCommand(UUID operationId, UUID taskId, String workerCode, String recordedBy, BigDecimal quantity) {
	}

	public record EntryResult(EntryView entry, boolean duplicate) {
	}

	public record PayrollExport(YearMonth month, byte[] content, int entryCount) {
	}

	public record OutputExport(YearMonth month, byte[] content, int rowCount) {
	}

	public record WorkerPayrollDetailView(
		String workerCode, String workerName, String orderNo, String customerName, String workOrderNo, String batchNo,
		String productCode, String productName, String productMaterial, RouteType routeType, String taskNo,
		String operationName, String settlementUnit, BigDecimal quantity, BigDecimal unitRate, BigDecimal amount,
		String rateVersion, String recordedBy, String confirmedBy, LocalDate settlementDate
	) {
	}

	public record WorkerOutputDetailView(
		String workerCode, String workerName, String orderNo, String customerName, String workOrderNo, String batchNo,
		String productCode, String productName, String productMaterial, RouteType routeType, String taskNo,
		String operationName, BigDecimal goodQuantity, BigDecimal scrapQuantity, Instant occurredAt, String source
	) {
	}

	private record PayrollLine(
		EntryView entry,
		String workerName,
		String orderNo,
		String customerName,
		String workOrderNo,
		String batchNo,
		String productCode,
		String productName,
		String productMaterial,
		RouteType routeType
	) {
	}

	private record WorkerOutputLine(
		String workerCode,
		String workerName,
		String orderNo,
		String customerName,
		String workOrderNo,
		String batchNo,
		String productCode,
		String productName,
		String productMaterial,
		RouteType routeType,
		String taskNo,
		String operationCode,
		String operationName,
		BigDecimal goodQuantity,
		BigDecimal scrapQuantity,
		Instant occurredAt,
		String source
	) {
	}

	public record RateView(
		UUID id,
		String operationCode,
		String operationName,
		RouteType routeType,
		String productCode,
		String productName,
		String version,
		String settlementUnit,
		BigDecimal unitRate,
		LocalDate effectiveFrom,
		boolean active,
		Instant createdAt
	) {
	}

	public record EntryView(
		UUID id,
		UUID operationId,
		String entryNo,
		UUID taskId,
		String taskNo,
		String operationCode,
		String operationName,
		String workerCode,
		String recordedBy,
		BigDecimal quantity,
		String settlementUnit,
		String rateVersion,
		BigDecimal unitRate,
		BigDecimal amount,
		String status,
		String confirmedBy,
		LocalDate settlementDate,
		Instant occurredAt,
		Instant confirmedAt
	) {
	}
}

@RestController
@RequestMapping("/api/piecework")
class PieceworkController {

	private final PieceworkApplication piecework;

	PieceworkController(PieceworkApplication piecework) {
		this.piecework = piecework;
	}

	@GetMapping("/rates")
	List<PieceworkApplication.RateView> rates() {
		return piecework.listRates();
	}

	@PostMapping("/rates")
	@ResponseStatus(HttpStatus.CREATED)
	PieceworkApplication.RateView createRate(@Valid @RequestBody RateRequest request) {
		return piecework.createRate(new PieceworkApplication.RateCommand(
			request.supervisorCode(),
			request.operationCode(),
			request.operationName(),
			request.routeType(),
			request.version(),
			request.settlementUnit(),
			request.unitRate(),
			request.effectiveFrom(),
			request.productCode(),
			request.productName()
		));
	}

	@GetMapping("/entries")
	List<PieceworkApplication.EntryView> entries(@org.springframework.web.bind.annotation.RequestParam(required = false) String viewerCode) {
		return piecework.listEntries(viewerCode);
	}

	@GetMapping(value = "/exports/payroll", produces = "text/csv")
	ResponseEntity<byte[]> exportPayroll(@org.springframework.web.bind.annotation.RequestParam String viewerCode,
			@org.springframework.web.bind.annotation.RequestParam String month) {
		PieceworkApplication.PayrollExport payroll = piecework.exportConfirmedPayroll(viewerCode, month);
		return ResponseEntity.ok()
			.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
				.filename("piecework-payroll-" + payroll.month() + ".csv", StandardCharsets.UTF_8).build().toString())
			.body(payroll.content());
	}

	@GetMapping(value = "/exports/worker-payroll", produces = "text/csv")
	ResponseEntity<byte[]> exportWorkerPayroll(@org.springframework.web.bind.annotation.RequestParam String viewerCode,
			@org.springframework.web.bind.annotation.RequestParam String workerCode,
			@org.springframework.web.bind.annotation.RequestParam String month) {
		PieceworkApplication.PayrollExport payroll = piecework.exportWorkerPayroll(viewerCode, workerCode, month);
		return ResponseEntity.ok()
			.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
				.filename("piecework-payslip-" + workerCode + "-" + payroll.month() + ".csv", StandardCharsets.UTF_8).build().toString())
			.body(payroll.content());
	}

	@GetMapping(value = "/exports/worker-output", produces = "text/csv")
	ResponseEntity<byte[]> exportWorkerOutput(@org.springframework.web.bind.annotation.RequestParam String viewerCode,
			@org.springframework.web.bind.annotation.RequestParam String workerCode,
			@org.springframework.web.bind.annotation.RequestParam String month,
			@org.springframework.web.bind.annotation.RequestParam(required = false) RouteType routeType) {
		PieceworkApplication.OutputExport output = piecework.exportWorkerOutput(viewerCode, workerCode, month, routeType);
		return ResponseEntity.ok()
			.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
				.filename("worker-output-" + workerCode + "-" + output.month() + ".csv", StandardCharsets.UTF_8).build().toString())
			.body(output.content());
	}

	@GetMapping("/worker-payroll")
	List<PieceworkApplication.WorkerPayrollDetailView> workerPayroll(@org.springframework.web.bind.annotation.RequestParam String viewerCode,
			@org.springframework.web.bind.annotation.RequestParam String workerCode,
			@org.springframework.web.bind.annotation.RequestParam String month) {
		return piecework.listWorkerPayroll(viewerCode, workerCode, month);
	}

	@GetMapping("/worker-output")
	List<PieceworkApplication.WorkerOutputDetailView> workerOutput(@org.springframework.web.bind.annotation.RequestParam String viewerCode,
			@org.springframework.web.bind.annotation.RequestParam String workerCode,
			@org.springframework.web.bind.annotation.RequestParam String month,
			@org.springframework.web.bind.annotation.RequestParam(required = false) RouteType routeType) {
		return piecework.listWorkerOutput(viewerCode, workerCode, month, routeType);
	}

	@PostMapping("/entries")
	ResponseEntity<PieceworkApplication.EntryResult> createEntry(@Valid @RequestBody EntryRequest request) {
		PieceworkApplication.EntryResult result = piecework.createEntry(new PieceworkApplication.EntryCommand(
			request.operationId(),
			request.taskId(),
			request.workerCode(),
			request.recordedBy(),
			request.quantity()
		));
		return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
	}

	@PostMapping("/entries/{entryId}/confirmation")
	PieceworkApplication.EntryView confirm(
		@PathVariable UUID entryId,
		@Valid @RequestBody ConfirmationRequest request
	) {
		return piecework.confirm(entryId, request.supervisorCode());
	}

	record RateRequest(
		@NotBlank @Size(max = 64) String supervisorCode,
		@NotBlank @Size(max = 64) String operationCode,
		@NotBlank @Size(max = 120) String operationName,
		@NotNull RouteType routeType,
		@NotBlank @Size(max = 32) String version,
		@Size(max = 16) String settlementUnit,
		@NotNull @DecimalMin("0.0") BigDecimal unitRate,
		@NotNull LocalDate effectiveFrom,
		@Size(max = 64) String productCode,
		@Size(max = 160) String productName
	) {
	}

	record EntryRequest(
		@NotNull UUID operationId,
		@NotNull UUID taskId,
		@NotBlank @Size(max = 64) String workerCode,
		@Size(max = 64) String recordedBy,
		@NotNull @DecimalMin("0.001") BigDecimal quantity
	) {
	}

	record ConfirmationRequest(@NotBlank @Size(max = 64) String supervisorCode) {
	}
}
