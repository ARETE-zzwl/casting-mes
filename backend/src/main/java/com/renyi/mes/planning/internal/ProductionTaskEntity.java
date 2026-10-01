package com.renyi.mes.planning.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.planning.TaskStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "planning_task")
public class ProductionTaskEntity {

	@Id
	private UUID id;
	private String taskNo;
	private UUID batchId;
	private int sequenceNo;
	private String operationCode;
	private String operationName;
	private String shellLineMode;
	private BigDecimal plannedQuantity;
	private BigDecimal goodQuantity;
	private BigDecimal scrapQuantity;
	@Enumerated(EnumType.STRING)
	private TaskStatus status;
	private String assignedTo;
	private String reportingMode;
	private BigDecimal assignedQuantity;
	private String settlementUnit;
	private boolean countingDeferred;
	private BigDecimal treeCount;
	private BigDecimal piecesPerTree;
	private String compensationMode;
	private BigDecimal completedWeightKg;
	private Instant startedAt;
	private Instant completedAt;
	@Version
	private long version;
	private Instant createdAt;

	protected ProductionTaskEntity() {
	}

	public ProductionTaskEntity(
		UUID id,
		String taskNo,
		UUID batchId,
		int sequenceNo,
		String operationCode,
		String operationName,
		BigDecimal plannedQuantity,
		BigDecimal goodQuantity,
		BigDecimal scrapQuantity,
		TaskStatus status,
		Instant createdAt
	) {
		this.id = id;
		this.taskNo = taskNo;
		this.batchId = batchId;
		this.sequenceNo = sequenceNo;
		this.operationCode = operationCode;
		this.operationName = operationName;
		this.shellLineMode = "MANUAL_SHELL_BUILDING".equals(operationCode) ? "MANUAL" : null;
		this.plannedQuantity = plannedQuantity;
		this.goodQuantity = goodQuantity;
		this.scrapQuantity = scrapQuantity;
		this.status = status;
		this.reportingMode = "SELF_REPORTED_QUANTITY";
		this.settlementUnit = defaultSettlementUnit(operationCode);
		this.compensationMode = defaultCompensationMode(operationCode);
		this.createdAt = createdAt;
	}

	public void assign(
		String workerCode,
		String requestedMode,
		BigDecimal requestedQuantity,
		String requestedSettlementUnit,
		String requestedCompensationMode
	) {
		if (status != TaskStatus.READY && status != TaskStatus.ASSIGNED) {
			throw DomainException.conflict("TASK_STATE_CONFLICT", "Only ready or assigned tasks can be assigned");
		}
		if (requiresShellLineMode() && shellLineMode == null) {
			throw DomainException.conflict("SHELL_LINE_MODE_REQUIRED", "制壳派工前请选择自动化线或手动线");
		}
		String mode = "TREE_ASSEMBLY".equals(operationCode) ? "TREE_COUNT" : normalizeMode(requestedMode);
		String unit = requestedSettlementUnit == null || requestedSettlementUnit.isBlank()
			? settlementUnit : normalizeUnit(requestedSettlementUnit);
		if ("HANDOFF_TO_TREE".equals(mode) && !"WAX_REPAIR".equals(operationCode)) {
			throw DomainException.badRequest("TASK_REPORTING_MODE_INVALID", "Count deferral is only available for wax repair");
		}
		if ("TREE_COUNT".equals(mode) && !"TREE_ASSEMBLY".equals(operationCode)) {
			throw DomainException.badRequest("TASK_REPORTING_MODE_INVALID", "Tree reporting is only available for tree assembly");
		}
		if ("FIXED_QUANTITY".equals(mode)) {
			if (requestedQuantity == null || requestedQuantity.signum() <= 0) {
				throw DomainException.badRequest("TASK_FIXED_QUANTITY_REQUIRED", "Fixed quantity is required");
			}
			if (requestedQuantity.compareTo(plannedQuantity) != 0) {
				throw DomainException.conflict("TASK_SPLIT_REQUIRED", "Split the task before assigning a partial fixed quantity");
			}
		} else if (requestedQuantity != null) {
			throw DomainException.badRequest("TASK_FIXED_QUANTITY_UNEXPECTED", "Only fixed-quantity assignments accept a quantity");
		}
		if (!unit.equals(settlementUnit)) {
			throw DomainException.conflict("TASK_SETTLEMENT_RULE_READ_ONLY", "结算单位由工序规则配置，派工时不可修改");
		}
		if ("TREE".equals(unit) && !"TREE_ASSEMBLY".equals(operationCode)) {
			throw DomainException.badRequest("TASK_SETTLEMENT_UNIT_INVALID", "按树结算仅适用于组树工序");
		}
		if (requestedCompensationMode != null && !requestedCompensationMode.isBlank()
				&& !normalizeCompensationMode(requestedCompensationMode, operationCode).equals(compensationMode)) {
			throw DomainException.conflict("TASK_COMPENSATION_RULE_READ_ONLY", "结算口径由工艺与工价规则配置，派工时不可修改");
		}
		assignedTo = workerCode;
		reportingMode = mode;
		assignedQuantity = "FIXED_QUANTITY".equals(mode) ? requestedQuantity : null;
		settlementUnit = unit;
		status = TaskStatus.ASSIGNED;
	}

