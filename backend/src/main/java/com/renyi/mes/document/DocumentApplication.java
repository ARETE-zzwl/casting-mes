package com.renyi.mes.document;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.BusinessAccess;
import com.renyi.mes.customerorder.CustomerOrderApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Service
public class DocumentApplication {

	private static final Map<DocumentType, String> REQUIRED_PERMISSIONS = Map.of(
		DocumentType.PROCESS_CARD, "PROCESS_CARD_VIEW",
		DocumentType.WORKSHOP_JOB_SHEET, "PRINT_WORKSHOP_DOCUMENT",
		DocumentType.FLOW_CARD, "PRINT_WORKSHOP_DOCUMENT",
		DocumentType.ORDER_DETAIL, "PRINT_SENSITIVE_ORDER",
		DocumentType.STATISTICS, "PRINT_STATISTICS"
	);
	private static final Map<String, Set<String>> OPERATION_ROLES = Map.ofEntries(
		Map.entry("MOLD_ISSUE", Set.of("MOLD_KEEPER")),
		Map.entry("WAX_INJECTION", Set.of("WAX_INJECTION_OPERATOR")),
		Map.entry("WAX_REPAIR", Set.of("WAX_REPAIR_OPERATOR")),
		Map.entry("TREE_ASSEMBLY", Set.of("TREE_ASSEMBLY_OPERATOR")),
		Map.entry("SHELL_BUILDING", Set.of("SHELL_BUILDING_OPERATOR")),
		Map.entry("MANUAL_SHELL_BUILDING", Set.of("SHELL_BUILDING_OPERATOR")),
		Map.entry("DEWAX", Set.of("DEWAX_OPERATOR")),
		Map.entry("POURING", Set.of("POURING_OPERATOR", "METALLURGY_LAB")),
		Map.entry("KNOCKOUT_CUTTING", Set.of("KNOCKOUT_OPERATOR")),
		Map.entry("KNOCKOUT", Set.of("KNOCKOUT_OPERATOR")),
		Map.entry("CUTTING", Set.of("CUTTING_OPERATOR")),
		Map.entry("OPTIONAL_FINISHING", Set.of("FINISHING_OPERATOR")),
		Map.entry("FINAL_COUNT", Set.of("FINISHED_GOODS_KEEPER"))
	);
	private static final Map<String, String> OPERATION_REQUIREMENTS = Map.ofEntries(
		Map.entry("MOLD_ISSUE", "模具资产编码、模具归属、领用数量和交接状态。"),
		Map.entry("WAX_INJECTION", "模具编号、蜡温、注蜡压力、保压时间和首件确认。"),
		Map.entry("WAX_REPAIR", "修蜡标准、外观缺陷、尺寸基准和待判品隔离要求。"),
		Map.entry("TREE_ASSEMBLY", "组树标准图、每树件数、浇口位置、树号和流转数量。"),
		Map.entry("SHELL_BUILDING", "制壳路线、层数、浆料和砂料批次、干燥时间及自动/手工方式。"),
		Map.entry("MANUAL_SHELL_BUILDING", "制壳路线、层数、浆料和砂料批次、干燥时间及自动/手工方式。"),
		Map.entry("DEWAX", "装炉数量、设备或炉号、脱蜡程序、压力温度曲线和出炉确认。"),
		Map.entry("POURING", "材质牌号、炉号、炉前光谱结果、型壳温度、浇筑温度窗口、树号和浇筑时间。"),
		Map.entry("KNOCKOUT_CUTTING", "脱壳方法、分割线和余量、批次标识、半成品数量及异常隔离。"),
		Map.entry("OPTIONAL_FINISHING", "后处理方法、质量要求、完工重量（公斤）和不合格品隔离。"),
		Map.entry("FINAL_COUNT", "成品批次、箱号、清点数量、合格标识和入库交接。")
	);

	private final JdbcTemplate jdbc;
	private final CustomerOrderApplication orders;
	private final ObjectMapper objectMapper;
	private final BusinessAccess access;

