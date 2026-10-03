package com.renyi.mes.planning;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.OrderReleasePort;
import com.renyi.mes.common.ProductionTaskPort;
import com.renyi.mes.common.ProductionTaskPort.TaskSnapshot;
import com.renyi.mes.customerorder.CustomerOrderApplication;
import com.renyi.mes.customerorder.CustomerOrderApplication.OrderLineView;
import com.renyi.mes.engineering.RouteCatalog;
import com.renyi.mes.engineering.RouteType;
import com.renyi.mes.notification.NotificationApplication;
import com.renyi.mes.planning.internal.ProductionBatchEntity;
import com.renyi.mes.planning.internal.ProductionBatchRepository;
import com.renyi.mes.planning.internal.ProductionTaskEntity;
import com.renyi.mes.planning.internal.ProductionTaskRepository;
import com.renyi.mes.planning.internal.WorkOrderEntity;
import com.renyi.mes.planning.internal.WorkOrderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanningApplication implements OrderReleasePort, ProductionTaskPort {
	private static final Logger log = LoggerFactory.getLogger(PlanningApplication.class);

	private final CustomerOrderApplication customerOrders;
	private final RouteCatalog routes;
	private final WorkOrderRepository workOrders;
	private final ProductionBatchRepository batches;
	private final ProductionTaskRepository tasks;
	private final ProductionLineScopeApplication lineScopes;
	private final ProductionShortageAlertApplication shortageAlerts;
	private final NotificationApplication notifications;
	private final JdbcTemplate jdbc;

	public PlanningApplication(
		CustomerOrderApplication customerOrders,
		RouteCatalog routes,
		WorkOrderRepository workOrders,
		ProductionBatchRepository batches,
		ProductionTaskRepository tasks,
		ProductionLineScopeApplication lineScopes,
		ProductionShortageAlertApplication shortageAlerts,
		NotificationApplication notifications,
		JdbcTemplate jdbc
	) {
		this.customerOrders = customerOrders;
		this.routes = routes;
		this.workOrders = workOrders;
		this.batches = batches;
		this.tasks = tasks;
		this.lineScopes = lineScopes;
		this.shortageAlerts = shortageAlerts;
		this.notifications = notifications;
		this.jdbc = jdbc;
	}

	@Transactional
	public List<WorkOrderView> releaseOrder(UUID orderId) {
		CustomerOrderApplication.OrderView order = customerOrders.lockReleasableOrder(orderId);
		List<WorkOrderEntity> existing = workOrders.findByOrderIdOrderByCreatedAt(orderId);
		if (!existing.isEmpty()) {
			return existing.stream().flatMap(workOrder -> workOrderViews(workOrder).stream()).toList();
		}

		Instant now = Instant.now();
		for (OrderLineView line : order.lines()) {
			if (line.engineeringParameters() == null || line.engineeringParameters().isBlank()) {
				throw DomainException.conflict("ORDER_LINE_PROCESS_CARD_REQUIRED", "订单产品缺少已确认的工艺卡，不能生成工单：" + line.productName());
			}
			UUID workOrderId = UUID.randomUUID();
			WorkOrderEntity workOrder = workOrders.save(new WorkOrderEntity(
				workOrderId,
				identifier("WO"),
				order.id(),
				line.id(),
				line.productId(),
				line.productCode(),
				line.productName(),
				line.productMaterial(),
				line.routeType(),
				line.routeVersion(),
				line.processCardVersion(),
				line.engineeringParameters(),
				line.engineeringOperationParameters(),
				line.orderedQuantity(),
				WorkOrderStatus.RELEASED,
				now
			));

			createBatch(workOrder, line.orderedQuantity(), now);
		}
		customerOrders.markReleased(order.id());
		return workOrders.findByOrderIdOrderByCreatedAt(orderId).stream()
			.flatMap(workOrder -> workOrderViews(workOrder).stream()).toList();
	}

	@Override
	@Transactional
	public void release(UUID orderId) {
		releaseOrder(orderId);
	}

	@Transactional(readOnly = true)
	public List<WorkOrderView> listWorkOrders(RouteType routeType) {
		return workOrders.findAllByOrderByCreatedAtDesc().stream()
			.filter(workOrder -> routeType == null || routeType == workOrder.routeType())
			.flatMap(workOrder -> workOrderViews(workOrder).stream()).toList();
	}

	@Transactional(readOnly = true)
	public List<WorkOrderView> workOrdersForOrder(UUID orderId) {
		return productionForOrder(orderId).stream().map(BatchProductionView::workOrder).toList();
	}

	@Transactional(readOnly = true)
	public List<BatchProductionView> productionForOrder(UUID orderId) {
		List<WorkOrderEntity> orderWorkOrders = workOrders.findByOrderIdOrderByCreatedAt(orderId);
		if (orderWorkOrders.isEmpty()) return List.of();
		Map<UUID, BigDecimal> quantities = customerOrders.getOrder(orderId).lines().stream()
			.collect(Collectors.toMap(OrderLineView::id, OrderLineView::orderedQuantity));
		List<ProductionBatchEntity> orderBatches = batches.findByWorkOrderIdInOrderByCreatedAtAscIdAsc(
			orderWorkOrders.stream().map(WorkOrderEntity::id).toList());
		if (orderBatches.isEmpty()) return List.of();
		Map<UUID, List<ProductionBatchEntity>> batchesByWorkOrder = orderBatches.stream()
			.collect(Collectors.groupingBy(ProductionBatchEntity::workOrderId));
		Map<UUID, List<ProductionTaskEntity>> tasksByBatch = tasks.findByBatchIdInOrderByBatchIdAscSequenceNoAsc(
			orderBatches.stream().map(ProductionBatchEntity::id).toList()).stream()
			.collect(Collectors.groupingBy(ProductionTaskEntity::batchId));
		return orderWorkOrders.stream().flatMap(workOrder -> batchesByWorkOrder.getOrDefault(workOrder.id(), List.of()).stream()
			.map(batch -> {
				List<ProductionTaskEntity> batchTasks = tasksByBatch.getOrDefault(batch.id(), List.of());
				return new BatchProductionView(toWorkOrderView(workOrder, batch,
					quantities.getOrDefault(workOrder.orderLineId(), workOrder.plannedQuantity()), batchTasks),
					batchTasks.stream().map(task -> toTaskView(task, batch, workOrder)).toList());
			})).toList();
	}

	public record BatchProductionView(WorkOrderView workOrder, List<TaskView> tasks) { }

	@Transactional
	public List<WorkOrderView> configureBatches(UUID workOrderId, List<BigDecimal> quantities, String supervisorCode) {
		WorkOrderEntity workOrder = workOrders.findById(workOrderId)
			.orElseThrow(() -> DomainException.notFound("WORK_ORDER_NOT_FOUND", "工单不存在"));
		if (!lineScopes.canAccess(supervisorCode, workOrder.routeType())) {
			throw DomainException.forbidden("WORK_ORDER_LINE_FORBIDDEN", "当前主管无权配置该生产线的批次");
		}
		List<BigDecimal> batchQuantities = quantities == null ? List.of() : quantities.stream().toList();
		if (batchQuantities.isEmpty() || batchQuantities.stream().anyMatch(quantity -> quantity == null
			|| quantity.compareTo(BigDecimal.ZERO) <= 0 || quantity.stripTrailingZeros().scale() > 0)) {
			throw DomainException.badRequest("BATCH_QUANTITY_INTEGER_REQUIRED", "生产批次数量必须为正整数");
		}
		BigDecimal total = batchQuantities.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		if (total.compareTo(workOrder.plannedQuantity()) != 0) {
			throw DomainException.badRequest("BATCH_QUANTITY_TOTAL_INVALID", "各批次数量之和必须等于工单计划数量");
		}
		List<ProductionBatchEntity> existing = batches.findByWorkOrderIdOrderByCreatedAt(workOrderId);
		boolean started = existing.stream().flatMap(batch -> tasks.findByBatchIdOrderBySequenceNo(batch.id()).stream())
			.anyMatch(task -> task.status() != TaskStatus.READY && task.status() != TaskStatus.BLOCKED);
		if (started) {
			throw DomainException.conflict("BATCH_ALREADY_STARTED", "已派工、开工或完工的批次不能重新拆分，以保证生产追溯");
		}
		existing.forEach(batch -> {
			jdbc.update("delete from schedule_queue_item where task_id in (select id from planning_task where batch_id = ?)", batch.id());
			tasks.deleteAll(tasks.findByBatchIdOrderBySequenceNo(batch.id()));
		});
		batches.deleteAll(existing);
		Instant now = Instant.now();
		batchQuantities.forEach(quantity -> createBatch(workOrder, quantity, now));
		tasks.flush();
		refreshShortageAlert(workOrderId);
		return workOrderViews(workOrder);
	}

	@Transactional
	public List<WorkOrderView> applyInitialProductionQuantity(UUID workOrderId, BigDecimal productionQuantity, String supervisorCode) {
		WorkOrderEntity workOrder = workOrders.findById(workOrderId)
			.orElseThrow(() -> DomainException.notFound("WORK_ORDER_NOT_FOUND", "工单不存在"));
		if (!lineScopes.canAccess(supervisorCode, workOrder.routeType())) {
			throw DomainException.forbidden("WORK_ORDER_LINE_FORBIDDEN", "当前主管无权调整该生产线工单");
		}
		if (productionQuantity == null || productionQuantity.compareTo(workOrder.plannedQuantity()) < 0) {
			throw DomainException.badRequest("PRODUCTION_MARGIN_INVALID", "实际投产数量不得小于订单数量");
		}
		List<ProductionBatchEntity> existing = batches.findByWorkOrderIdOrderByCreatedAt(workOrderId);
		if (existing.size() != 1) {
			throw DomainException.conflict("PRODUCTION_MARGIN_BATCH_CONFIGURED", "已拆分批次时，请直接按含余量的数量重新设置各批次");
		}
		ProductionBatchEntity batch = existing.getFirst();
		List<ProductionTaskEntity> batchTasks = tasks.findByBatchIdOrderBySequenceNo(batch.id());
		if (batchTasks.stream().anyMatch(task -> task.status() != TaskStatus.READY && task.status() != TaskStatus.BLOCKED)) {
			throw DomainException.conflict("PRODUCTION_MARGIN_LOCKED", "已有派工、开工或报工记录，不能调整投产余量");
		}
		workOrder.adjustPlannedQuantity(productionQuantity);
		batch.adjustPlannedQuantity(productionQuantity);
		batchTasks.forEach(task -> task.adjustInitialPlannedQuantity(productionQuantity));
		tasks.flush();
		refreshShortageAlert(workOrderId);
		return workOrderViews(workOrder);
	}

	@Transactional
	public WorkOrderView launchBatch(UUID batchId, BigDecimal productionQuantity, String supervisorCode) {
		ProductionBatchEntity batch = batches.findById(batchId)
			.orElseThrow(() -> DomainException.notFound("BATCH_NOT_FOUND", "生产批次不存在"));
		WorkOrderEntity workOrder = workOrders.findById(batch.workOrderId())
			.orElseThrow(() -> DomainException.notFound("WORK_ORDER_NOT_FOUND", "工单不存在"));
		if (!lineScopes.canAccess(supervisorCode, workOrder.routeType())) {
			throw DomainException.forbidden("WORK_ORDER_LINE_FORBIDDEN", "当前主管无权投产该生产线批次");
		}
		if (batch.status() != BatchStatus.PENDING_LAUNCH) {
			throw DomainException.conflict("BATCH_LAUNCH_LOCKED", "该批次已投产，不能重复确认");
		}
		if (productionQuantity == null || productionQuantity.signum() <= 0 || productionQuantity.stripTrailingZeros().scale() > 0) {
			throw DomainException.badRequest("PRODUCTION_QUANTITY_INTEGER_REQUIRED", "实际投产数量必须为正整数");
		}
		List<ProductionTaskEntity> batchTasks = tasks.findByBatchIdOrderBySequenceNo(batch.id());
		if (batchTasks.isEmpty() || batchTasks.stream().anyMatch(task -> task.status() != TaskStatus.BLOCKED)) {
			throw DomainException.conflict("BATCH_LAUNCH_TASK_STATE_INVALID", "批次任务状态不允许确认投产");
		}
		List<ProductionBatchEntity> orderBatches = batches.findByWorkOrderIdOrderByCreatedAt(workOrder.id());
		BigDecimal totalAfterLaunch = orderBatches.stream().map(ProductionBatchEntity::plannedQuantity)
			.reduce(BigDecimal.ZERO, BigDecimal::add).subtract(batch.plannedQuantity()).add(productionQuantity);
		BigDecimal orderedQuantity = jdbc.queryForObject("select ordered_quantity from customer_order_line where id = ?", BigDecimal.class, workOrder.orderLineId());
		if (totalAfterLaunch.compareTo(orderedQuantity) < 0) {
			throw DomainException.badRequest("PRODUCTION_MARGIN_INVALID", "各批实际投产数量合计不得小于订单数量");
		}
		batch.adjustPlannedQuantity(productionQuantity);
		workOrder.adjustPlannedQuantity(totalAfterLaunch);
		batchTasks.forEach(task -> task.adjustInitialPlannedQuantity(productionQuantity));
		batchTasks.getFirst().unlockForDispatch(productionQuantity);
		batch.launch();
		tasks.flush();
		refreshShortageAlert(workOrder.id());
		return toWorkOrderView(workOrder, batch);
	}

	@Transactional
	public TaskView confirmInitialBatchProductionQuantity(UUID taskId, BigDecimal productionQuantity, String supervisorCode) {
		ProductionTaskEntity task = requireLockedTask(taskId);
		if (task.sequenceNo() != 1 || task.status() != TaskStatus.READY) {
			throw DomainException.conflict("PRODUCTION_QUANTITY_CONFIRMATION_LOCKED", "仅可在首道工序派工前确认实际投产数量");
		}
		if (productionQuantity == null || productionQuantity.signum() <= 0 || productionQuantity.stripTrailingZeros().scale() > 0) {
			throw DomainException.badRequest("PRODUCTION_QUANTITY_INTEGER_REQUIRED", "实际投产数量必须为正整数");
		}
		ProductionBatchEntity batch = batches.findById(task.batchId())
			.orElseThrow(() -> DomainException.notFound("PRODUCTION_BATCH_NOT_FOUND", "生产批次不存在"));
		WorkOrderEntity workOrder = workOrders.findById(batch.workOrderId())
			.orElseThrow(() -> DomainException.notFound("WORK_ORDER_NOT_FOUND", "工单不存在"));
		if (!lineScopes.canAccess(supervisorCode, workOrder.routeType())) {
			throw DomainException.forbidden("WORK_ORDER_LINE_FORBIDDEN", "当前主管无权调整该生产线工单");
		}
		List<ProductionBatchEntity> orderBatches = batches.findByWorkOrderIdOrderByCreatedAt(workOrder.id());
		BigDecimal totalAfterConfirmation = orderBatches.stream().map(ProductionBatchEntity::plannedQuantity)
			.reduce(BigDecimal.ZERO, BigDecimal::add).subtract(batch.plannedQuantity()).add(productionQuantity);
		BigDecimal orderedQuantity = jdbc.queryForObject("select ordered_quantity from customer_order_line where id = ?", BigDecimal.class, workOrder.orderLineId());
		if (totalAfterConfirmation.compareTo(orderedQuantity) < 0) {
			throw DomainException.badRequest("PRODUCTION_MARGIN_INVALID", "各批实际投产数量合计不得小于订单数量");
		}
		batch.adjustPlannedQuantity(productionQuantity);
		workOrder.adjustPlannedQuantity(totalAfterConfirmation);
		tasks.findByBatchIdOrderBySequenceNo(batch.id()).forEach(candidate -> candidate.adjustInitialPlannedQuantity(productionQuantity));
		tasks.flush();
		refreshShortageAlert(workOrder.id());
		return toTaskView(task);
	}

	@Transactional(readOnly = true)
	public List<TaskView> listTasks(TaskStatus status, String assignedTo, UUID orderId) {
		return listTasks(status, assignedTo, orderId, null);
	}

	@Transactional(readOnly = true)
	public List<TaskView> listTasks(TaskStatus status, String assignedTo, UUID orderId, String supervisorCode) {
		String worker = assignedTo == null || assignedTo.isBlank()
			? null
			: normalizeWorker(assignedTo);
		return tasks.findAllByOrderByCreatedAtDescBatchIdAscSequenceNoAsc().stream()
			.filter(task -> status == null || task.status() == status)
			.filter(task -> worker == null || worker.equals(task.assignedTo()))
			.map(this::toTaskView)
			.filter(task -> orderId == null || orderId.equals(task.orderId()))
		.filter(task -> lineScopes.canDispatch(supervisorCode, task.routeType(), task.operationCode()))
			.toList();
	}

	@Transactional(readOnly = true)
	public TaskView getTask(UUID taskId) {
		return toTaskView(requireTask(taskId));
	}

	@Transactional(readOnly = true)
	public java.util.Optional<TaskView> findTaskForScan(String taskNo) {
		return tasks.findFirstByTaskNoIgnoreCase(taskNo.trim()).map(this::toTaskView);
	}

	@Transactional(readOnly = true)
	public java.util.Optional<TaskView> findTaskForScan(UUID taskId) {
		return tasks.findById(taskId).map(this::toTaskView);
	}

	@Transactional(readOnly = true)
	public List<TaskView> findBatchTasksForScan(String batchNo) {
		return batches.findFirstByBatchNoIgnoreCase(batchNo.trim())
			.map(batch -> tasks.findByBatchIdOrderBySequenceNo(batch.id()).stream().map(this::toTaskView).toList())
			.orElseGet(List::of);
	}

	@Transactional(readOnly = true)
	public List<TaskView> findBatchTasksForScan(UUID batchId) {
		return batches.findById(batchId)
			.map(batch -> tasks.findByBatchIdOrderBySequenceNo(batch.id()).stream().map(this::toTaskView).toList())
			.orElseGet(List::of);
	}

	@Override
	@Transactional(readOnly = true)
	public TaskSnapshot get(UUID taskId) {
		return toTaskSnapshot(getTask(taskId));
	}

	@Transactional(readOnly = true)
	public List<TaskView> claimableTasks(String workerCode) {
		String worker = normalizeWorker(workerCode);
		Set<String> roles = workerRoles(worker);
		return tasks.findAllByOrderByCreatedAtDescBatchIdAscSequenceNoAsc().stream()
			.filter(task -> task.status() == TaskStatus.READY)
			.filter(task -> !"WAX_INJECTION".equals(task.operationCode()))
			.filter(this::predecessorCompleted)
			.filter(task -> !java.util.Collections.disjoint(roles, DispatchRecommendationApplication.rolesForOperation(task.operationCode())))
			.filter(task -> lineScopes.canOperate(worker, toTaskView(task).routeType()))
			.map(this::toTaskView)
			.toList();
	}

	@Transactional(readOnly = true)
	public List<TaskView> tasksForWorkOrder(UUID workOrderId) {
		List<ProductionBatchEntity> workOrderBatches = batches.findByWorkOrderIdOrderByCreatedAt(workOrderId);
		if (workOrderBatches.isEmpty()) throw DomainException.notFound("BATCH_NOT_FOUND", "生产批次不存在");
		return workOrderBatches.stream().flatMap(batch -> tasks.findByBatchIdOrderBySequenceNo(batch.id()).stream())
			.map(this::toTaskView).toList();
	}

	@Transactional(readOnly = true)
	public List<TaskView> tasksForBatch(UUID batchId) {
		if (!batches.existsById(batchId)) throw DomainException.notFound("BATCH_NOT_FOUND", "生产批次不存在");
		return tasks.findByBatchIdOrderBySequenceNo(batchId).stream().map(this::toTaskView).toList();
	}

	@Transactional(readOnly = true)
	public Optional<UpstreamTaskView> upstreamTask(UUID taskId) {
		ProductionTaskEntity receiving = requireTask(taskId);
		if (receiving.sequenceNo() > 1) {
			return tasks.findByBatchIdAndSequenceNo(receiving.batchId(), receiving.sequenceNo() - 1)
				.map(task -> new UpstreamTaskView(task.id(), task.taskNo(), task.operationCode(), task.operationName(), null));
		}
		// Only the first task of a split batch inherits its source at the release cutoff.
		return jdbc.query("""
			select source.id, source.task_no, source.operation_code, source.operation_name, release.released_at
			from partial_flow_release release
			join planning_task source on source.id = release.source_task_id
			where release.target_batch_id = ?
			""", (rs, row) -> new UpstreamTaskView(rs.getObject("id", UUID.class), rs.getString("task_no"),
				rs.getString("operation_code"), rs.getString("operation_name"), rs.getTimestamp("released_at").toInstant()),
			receiving.batchId()).stream().findFirst();
	}

	public record UpstreamTaskView(UUID taskId, String taskNo, String operationCode, String operationName, Instant reportedThrough) { }

	@Transactional
	public TaskView assign(UUID taskId, String workerCode) {
		return assign(taskId, workerCode, null, null, null, null);
	}

	@Transactional
	public TaskView assign(
		UUID taskId,
		String workerCode,
		String reportingMode,
		BigDecimal fixedQuantity,
		String settlementUnit,
		String compensationMode
	) {
		return assign(taskId, workerCode, reportingMode, fixedQuantity, settlementUnit, compensationMode, null);
	}

	@Transactional
	public TaskView assign(
		UUID taskId,
		String workerCode,
		String reportingMode,
		BigDecimal fixedQuantity,
		String settlementUnit,
		String compensationMode,
		String supervisorCode
	) {
		ProductionTaskEntity task = requireLockedTask(taskId);
		if (!lineScopes.canDispatch(supervisorCode, toTaskView(task).routeType(), task.operationCode())) {
			throw DomainException.forbidden("TASK_LINE_FORBIDDEN", "该主管无权查看或派发此生产线任务");
		}
		if (!lineScopes.canOperate(workerCode, toTaskView(task).routeType())) {
			throw DomainException.forbidden("TASK_WORKER_LINE_FORBIDDEN", "该员工不属于当前生产线，不能接收此任务");
		}
		if ("WAX_INJECTION".equals(task.operationCode())) {
			Integer issuedMolds = jdbc.queryForObject("""
				select count(*) from mold_request
				where wax_task_id = ? and status = 'ISSUED' and issued_to_worker_code = ?
				""", Integer.class, taskId, normalizeWorker(workerCode));
			if (issuedMolds == null || issuedMolds == 0) {
				throw DomainException.conflict("MOLD_NOT_ISSUED", "射蜡派工前须完成模具出库，并交接给当前射蜡工");
			}
		}
		task.assign(normalizeWorker(workerCode), reportingMode, fixedQuantity, settlementUnit, compensationMode);
		return toTaskView(task);
	}

	@Transactional
	public TaskView configureShellLineMode(UUID taskId, String shellLineMode, String supervisorCode) {
		ProductionTaskEntity task = requireLockedTask(taskId);
		TaskView taskView = toTaskView(task);
		if (!lineScopes.canDispatch(supervisorCode, taskView.routeType(), task.operationCode())) {
			throw DomainException.forbidden("SHELL_LINE_MODE_FORBIDDEN", "当前主管无权配置该制壳任务");
		}
		task.configureShellLineMode(shellLineMode);
		return toTaskView(task);
	}

	@Transactional
	public TaskView start(UUID taskId, String operatorCode) {
		ProductionTaskEntity task = requireLockedTask(taskId);
		startLocked(task, normalizeWorker(operatorCode));
		return toTaskView(task);
	}

	@Override
	@Transactional
	public TaskSnapshot assignAndStart(UUID taskId, String workerCode, String supervisorCode) {
		assign(taskId, workerCode, null, null, null, null, supervisorCode);
		return toTaskSnapshot(start(taskId, workerCode));
	}

	@Transactional
	public TaskView claim(UUID taskId, String operatorCode) {
		String worker = normalizeWorker(operatorCode);
		ProductionTaskEntity task = requireLockedTask(taskId);
		if ("WAX_INJECTION".equals(task.operationCode())) {
			throw DomainException.conflict("WAX_DISPATCH_REQUIRED", "射蜡任务需由主管领模、派工后开工");
		}
		if (task.status() == TaskStatus.READY) {
			if (!workerRoles(worker).stream().anyMatch(DispatchRecommendationApplication.rolesForOperation(task.operationCode())::contains)) {
				throw DomainException.forbidden("TASK_CLAIM_ROLE_FORBIDDEN", "当前员工不具备该工序的认领资格");
			}
			if (!lineScopes.canOperate(worker, toTaskView(task).routeType())) {
				throw DomainException.forbidden("TASK_CLAIM_LINE_FORBIDDEN", "当前员工不属于该生产线，不能认领此任务");
			}
			if (!predecessorCompleted(task)) {
				throw DomainException.conflict("TASK_PREDECESSOR_INCOMPLETE", "前序工序完成后才能认领开工");
			}
			task.assign(worker, task.reportingMode(), null, task.settlementUnit(), task.compensationMode());
		}
		if (!worker.equals(task.assignedTo())) {
			throw DomainException.conflict("TASK_CLAIMED_BY_OTHER", "该任务已由其他员工领取");
		}
		return toTaskView(task);
	}

	@Transactional
	public TaskView claimAndStart(UUID taskId, String operatorCode) {
		claim(taskId, operatorCode);
		return start(taskId, operatorCode);
	}

	@Transactional
	public TaskView lockTask(UUID taskId) {
		return toTaskView(requireLockedTask(taskId));
	}

	@Transactional
	public TaskView applyReport(
		UUID taskId,
		BigDecimal goodQuantity,
		BigDecimal scrapQuantity,
		String operatorCode
	) {
		ProductionTaskEntity task = requireLockedTask(taskId);
		task.report(goodQuantity, scrapQuantity, normalizeWorker(operatorCode), Instant.now());
		unlockSuccessorIfCompleted(task);
		return toTaskView(task);
	}

	@Transactional
	public TaskView applySupervisorReport(UUID taskId, BigDecimal goodQuantity, BigDecimal scrapQuantity) {
		ProductionTaskEntity task = requireLockedTask(taskId);
		task.reportBySupervisor(goodQuantity, scrapQuantity, Instant.now());
		unlockSuccessorIfCompleted(task);
		return toTaskView(task);
	}

	@Transactional
	public PartialFlowView releasePartialFlow(UUID sourceTaskId, BigDecimal quantity, String releasedBy, String handoffPhotoUrl) {
		ProductionTaskEntity source = requireLockedTask(sourceTaskId);
		TaskView sourceView = toTaskView(source);
		String actor = normalizeWorker(releasedBy);
		if (!actor.equals(source.assignedTo()) && !lineScopes.canDispatch(actor, sourceView.routeType(), source.operationCode())) {
			throw DomainException.forbidden("PARTIAL_FLOW_OPERATOR_FORBIDDEN", "Only the assigned worker or responsible supervisor can release a leading batch");
		}
		if (source.status() != TaskStatus.IN_PROGRESS) {
			throw DomainException.conflict("PARTIAL_FLOW_TASK_NOT_IN_PROGRESS", "Partial flow is only available while the source task is in progress");
		}
		if (quantity == null || quantity.signum() <= 0) {
			throw DomainException.badRequest("PARTIAL_FLOW_QUANTITY_INVALID", "The leading-batch quantity must be greater than zero");
		}
		WorkOrderEntity workOrder = workOrderFor(source);
		List<RouteCatalog.RouteOperation> operations = routes.operationsFor(workOrder.routeType());
		int sourceOperationIndex = operationIndex(operations, source.operationCode());
		if (sourceOperationIndex < 0 || sourceOperationIndex >= operations.size() - 1) {
			throw DomainException.conflict("PARTIAL_FLOW_NO_SUCCESSOR", "The final operation cannot be released to a leading batch");
		}
		BigDecimal availableQuantity = source.goodQuantity().subtract(releasedQuantity(source.id()));
		if (quantity.compareTo(availableQuantity) > 0) {
			throw DomainException.conflict("PARTIAL_FLOW_QUANTITY_EXCEEDED", "The quantity exceeds qualified output not yet released to the next operation");
		}

		Instant now = Instant.now();
		ProductionBatchEntity sourceBatch = batches.findById(source.batchId())
			.orElseThrow(() -> DomainException.notFound("PRODUCTION_BATCH_NOT_FOUND", "生产批次不存在"));
		ProductionBatchEntity leadingBatch = batches.save(new ProductionBatchEntity(
			UUID.randomUUID(), identifier("PB-L"), workOrder.id(), quantity, BatchStatus.READY, "FLOW_SPLIT", sourceBatch.id(), now));
		for (int index = sourceOperationIndex + 1; index < operations.size(); index++) {
			RouteCatalog.RouteOperation operation = operations.get(index);
			tasks.save(new ProductionTaskEntity(UUID.randomUUID(), identifier("TK"), leadingBatch.id(), index - sourceOperationIndex,
				operation.code(), operation.name(), quantity, BigDecimal.ZERO, BigDecimal.ZERO,
				index == sourceOperationIndex + 1 ? TaskStatus.READY : TaskStatus.BLOCKED, now));
		}
		batches.flush();
		tasks.flush();
		jdbc.update("""
			insert into partial_flow_release (id, source_task_id, target_batch_id, quantity, released_by, released_at, handoff_photo_url)
			values (?, ?, ?, ?, ?, ?, ?)
			""", UUID.randomUUID(), source.id(), leadingBatch.id(), quantity, actor, java.sql.Timestamp.from(now), handoffPhotoUrl);
		ProductionTaskEntity firstTask = tasks.findLockedByBatchAndSequence(leadingBatch.id(), 1)
			.orElseThrow(() -> DomainException.conflict("PARTIAL_FLOW_TASK_CREATE_FAILED", "The leading task could not be created"));
		jdbc.query("""
			select distinct mr.employee_code from organization_member_role mr
			join organization_member m on m.employee_code = mr.employee_code
			where mr.role_code in ('PRODUCTION_MANAGER', 'WORKSHOP_SUPERVISOR') and m.active = true
			""", (rs, row) -> rs.getString("employee_code")).stream()
			.filter(supervisor -> lineScopes.canDispatch(supervisor, sourceView.routeType(), source.operationCode()))
			.forEach(supervisor -> notifications.create(new NotificationApplication.CreateCommand(supervisor, "WORKFLOW",
				"Partial flow card to complete: " + sourceView.taskNo(),
				"Qualified quantity " + quantity.stripTrailingZeros().toPlainString() + " created leading batch " + leadingBatch.batchNo()
					+ ". Print or reprint the flow card before physical handoff.",
				"/documents?documentType=FLOW_CARD&taskId=" + firstTask.id())));
		return new PartialFlowView(source.id(), leadingBatch.id(), leadingBatch.batchNo(), quantity, toTaskView(firstTask), now);
	}

	@Transactional
	public TaskView recordCompletedWeight(UUID taskId, BigDecimal weightKg) {
		ProductionTaskEntity task = requireLockedTask(taskId);
		task.recordCompletedWeight(weightKg);
		return toTaskView(task);
	}

	@Transactional
	public TaskView handoffWithoutCount(UUID taskId, String operatorCode) {
		return handoffWithoutCount(taskId, operatorCode, null);
	}

	@Transactional
	public TaskView handoffWithoutCount(UUID taskId, String operatorCode, BigDecimal receivedQuantity) {
		ProductionTaskEntity task = requireLockedTask(taskId);
		task.handoffWithoutCount(normalizeWorker(operatorCode), receivedQuantity, Instant.now());
		unlockSuccessorIfCompleted(task);
		return toTaskView(task);
	}

	@Transactional
	public TaskView applyTreeReport(
		UUID taskId,
		BigDecimal treeCount,
		BigDecimal piecesPerTree,
		BigDecimal scrapQuantity,
		String operatorCode
	) {
		ProductionTaskEntity task = requireLockedTask(taskId);
		task.reportTree(treeCount, piecesPerTree, scrapQuantity, normalizeWorker(operatorCode), Instant.now());
		unlockSuccessorIfCompleted(task);
		return toTaskView(task);
	}

	private List<WorkOrderView> workOrderViews(WorkOrderEntity workOrder) {
		return batches.findByWorkOrderIdOrderByCreatedAt(workOrder.id()).stream()
			.map(batch -> toWorkOrderView(workOrder, batch)).toList();
	}

	private WorkOrderView toWorkOrderView(WorkOrderEntity workOrder, ProductionBatchEntity batch) {
		BigDecimal orderQuantity = customerOrders.getOrder(workOrder.orderId()).lines().stream()
			.filter(line -> line.id().equals(workOrder.orderLineId())).findFirst()
			.map(OrderLineView::orderedQuantity).orElse(workOrder.plannedQuantity());
		List<ProductionTaskEntity> batchTasks = tasks.findByBatchIdOrderBySequenceNo(batch.id());
		return toWorkOrderView(workOrder, batch, orderQuantity, batchTasks);
	}

	private WorkOrderView toWorkOrderView(WorkOrderEntity workOrder, ProductionBatchEntity batch,
			BigDecimal orderQuantity, List<ProductionTaskEntity> batchTasks) {
		long taskCount = batchTasks.size();
		long completedTaskCount = batchTasks.stream().filter(task -> task.status() == TaskStatus.COMPLETED).count();
		ProductionTaskEntity currentTask = batchTasks.stream()
			.filter(task -> task.status() != TaskStatus.COMPLETED).findFirst().orElse(null);
		return new WorkOrderView(
			workOrder.id(),
			workOrder.workOrderNo(),
			workOrder.orderId(),
			workOrder.orderLineId(),
			workOrder.productId(),
			workOrder.productCode(),
			workOrder.productName(),
			workOrder.productMaterial(),
			workOrder.routeType(),
			workOrder.routeVersion(),
			orderQuantity,
			batch.plannedQuantity(),
			workOrder.status(),
			batch.id(),
			batch.batchNo(),
			batch.batchType(),
			batch.status().name(),
			taskCount,
			completedTaskCount,
			currentTask == null ? null : currentTask.operationName(),
			currentTask == null ? null : currentTask.status().name(),
			workOrder.createdAt()
		);
	}

	private void createBatch(WorkOrderEntity workOrder, BigDecimal quantity, Instant createdAt) {
		ProductionBatchEntity batch = batches.save(new ProductionBatchEntity(
			UUID.randomUUID(), identifier("PB"), workOrder.id(), quantity, BatchStatus.PENDING_LAUNCH, createdAt));
		List<RouteCatalog.RouteOperation> operations = routes.operationsFor(workOrder.routeType());
		for (int index = 0; index < operations.size(); index++) {
			RouteCatalog.RouteOperation operation = operations.get(index);
			tasks.save(new ProductionTaskEntity(UUID.randomUUID(), identifier("TK"), batch.id(), index + 1,
				operation.code(), operation.name(), quantity, BigDecimal.ZERO, BigDecimal.ZERO,
				TaskStatus.BLOCKED, createdAt));
		}
	}

	private TaskView toTaskView(ProductionTaskEntity task) {
		ProductionBatchEntity batch = batches.findById(task.batchId())
			.orElseThrow(() -> DomainException.notFound("BATCH_NOT_FOUND", "生产批次不存在"));
		WorkOrderEntity workOrder = workOrders.findById(batch.workOrderId())
			.orElseThrow(() -> DomainException.notFound("WORK_ORDER_NOT_FOUND", "工单不存在"));
		return toTaskView(task, batch, workOrder);
	}

	private TaskView toTaskView(ProductionTaskEntity task, ProductionBatchEntity batch, WorkOrderEntity workOrder) {
		return new TaskView(
			task.id(),
			task.taskNo(),
			workOrder.id(),
			workOrder.workOrderNo(),
		workOrder.orderId(),
		workOrder.orderLineId(),
		workOrder.productCode(),
		workOrder.productName(),
		workOrder.productMaterial(),
		workOrder.routeType(),
			batch.id(),
			batch.batchNo(),
			task.sequenceNo(),
			task.operationCode(),
			task.operationName(),
			task.shellLineMode(),
			task.plannedQuantity(),
			task.goodQuantity(),
			task.scrapQuantity(),
			task.status(),
			task.assignedTo(),
			task.reportingMode(),
			task.assignedQuantity(),
			task.settlementUnit(),
			task.countingDeferred(),
			task.treeCount(),
			task.piecesPerTree(),
			task.compensationMode(),
			task.completedWeightKg(),
			task.startedAt(),
			task.completedAt(),
			task.createdAt()
		);
	}

	private static TaskSnapshot toTaskSnapshot(TaskView task) {
		return new TaskSnapshot(
			task.id(), task.taskNo(), task.workOrderId(), task.workOrderNo(), task.orderId(), task.orderLineId(),
			task.productCode(), task.productName(), task.productMaterial(), task.routeType().name(), task.batchId(),
			task.batchNo(), task.sequenceNo(), task.operationCode(), task.operationName(), task.plannedQuantity(),
			task.goodQuantity(), task.scrapQuantity(), task.status().name(), task.assignedTo(), task.reportingMode(),
			task.assignedQuantity(), task.settlementUnit(), task.countingDeferred(), task.treeCount(), task.piecesPerTree(),
			task.compensationMode(), task.completedWeightKg(), task.startedAt(), task.completedAt(), task.createdAt()
		);
	}

	private ProductionTaskEntity requireTask(UUID taskId) {
		return tasks.findById(taskId)
			.orElseThrow(() -> DomainException.notFound("TASK_NOT_FOUND", "生产任务不存在"));
	}

	private ProductionTaskEntity requireLockedTask(UUID taskId) {
		return tasks.findLockedById(taskId)
			.orElseThrow(() -> DomainException.notFound("TASK_NOT_FOUND", "生产任务不存在"));
	}

	private WorkOrderEntity workOrderFor(ProductionTaskEntity task) {
		ProductionBatchEntity batch = batches.findById(task.batchId())
			.orElseThrow(() -> DomainException.notFound("BATCH_NOT_FOUND", "Production batch not found"));
		return workOrders.findById(batch.workOrderId())
			.orElseThrow(() -> DomainException.notFound("WORK_ORDER_NOT_FOUND", "Work order not found"));
	}

	private void startLocked(ProductionTaskEntity task, String worker) {
		if (task.sequenceNo() > 1) {
			ProductionTaskEntity predecessor = tasks.findLockedByBatchAndSequence(task.batchId(), task.sequenceNo() - 1)
				.orElseThrow(() -> DomainException.conflict("TASK_PREDECESSOR_MISSING", "前置工序不存在"));
			if (predecessor.status() != TaskStatus.COMPLETED) {
				throw DomainException.conflict("TASK_PREDECESSOR_INCOMPLETE", "前序工序完成后才能开工");
			}
			task.alignPlannedQuantity(predecessor.goodQuantity());
		}
		task.start(worker, Instant.now());
	}

	private void unlockSuccessorIfCompleted(ProductionTaskEntity task) {
		if (task.status() != TaskStatus.COMPLETED) {
			return;
		}
		tasks.flush();
		refreshShortageAlert(toTaskView(task).workOrderId());
		BigDecimal transferable = transferableQuantity(task);
		if (transferable.signum() <= 0) {
			return;
		}
		if ("SEMI_FINISHED_COUNT".equals(task.operationCode()) && isDirectFinishedOrDefault(task)) {
			tasks.findLockedByBatchAndSequence(task.batchId(), task.sequenceNo() + 1)
				.filter(candidate -> "OPTIONAL_FINISHING".equals(candidate.operationCode()))
				.ifPresent(optional -> {
					optional.skipForDirectFinished(transferable, Instant.now());
					unlockSuccessorIfCompleted(optional);
				});
			return;
		}
		tasks.findLockedByBatchAndSequence(task.batchId(), task.sequenceNo() + 1)
			.filter(successor -> successor.status() == TaskStatus.BLOCKED)
			.ifPresent(successor -> successor.unlockForDispatch(transferable));
	}

	private BigDecimal transferableQuantity(ProductionTaskEntity task) {
		return task.goodQuantity().subtract(releasedQuantity(task.id()));
	}

	private int operationIndex(List<RouteCatalog.RouteOperation> operations, String operationCode) {
		for (int index = 0; index < operations.size(); index++) {
			if (operations.get(index).code().equals(operationCode)) {
				return index;
			}
		}
		return -1;
	}

	private BigDecimal releasedQuantity(UUID sourceTaskId) {
		BigDecimal total = jdbc.queryForObject("select coalesce(sum(quantity), 0) from partial_flow_release where source_task_id = ?",
			BigDecimal.class, sourceTaskId);
		return total == null ? BigDecimal.ZERO : total;
	}

	private void refreshShortageAlert(UUID workOrderId) {
		try {
			shortageAlerts.refreshForWorkOrder(workOrderId);
		} catch (RuntimeException exception) {
			log.error("Production shortage alert refresh failed for work order {}", workOrderId, exception);
		}
	}

	private boolean isDirectFinishedOrDefault(ProductionTaskEntity task) {
		List<String> destinations = jdbc.query("select destination from post_treatment_decision where batch_id = ?", (rs, row) -> rs.getString(1), task.batchId());
		if (!destinations.isEmpty()) return "DIRECT_FINISHED".equals(destinations.getFirst()) || "FINISHED_GOODS_STORAGE".equals(destinations.getFirst());
		jdbc.update("""
			insert into post_treatment_decision (id, batch_id, source_task_id, destination, process_summary, supplier_id, outsourcing_order_id, note, decided_by, decided_at)
			values (?, ?, ?, 'DIRECT_FINISHED', null, null, null, ?, 'SYSTEM', ?)
			""", UUID.randomUUID(), task.batchId(), task.id(), "未选择具体后处理，系统默认直入成品清点/入库待发货", java.sql.Timestamp.from(Instant.now()));
		return true;
	}

	private boolean predecessorCompleted(ProductionTaskEntity task) {
		return task.sequenceNo() == 1 || tasks.findByBatchIdOrderBySequenceNo(task.batchId()).stream()
			.anyMatch(candidate -> candidate.sequenceNo() == task.sequenceNo() - 1 && candidate.status() == TaskStatus.COMPLETED);
	}

	private Set<String> workerRoles(String workerCode) {
		return Set.copyOf(jdbc.query("select role_code from organization_member_role where employee_code = ?",
			(rs, rowNum) -> rs.getString("role_code"), workerCode));
	}

	private static String normalizeWorker(String workerCode) {
		return workerCode.trim().toUpperCase(Locale.ROOT);
	}

	private static String identifier(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

	public record WorkOrderView(
		UUID id,
		String workOrderNo,
		UUID orderId,
		UUID orderLineId,
		UUID productId,
		String productCode,
		String productName,
		String productMaterial,
		RouteType routeType,
		String routeVersion,
		BigDecimal orderQuantity,
		BigDecimal plannedQuantity,
		WorkOrderStatus status,
		UUID batchId,
		String batchNo,
		String batchType,
		String batchStatus,
		long taskCount,
		long completedTaskCount,
		String currentOperationName,
		String currentTaskStatus,
		Instant createdAt
	) {
	}

	public record TaskView(
		UUID id,
		String taskNo,
		UUID workOrderId,
		String workOrderNo,
		UUID orderId,
		UUID orderLineId,
		String productCode,
		String productName,
		String productMaterial,
		RouteType routeType,
		UUID batchId,
		String batchNo,
		int sequenceNo,
		String operationCode,
		String operationName,
		String shellLineMode,
		BigDecimal plannedQuantity,
		BigDecimal goodQuantity,
		BigDecimal scrapQuantity,
		TaskStatus status,
		String assignedTo,
		String reportingMode,
		BigDecimal assignedQuantity,
		String settlementUnit,
		boolean countingDeferred,
		BigDecimal treeCount,
		BigDecimal piecesPerTree,
		String compensationMode,
		BigDecimal completedWeightKg,
		Instant startedAt,
		Instant completedAt,
		Instant createdAt
	) {
	}

	public record PartialFlowView(
		UUID sourceTaskId,
		UUID targetBatchId,
		String targetBatchNo,
		BigDecimal quantity,
		TaskView firstTask,
		Instant releasedAt
	) {
	}
}