	public void assign(String workerCode) {
		if (status != TaskStatus.READY && status != TaskStatus.ASSIGNED) {
			throw DomainException.conflict("TASK_STATE_CONFLICT", "只有待派工任务可以分派");
		}
		assignedTo = workerCode;
		status = TaskStatus.ASSIGNED;
	}

	public void configureShellLineMode(String requestedMode) {
		if (!isShellBuilding()) throw DomainException.conflict("SHELL_LINE_MODE_TASK_INVALID", "仅制壳任务可以选择制壳线");
		if (status != TaskStatus.READY || assignedTo != null) {
			throw DomainException.conflict("SHELL_LINE_MODE_LOCKED", "派工或开工后不能变更制壳线");
		}
		String mode = requestedMode == null ? "" : requestedMode.trim().toUpperCase();
		if (!mode.equals("AUTOMATED") && !mode.equals("MANUAL")) {
			throw DomainException.badRequest("SHELL_LINE_MODE_INVALID", "制壳线仅支持自动化线或手动线");
		}
		if ("MANUAL_SHELL_BUILDING".equals(operationCode) && !mode.equals("MANUAL")) {
			throw DomainException.conflict("SHELL_LINE_MODE_ROUTE_INVALID", "低温蜡人工制壳任务只能选择手动线");
		}
		shellLineMode = mode;
	}

	public void unlockForDispatch(BigDecimal transferableQuantity) {
		if (status != TaskStatus.BLOCKED) {
			throw DomainException.conflict("TASK_STATE_CONFLICT", "Only blocked tasks can be unlocked");
		}
		if (transferableQuantity == null || transferableQuantity.signum() <= 0) {
			throw DomainException.conflict("TASK_NO_TRANSFERABLE_QUANTITY", "No qualified quantity is available for the next operation");
		}
		plannedQuantity = transferableQuantity;
		status = TaskStatus.READY;
	}

	public void adjustInitialPlannedQuantity(BigDecimal quantity) {
		if ((status != TaskStatus.READY && status != TaskStatus.BLOCKED) || assignedTo != null
				|| goodQuantity.signum() != 0 || scrapQuantity.signum() != 0) {
			throw DomainException.conflict("TASK_INITIAL_QUANTITY_LOCKED", "派工或报工后不能调整初始投产数量");
		}
		plannedQuantity = quantity;
	}

	public void skipForDirectFinished(BigDecimal transferableQuantity, Instant now) {
		if (status != TaskStatus.BLOCKED || !"OPTIONAL_FINISHING".equals(operationCode)) {
			throw DomainException.conflict("TASK_SKIP_STATE_INVALID", "当前任务不能跳过后处理");
		}
		plannedQuantity = transferableQuantity;
		goodQuantity = transferableQuantity;
		scrapQuantity = BigDecimal.ZERO;
		countingDeferred = true;
		status = TaskStatus.COMPLETED;
		completedAt = now;
	}