	public DocumentApplication(JdbcTemplate jdbc, CustomerOrderApplication orders, ObjectMapper objectMapper, BusinessAccess access) {
		this.jdbc = jdbc;
		this.orders = orders;
		this.objectMapper = objectMapper;
		this.access = access;
	}

	@Transactional(readOnly = true)
	public List<DocumentOption> options(String actorCode) {
		Set<String> permissions = permissionsFor(actorCode);
		return REQUIRED_PERMISSIONS.entrySet().stream()
			.filter(entry -> permissions.contains(entry.getValue()))
			.map(entry -> new DocumentOption(entry.getKey().name(), titleFor(entry.getKey()),
				entry.getKey() == DocumentType.ORDER_DETAIL || entry.getKey() == DocumentType.FLOW_CARD))
			.toList();
	}

	@Transactional
	public DocumentPreview preview(PreviewCommand command) {
		String actorCode = normalize(command.actorCode());
		Set<String> permissions = permissionsFor(actorCode);
		Set<String> roles = rolesFor(actorCode);
		String requiredPermission = REQUIRED_PERMISSIONS.get(command.documentType());
		if (!permissions.contains(requiredPermission)) {
			throw DomainException.forbidden("DOCUMENT_PERMISSION_DENIED", "当前角色无权生成该文档");
		}
		List<PrintableField> available = fieldsFor(command.documentType(), command.entityId(), actorCode, roles);
		Map<String, PrintableField> byCode = new LinkedHashMap<>();
		available.forEach(field -> byCode.put(field.code(), field));
		List<String> selectedCodes = command.selectedFields().stream().map(DocumentApplication::normalize)
			.distinct().toList();
		if (!byCode.keySet().containsAll(selectedCodes)) {
			throw DomainException.badRequest("DOCUMENT_FIELD_INVALID", "包含该文档不允许输出的字段");
		}
		List<PrintableField> selected = selectedCodes.stream().map(byCode::get).toList();
		boolean sensitiveIncluded = selected.stream().anyMatch(PrintableField::sensitive);
		Instant now = Instant.now();
		jdbc.update("""
			insert into document_print_audit (
				id, document_type, entity_id, actor_code, selected_fields, sensitive_included, output_type, occurred_at
			) values (?, ?, ?, ?, ?, ?, ?, ?)
			""", UUID.randomUUID(), command.documentType().name(), command.entityId(), actorCode,
			String.join(",", selectedCodes), sensitiveIncluded, command.outputType().name(), Timestamp.from(now));
		return new DocumentPreview(command.documentType().name(), titleFor(command.documentType()), command.entityId(),
			now, sensitiveIncluded, selected);
	}

	@Transactional(readOnly = true)
	public List<AuditView> audits(String actorCode) {
		if (!permissionsFor(actorCode).contains("PRINT_AUDIT_VIEW")) {
			throw DomainException.forbidden("DOCUMENT_AUDIT_DENIED", "当前角色无权查看打印导出审计");
		}
		return jdbc.query("""
			select id, document_type, entity_id, actor_code, selected_fields, sensitive_included, output_type, occurred_at
			from document_print_audit order by occurred_at desc
			""", (rs, rowNum) -> new AuditView(
			rs.getObject("id", UUID.class), rs.getString("document_type"), rs.getObject("entity_id", UUID.class),
			rs.getString("actor_code"), List.of(rs.getString("selected_fields").split(",")),
			rs.getBoolean("sensitive_included"), rs.getString("output_type"),
			rs.getTimestamp("occurred_at").toInstant()));
	}

	private List<PrintableField> fieldsFor(DocumentType type, UUID entityId, String actorCode, Set<String> roles) {
		return switch (type) {
			case PROCESS_CARD -> processCardFields(requireScopedTask(requireEntityId(entityId), actorCode, roles));
			case WORKSHOP_JOB_SHEET -> workshopJobSheetFields(requireScopedTask(requireEntityId(entityId), actorCode, roles));
			case FLOW_CARD -> flowCardFields(requireScopedTask(requireEntityId(entityId), actorCode, roles));
			case ORDER_DETAIL -> orderDetailFields(requireEntityId(entityId));
			case STATISTICS -> statisticsFields();
		};
	}

	private List<PrintableField> processCardFields(TaskDocument task) {
		List<PrintableField> fields = new ArrayList<>(taskFields(task));
		fields.add(field("PROCESS_CARD_VERSION", "工艺卡版本", task.processCardVersion(), false));
		fields.add(field("ENGINEERING_PARAMETERS", "本工序工程参数", operationParameters(task), false));
		List<String> processImageUrls = operationImageUrls(task);
		if (!processImageUrls.isEmpty()) fields.add(field("PROCESS_IMAGE_URLS", "本工序工艺图", String.join("\n", processImageUrls), false));
		PublishedSop sop = publishedSop(task.operationCode());
		fields.add(field("PROCESS_REQUIREMENTS", "本工序关键确认项",
			OPERATION_REQUIREMENTS.getOrDefault(task.operationCode(), task.operationName() + "作业前核对本任务工艺卡与现场要求。"), false));
		if (sop != null) {
			fields.add(field("PREPARATION", "工艺准备", sop.preparationNote(), false));
			if (!sop.keyParameters().isEmpty()) {
				fields.add(field("SOP_KEY_PARAMETERS", "本工序关键参数", sop.keyParameters().stream()
					.map(parameter -> parameter.name() + "：" + parameter.value())
					.reduce((left, right) -> left + "；" + right).orElse(""), false));
			}
			String steps = sop.steps().stream()
				.map(step -> step.stepNo() + ". " + step.title() + ": " + step.instruction())
				.reduce((left, right) -> left + "\n" + right).orElse("未配置步骤");
			fields.add(field("SOP", "SOP步骤", steps, false));
			fields.add(field("SAFETY", "安全提示", sop.safetyNotice(), false));
			fields.add(field("QUALITY_POINTS", "质量要点", String.join("；", sop.qualityPoints()), false));
		}
		else {
			fields.add(field("SOP", "SOP步骤", "该工序尚未发布SOP，请联系工艺工程师。", false));
			fields.add(field("SAFETY", "安全提示", "按车间通用安全规程作业，禁止跳过首件确认。", false));
			fields.add(field("QUALITY_POINTS", "质量要点", "按任务要求完成数量、状态和异常记录。", false));
		}
		return fields;
	}

	private PublishedSop publishedSop(String operationCode) {
		return jdbc.query("""
			select id, safety_notice, preparation_note from operation_sop
			where operation_code = ? and status = 'PUBLISHED'
			order by updated_at desc limit 1
			""", (rs, rowNum) -> new SopHeader(
				rs.getObject("id", UUID.class), rs.getString("safety_notice"), rs.getString("preparation_note")), operationCode)
			.stream().findFirst().map(header -> new PublishedSop(
				jdbc.query("""
					select step_no, title, instruction from operation_sop_step where sop_id = ? order by step_no
					""", (rs, rowNum) -> new SopStep(rs.getInt("step_no"), rs.getString("title"), rs.getString("instruction")), header.id()),
				header.safetyNotice(), header.preparationNote(),
				jdbc.query("""
					select content from operation_sop_quality_point where sop_id = ? order by point_no
					""", (rs, rowNum) -> rs.getString("content"), header.id()),
				jdbc.query("""
					select parameter_name, parameter_value from operation_sop_key_parameter
					where sop_id = ? order by parameter_no
					""", (rs, rowNum) -> new SopKeyParameter(
						rs.getString("parameter_name"), rs.getString("parameter_value")), header.id())
			)).orElse(null);
	}