	public void start(String operatorCode, Instant now) {
		assertAssignedOperator(operatorCode);
		if (status != TaskStatus.ASSIGNED) {
			throw DomainException.conflict("TASK_STATE_CONFLICT", "只有已分派任务可以开工");
		}
		status = TaskStatus.IN_PROGRESS;
		startedAt = now;
	}

	public void alignPlannedQuantity(BigDecimal transferableQuantity) {
		if (status != TaskStatus.ASSIGNED || goodQuantity.signum() != 0 || scrapQuantity.signum() != 0) {
			throw DomainException.conflict("TASK_STATE_CONFLICT", "任务当前状态不能调整计划数量");
		}
		if (transferableQuantity.signum() <= 0) {
			throw DomainException.conflict("TASK_NO_TRANSFERABLE_QUANTITY", "前工序无合格数量可转入");
		}
		plannedQuantity = transferableQuantity;
	}

	public void report(BigDecimal good, BigDecimal scrap, String operatorCode, Instant now) {
		assertAssignedOperator(operatorCode);
		if (!"SELF_REPORTED_QUANTITY".equals(reportingMode) && !"FIXED_QUANTITY".equals(reportingMode)) {
			throw DomainException.conflict("TASK_REPORTING_MODE_MISMATCH", "Use the dedicated reporting method for this task");
		}
		if (status != TaskStatus.IN_PROGRESS) {
			throw DomainException.conflict("TASK_STATE_CONFLICT", "只有生产中的任务可以报工");
		}
		if (good.signum() < 0 || scrap.signum() < 0) {
			throw DomainException.badRequest("REPORT_QUANTITY_INVALID", "合格数和报废数不能为负数");
		}

		BigDecimal reported = good.add(scrap);
		if (reported.signum() <= 0) {
			throw DomainException.badRequest("REPORT_QUANTITY_INVALID", "本次报工数量必须大于零");
		}
		BigDecimal nextGood = goodQuantity.add(good);
		BigDecimal nextScrap = scrapQuantity.add(scrap);
		if (nextGood.add(nextScrap).compareTo(plannedQuantity) > 0) {
			throw DomainException.conflict("REPORT_QUANTITY_EXCEEDED", "累计报工数量不能超过任务计划数量");
		}

		goodQuantity = nextGood;
		scrapQuantity = nextScrap;
		if (goodQuantity.add(scrapQuantity).compareTo(plannedQuantity) == 0) {
			status = TaskStatus.COMPLETED;
			completedAt = now;
		}
	}

	public void handoffWithoutCount(String operatorCode, Instant now) {
		handoffWithoutCount(operatorCode, null, now);
	}

	public void handoffWithoutCount(String operatorCode, BigDecimal receivedQuantity, Instant now) {
		assertAssignedOperator(operatorCode);
		if (!("HANDOFF_TO_TREE".equals(reportingMode) && "WAX_REPAIR".equals(operationCode))
				&& !"HANDOFF_TO_NEXT".equals(reportingMode)) {
			throw DomainException.conflict("TASK_REPORTING_MODE_MISMATCH", "This task does not support count deferral");
		}
		if (status != TaskStatus.IN_PROGRESS) {
			throw DomainException.conflict("TASK_STATE_CONFLICT", "Only in-progress tasks can be handed off");
		}
		if (receivedQuantity != null && (receivedQuantity.signum() < 0 || receivedQuantity.compareTo(plannedQuantity) > 0)) {
			throw DomainException.badRequest("HANDOFF_RECEIVED_QUANTITY_INVALID", "Received quantity must be between zero and the expected quantity");
		}
		goodQuantity = receivedQuantity == null ? plannedQuantity : receivedQuantity;
		scrapQuantity = BigDecimal.ZERO;
		countingDeferred = true;
		status = TaskStatus.COMPLETED;
		completedAt = now;
	}

	public void reportBySupervisor(BigDecimal good, BigDecimal scrap, Instant now) {
		if (!"SELF_REPORTED_QUANTITY".equals(reportingMode) && !"FIXED_QUANTITY".equals(reportingMode)) {
			throw DomainException.conflict("TASK_REPORTING_MODE_MISMATCH", "Use the dedicated reporting method for this task");
		}
		applyReport(good, scrap, now);
	}