	private List<PrintableField> workshopJobSheetFields(TaskDocument task) {
		List<PrintableField> fields = new ArrayList<>(taskFields(task));
		fields.add(field("PROCESS_CARD_VERSION", "工艺卡版本", task.processCardVersion(), false));
		fields.add(field("ENGINEERING_PARAMETERS", "本工序工程参数", operationParameters(task), false));
		fields.add(field("ASSIGNED_TO", "派发给", task.assignedTo() == null || task.assignedTo().isBlank() ? "未派发" : task.assignedTo(), false));
		fields.add(field("ORDER_REMARK", "订单备注", blankAsDash(task.orderRemark()), false));
		fields.add(field("TASK_QR_PAYLOAD", "任务二维码", "MES:TASK:" + task.taskId(), false));
		fields.add(field("QUANTITY_STATUS", "当前完成", task.goodQuantity().stripTrailingZeros().toPlainString()
			+ " 合格 / " + task.scrapQuantity().stripTrailingZeros().toPlainString() + " 报废", false));
		return fields;
	}

	private List<PrintableField> flowCardFields(TaskDocument task) {
		List<PrintableField> fields = new ArrayList<>(taskFields(task));
		fields.add(field("ORDER_NO", "订单号", task.orderNo(), true));
		fields.add(field("CUSTOMER_NAME", "客户名称", task.customerName(), true));
		fields.add(field("SPECIFICATION", "产品规格", blankAsDash(task.specification()), false));
		fields.add(field("MATERIAL", "材质", blankAsDash(task.material()), false));
		fields.add(field("ORDER_REMARK", "订单备注", blankAsDash(task.orderRemark()), false));
		fields.add(field("PROCESS_CARD_VERSION", "工艺卡版本", task.processCardVersion(), false));
		fields.add(field("FLOW_STEPS", "流转记录", flowSteps(task.batchId()), false));
		fields.add(field("FLOW_RECORDS", "流转记录表", flowRecords(task.batchId()), false));
		fields.add(field("FLOW_QR_PAYLOAD", "流转二维码", "MES:BATCH:" + task.batchId(), false));
		return fields;
	}

	private String flowRecords(UUID batchId) {
		List<FlowCardRecord> records = jdbc.query("""
			select sequence_no, operation_name, status, planned_quantity, good_quantity, scrap_quantity, assigned_to, completed_at
			from planning_task where batch_id = ? order by sequence_no
			""", (rs, rowNum) -> new FlowCardRecord(
			rs.getInt("sequence_no"), rs.getString("operation_name"), rs.getString("status"),
			rs.getBigDecimal("planned_quantity").stripTrailingZeros().toPlainString(),
			rs.getBigDecimal("good_quantity").stripTrailingZeros().toPlainString(),
			rs.getBigDecimal("scrap_quantity").stripTrailingZeros().toPlainString(),
			blankAsDash(rs.getString("assigned_to")),
			rs.getTimestamp("completed_at") == null ? "" : rs.getTimestamp("completed_at").toInstant().toString()), batchId);
		try {
			return objectMapper.writeValueAsString(records);
		} catch (Exception exception) {
			throw new IllegalStateException("Unable to prepare flow card records", exception);
		}
	}

	private String flowSteps(UUID batchId) {
		List<String> steps = jdbc.query("""
			select sequence_no, operation_name, status, good_quantity, scrap_quantity, assigned_to
			from planning_task where batch_id = ? order by sequence_no
			""", (rs, rowNum) -> String.format("%02d. %s | %s | 合格 %s / 报废 %s | %s",
			rs.getInt("sequence_no"), rs.getString("operation_name"), rs.getString("status"),
			rs.getBigDecimal("good_quantity").stripTrailingZeros().toPlainString(),
			rs.getBigDecimal("scrap_quantity").stripTrailingZeros().toPlainString(),
			blankAsDash(rs.getString("assigned_to"))), batchId);
		return String.join("\n", steps);
	}

	private static String operationParameters(TaskDocument task) {
		if (task.engineeringOperationParameters() == null || task.engineeringOperationParameters().isBlank()) {
			return task.engineeringParameters();
		}
		Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(task.operationCode())
			+ "\\\"\\s*:\\s*\\\"((?:\\\\\\\\.|[^\\\"\\\\])*)\\\"").matcher(task.engineeringOperationParameters());
		if (!matcher.find() || matcher.group(1).isBlank()) return task.engineeringParameters();
		return matcher.group(1).replace("\\\\n", "\n").replace("\\\\\"", "\"").replace("\\\\\\\\", "\\");
	}

	private List<String> operationImageUrls(TaskDocument task) {
		if (task.engineeringOperationParameters() == null || task.engineeringOperationParameters().isBlank()) return List.of();
		try {
			JsonNode image = objectMapper.readTree(task.engineeringOperationParameters())
				.path("_operationImages").path(task.operationCode());
			if (image.isTextual() && !image.asText().isBlank()) return List.of(image.asText());
			if (!image.isArray()) return List.of();
			List<String> urls = new ArrayList<>();
			image.forEach(item -> { if (item.isTextual() && !item.asText().isBlank()) urls.add(item.asText()); });
			return urls;
		} catch (Exception ignored) {
			return List.of();
		}
	}

	private List<PrintableField> taskFields(TaskDocument task) {
		return List.of(
			field("TASK_NO", "任务号", task.taskNo(), false),
			field("WORK_ORDER_NO", "工单号", task.workOrderNo(), false),
			field("PRODUCT", "产品", task.productCode() + " - " + task.productName(), false),
			field("OPERATION", "工序", task.operationName(), false),
			field("BATCH_NO", "批次号", task.batchNo(), false),
			field("PLANNED_QUANTITY", "计划数量", task.plannedQuantity().stripTrailingZeros().toPlainString(), false)
		);
	}

	private List<PrintableField> orderDetailFields(UUID orderId) {
		access.requireOrder(orderId);
		CustomerOrderApplication.OrderView order = orders.getOrder(orderId);
		String lines = order.lines().stream().map(line -> line.lineNo() + ". " + line.productCode() + " "
			+ line.productName() + " x " + line.orderedQuantity().stripTrailingZeros().toPlainString() + " " + line.unit())
			.reduce((left, right) -> left + "\n" + right).orElse("无明细");
		return List.of(
			field("ORDER_NO", "订单号", order.orderNo(), true),
			field("CUSTOMER_NAME", "客户名称", order.customerName(), true),
			field("CUSTOMER_CODE", "客户编码", order.customerCode(), true),
			field("ORDER_STATUS", "订单状态", order.status().name(), true),
			field("PRIORITY", "优先级", order.priority().name(), true),
			field("DELIVERY_DATE", "要求交期", order.requestedDeliveryDate() == null ? "未填写" : order.requestedDeliveryDate().toString(), true),
			field("LINES", "产品明细", lines, true),
			field("REMARK", "订单备注", order.remark() == null ? "" : order.remark(), true)
		);
	}

	private List<PrintableField> statisticsFields() {
		Integer orderCount = jdbc.queryForObject("select count(*) from customer_order_header", Integer.class);
		Integer activeTasks = jdbc.queryForObject("select count(*) from planning_task where status in ('ASSIGNED', 'IN_PROGRESS')", Integer.class);
		BigDecimal goodQuantity = jdbc.queryForObject("select coalesce(sum(good_quantity), 0) from planning_task", BigDecimal.class);
		BigDecimal scrapQuantity = jdbc.queryForObject("select coalesce(sum(scrap_quantity), 0) from planning_task", BigDecimal.class);
		return List.of(
			field("GENERATED_AT", "生成时间", Instant.now().toString(), false),
			field("ORDER_COUNT", "订单总数", String.valueOf(orderCount), false),
			field("ACTIVE_TASKS", "进行中任务", String.valueOf(activeTasks), false),
			field("GOOD_QUANTITY", "累计合格数量", goodQuantity.stripTrailingZeros().toPlainString(), false),
			field("SCRAP_QUANTITY", "累计报废数量", scrapQuantity.stripTrailingZeros().toPlainString(), false)
		);
	}

	private TaskDocument requireScopedTask(UUID taskId, String actorCode, Set<String> roles) {
		access.requireTask(taskId);
		TaskDocument task = requireTask(taskId);
		Set<String> allowedOperations = new LinkedHashSet<>();
		roles.forEach(role -> OPERATION_ROLES.forEach((operation, authorizedRoles) -> {
			if (authorizedRoles.contains(role)) allowedOperations.add(operation);
		}));
		if (!allowedOperations.isEmpty()
				&& (!allowedOperations.contains(task.operationCode()) || !actorCode.equals(task.assignedTo()))) {
			throw DomainException.forbidden("DOCUMENT_TASK_SCOPE_DENIED", "当前岗位只能查看已派发给本人的对应工序工艺信息");
		}
		return task;
	}

	private TaskDocument requireTask(UUID taskId) {
		return jdbc.query("""
			select t.id, t.task_no, t.operation_code, t.operation_name, t.planned_quantity, t.good_quantity, t.scrap_quantity,
			       t.assigned_to, b.id as batch_id, b.batch_no, w.work_order_no, w.product_code, w.product_name,
			       o.order_no, o.customer_name, o.remark, p.specification, p.material,
			       w.process_card_version as work_order_process_card_version,
			       w.engineering_parameters as work_order_engineering_parameters,
			       w.engineering_operation_parameters as work_order_engineering_operation_parameters
			       , coalesce(o.process_card_version, '待工程确认') as process_card_version
			       , coalesce(o.engineering_parameters, '待工程师补充订单专属参数') as engineering_parameters
			       , o.engineering_operation_parameters
			from planning_task t
			join planning_batch b on b.id = t.batch_id
			join planning_work_order w on w.id = b.work_order_id
			join customer_order_header o on o.id = w.order_id
			join engineering_product p on p.id = w.product_id
			where t.id = ?
			""", (rs, rowNum) -> new TaskDocument(
			rs.getObject("id", UUID.class), rs.getString("task_no"), rs.getString("operation_code"), rs.getString("operation_name"),
			rs.getBigDecimal("planned_quantity"), rs.getBigDecimal("good_quantity"), rs.getBigDecimal("scrap_quantity"),
			rs.getString("assigned_to"), rs.getObject("batch_id", UUID.class), rs.getString("batch_no"), rs.getString("work_order_no"),
			rs.getString("product_code"), rs.getString("product_name"), rs.getString("order_no"), rs.getString("customer_name"), rs.getString("remark"),
			rs.getString("specification"), rs.getString("material"),
			firstNonBlank(rs.getString("work_order_process_card_version"), rs.getString("process_card_version")),
			firstNonBlank(rs.getString("work_order_engineering_parameters"), rs.getString("engineering_parameters")),
			firstNonBlank(rs.getString("work_order_engineering_operation_parameters"), rs.getString("engineering_operation_parameters"))), taskId)
			.stream().findFirst().orElseThrow(() -> DomainException.notFound("DOCUMENT_TASK_NOT_FOUND", "任务不存在"));
	}

	private static String firstNonBlank(String preferred, String fallback) {
		return preferred == null || preferred.isBlank() ? fallback : preferred;
	}

	private Set<String> rolesFor(String actorCode) {
		return new LinkedHashSet<>(jdbc.query("""
			select role_code from organization_member_role where employee_code = ?
			""", (rs, rowNum) -> rs.getString(1), actorCode));
	}

	private Set<String> permissionsFor(String actorCode) {
		String code = normalize(actorCode);
		Integer memberCount = jdbc.queryForObject("""
			select count(*) from organization_member where employee_code = ? and active = true
			""", Integer.class, code);
		if (memberCount == null || memberCount == 0) {
			throw DomainException.notFound("DOCUMENT_ACTOR_NOT_FOUND", "操作人不存在或已停用");
		}
		return new LinkedHashSet<>(jdbc.query("""
			select distinct rp.permission_code
			from organization_member_role mr
			join access_role_permission rp on rp.role_code = mr.role_code
			where mr.employee_code = ?
			""", (rs, rowNum) -> rs.getString(1), code).stream().filter(permission -> !access.secured() || access.permission(permission)).toList());
	}

	private static UUID requireEntityId(UUID entityId) {
		if (entityId == null) {
			throw DomainException.badRequest("DOCUMENT_ENTITY_REQUIRED", "该文档必须指定业务对象");
		}
		return entityId;
	}

	private static PrintableField field(String code, String label, String value, boolean sensitive) {
		return new PrintableField(code, label, value, sensitive);
	}

	private static String blankAsDash(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}

	private static String titleFor(DocumentType type) {
		return switch (type) {
			case PROCESS_CARD -> "工艺卡";
			case WORKSHOP_JOB_SHEET -> "车间作业单";
			case FLOW_CARD -> "生产流转卡";
			case ORDER_DETAIL -> "订单明细";
			case STATISTICS -> "生产统计表";
		};
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	public enum DocumentType { PROCESS_CARD, WORKSHOP_JOB_SHEET, FLOW_CARD, ORDER_DETAIL, STATISTICS }

	private record FlowCardRecord(int sequenceNo, String operationName, String status, String plannedQuantity,
			String goodQuantity, String scrapQuantity, String assignedTo, String completedAt) { }

	public record PreviewCommand(DocumentType documentType, UUID entityId, String actorCode,
			List<String> selectedFields, DocumentOutput outputType) { }
	public enum DocumentOutput { PREVIEW, PRINT, EXPORT }
	public record DocumentOption(String documentType, String title, boolean containsSensitiveData) { }
	public record PrintableField(String code, String label, String value, boolean sensitive) { }
	public record DocumentPreview(String documentType, String title, UUID entityId, Instant generatedAt,
			boolean sensitiveIncluded, List<PrintableField> fields) { }
	public record AuditView(UUID id, String documentType, UUID entityId, String actorCode,
			List<String> selectedFields, boolean sensitiveIncluded, String outputType, Instant occurredAt) { }
	private record TaskDocument(UUID taskId, String taskNo, String operationCode, String operationName,
			BigDecimal plannedQuantity, BigDecimal goodQuantity, BigDecimal scrapQuantity, String assignedTo,
			UUID batchId, String batchNo, String workOrderNo, String productCode, String productName, String orderNo,
			String customerName, String orderRemark, String specification, String material, String processCardVersion,
			String engineeringParameters, String engineeringOperationParameters) { }
	private record SopHeader(UUID id, String safetyNotice, String preparationNote) { }
	private record SopStep(int stepNo, String title, String instruction) { }
	private record SopKeyParameter(String name, String value) { }
	private record PublishedSop(List<SopStep> steps, String safetyNotice, String preparationNote,
			List<String> qualityPoints, List<SopKeyParameter> keyParameters) { }
}

@RestController
@RequestMapping("/api/documents")
class DocumentController {

	private final DocumentApplication documents;

	DocumentController(DocumentApplication documents) {
		this.documents = documents;
	}

	@GetMapping("/options")
	List<DocumentApplication.DocumentOption> options(@RequestParam String actorCode) {
		return documents.options(actorCode);
	}

	@PostMapping("/previews")
	DocumentApplication.DocumentPreview preview(@Valid @RequestBody PreviewRequest request) {
		return documents.preview(new DocumentApplication.PreviewCommand(
			request.documentType(), request.entityId(), request.actorCode(), request.selectedFields(),
			request.outputType() == null ? DocumentApplication.DocumentOutput.PREVIEW : request.outputType()));
	}

	@GetMapping("/audits")
	List<DocumentApplication.AuditView> audits(@RequestParam String actorCode) {
		return documents.audits(actorCode);
	}

	record PreviewRequest(@NotNull DocumentApplication.DocumentType documentType, UUID entityId,
			@NotBlank @Size(max = 64) String actorCode,
			@NotEmpty List<@NotBlank @Size(max = 64) String> selectedFields,
			DocumentApplication.DocumentOutput outputType) { }
}