	public void recordCompletedWeight(BigDecimal weightKg) {
		if (weightKg == null || weightKg.signum() <= 0) {
			throw DomainException.badRequest("TASK_WEIGHT_INVALID", "Completed weight must be greater than zero");
		}
		if (!"PIECE_KG".equals(compensationMode) || status != TaskStatus.COMPLETED) {
			throw DomainException.conflict("TASK_WEIGHT_NOT_ALLOWED", "Weight can only be recorded for a completed kilogram piecework task");
		}
		completedWeightKg = weightKg;
	}

	public void reportTree(
		BigDecimal reportedTreeCount,
		BigDecimal reportedPiecesPerTree,
		BigDecimal scrap,
		String operatorCode,
		Instant now
	) {
		assertAssignedOperator(operatorCode);
		if (!"TREE_COUNT".equals(reportingMode) || !"TREE_ASSEMBLY".equals(operationCode)) {
			throw DomainException.conflict("TASK_REPORTING_MODE_MISMATCH", "This task must use tree reporting");
		}
		if (status != TaskStatus.IN_PROGRESS) {
			throw DomainException.conflict("TASK_STATE_CONFLICT", "Only in-progress tasks can be reported");
		}
		if (!isPositiveWholeNumber(reportedTreeCount) || !isPositiveWholeNumber(reportedPiecesPerTree)
				|| !isWholeNumber(scrap) || scrap.signum() < 0) {
			throw DomainException.badRequest("TREE_REPORT_QUANTITY_INVALID", "Tree count and pieces per tree must be positive whole numbers");
		}
		BigDecimal calculatedGood = reportedTreeCount.multiply(reportedPiecesPerTree);
		if (calculatedGood.add(scrap).compareTo(plannedQuantity) != 0) {
			throw DomainException.conflict("TREE_REPORT_QUANTITY_MISMATCH", "Tree output plus scrap must match the task quantity");
		}
		treeCount = reportedTreeCount;
		piecesPerTree = reportedPiecesPerTree;
		goodQuantity = calculatedGood;
		scrapQuantity = scrap;
		status = TaskStatus.COMPLETED;
		completedAt = now;
	}

	private void applyReport(BigDecimal good, BigDecimal scrap, Instant now) {
		if (status != TaskStatus.IN_PROGRESS) {
			throw DomainException.conflict("TASK_STATE_CONFLICT", "Only in-progress tasks can be reported");
		}
		if (good == null || scrap == null || good.signum() < 0 || scrap.signum() < 0) {
			throw DomainException.badRequest("REPORT_QUANTITY_INVALID", "Good and scrap quantities must be non-negative");
		}
		BigDecimal reported = good.add(scrap);
		if (reported.signum() <= 0) {
			throw DomainException.badRequest("REPORT_QUANTITY_INVALID", "Reported quantity must be greater than zero");
		}
		BigDecimal nextGood = goodQuantity.add(good);
		BigDecimal nextScrap = scrapQuantity.add(scrap);
		if (nextGood.add(nextScrap).compareTo(plannedQuantity) > 0) {
			throw DomainException.conflict("REPORT_QUANTITY_EXCEEDED", "Reported quantity exceeds the task quantity");
		}
		goodQuantity = nextGood;
		scrapQuantity = nextScrap;
		if (goodQuantity.add(scrapQuantity).compareTo(plannedQuantity) == 0) {
			status = TaskStatus.COMPLETED;
			completedAt = now;
		}
	}

	private static boolean isPositiveWholeNumber(BigDecimal value) {
		return value != null && value.signum() > 0 && isWholeNumber(value);
	}

	private static boolean isWholeNumber(BigDecimal value) {
		return value != null && value.stripTrailingZeros().scale() <= 0;
	}

	private static String normalizeMode(String value) {
		String mode = value == null || value.isBlank() ? "SELF_REPORTED_QUANTITY" : value.trim().toUpperCase();
		if (!mode.equals("SELF_REPORTED_QUANTITY") && !mode.equals("FIXED_QUANTITY")
				&& !mode.equals("HANDOFF_TO_TREE") && !mode.equals("HANDOFF_TO_NEXT") && !mode.equals("TREE_COUNT")) {
			throw DomainException.badRequest("TASK_REPORTING_MODE_INVALID", "Unsupported reporting mode");
		}
		return mode;
	}

	private static String normalizeUnit(String value) {
		String unit = value == null || value.isBlank() ? "PCS" : value.trim().toUpperCase();
		if (!unit.equals("PCS") && !unit.equals("TREE")) {
			throw DomainException.badRequest("TASK_SETTLEMENT_UNIT_INVALID", "Settlement unit must be PCS or TREE");
		}
		return unit;
	}

	private static String normalizeCompensationMode(String value, String operationCode) {
		String mode = value == null || value.isBlank() ? defaultCompensationMode(operationCode) : value.trim().toUpperCase();
		if (!mode.equals("PIECE_PCS") && !mode.equals("PIECE_TREE") && !mode.equals("HOURLY")
				&& !mode.equals("PIECE_KG") && !mode.equals("HANDOFF_ONLY")) {
			throw DomainException.badRequest("TASK_COMPENSATION_MODE_INVALID", "Unsupported compensation mode");
		}
		return mode;
	}

	private static String defaultCompensationMode(String operationCode) {
		return switch (operationCode) {
			case "WAX_INJECTION", "WAX_REPAIR" -> "PIECE_PCS";
			case "TREE_ASSEMBLY" -> "PIECE_TREE";
			case "SHELL_BUILDING", "MANUAL_SHELL_BUILDING", "DEWAX", "POURING", "KNOCKOUT_CUTTING", "KNOCKOUT", "CUTTING", "SEMI_FINISHED_COUNT" -> "HOURLY";
			case "OPTIONAL_FINISHING" -> "PIECE_KG";
			default -> "HANDOFF_ONLY";
		};
	}

	private boolean isShellBuilding() {
		return "SHELL_BUILDING".equals(operationCode) || "MANUAL_SHELL_BUILDING".equals(operationCode);
	}

	private boolean requiresShellLineMode() {
		return "SHELL_BUILDING".equals(operationCode);
	}

	private static String defaultSettlementUnit(String operationCode) {
		return "TREE_ASSEMBLY".equals(operationCode) ? "TREE" : "PCS";
	}

	private void assertAssignedOperator(String operatorCode) {
		if (assignedTo == null || !assignedTo.equals(operatorCode)) {
			throw DomainException.conflict("TASK_OPERATOR_MISMATCH", "任务只能由被分派人员操作");
		}
	}

	public UUID id() {
		return id;
	}

	public String taskNo() {
		return taskNo;
	}

	public UUID batchId() {
		return batchId;
	}

	public int sequenceNo() {
		return sequenceNo;
	}

	public String operationCode() {
		return operationCode;
	}

	public String operationName() {
		return operationName;
	}

	public String shellLineMode() { return shellLineMode; }

	public BigDecimal plannedQuantity() {
		return plannedQuantity;
	}

	public BigDecimal goodQuantity() {
		return goodQuantity;
	}

	public BigDecimal scrapQuantity() {
		return scrapQuantity;
	}

	public TaskStatus status() {
		return status;
	}

	public String assignedTo() {
		return assignedTo;
	}

	public String reportingMode() {
		return reportingMode;
	}

	public BigDecimal assignedQuantity() {
		return assignedQuantity;
	}

	public String settlementUnit() {
		return settlementUnit;
	}

	public boolean countingDeferred() {
		return countingDeferred;
	}

	public BigDecimal treeCount() {
		return treeCount;
	}

	public BigDecimal piecesPerTree() {
		return piecesPerTree;
	}

	public String compensationMode() {
		return compensationMode;
	}

	public BigDecimal completedWeightKg() {
		return completedWeightKg;
	}

	public Instant startedAt() {
		return startedAt;
	}

	public Instant completedAt() {
		return completedAt;
	}

	public Instant createdAt() {
		return createdAt;
	}
}
