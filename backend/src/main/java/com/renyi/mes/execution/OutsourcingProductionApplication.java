package com.renyi.mes.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.OutsourcingProductionPort;
import com.renyi.mes.common.ProductionTaskPort.TaskSnapshot;
import com.renyi.mes.engineering.RouteType;
import com.renyi.mes.planning.PlanningApplication;
import com.renyi.mes.planning.PlanningApplication.TaskView;
import com.renyi.mes.planning.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OutsourcingProductionApplication implements OutsourcingProductionPort {

	private final PlanningApplication planning;
	private final ExecutionApplication execution;

	OutsourcingProductionApplication(PlanningApplication planning, ExecutionApplication execution) {
		this.planning = planning;
		this.execution = execution;
	}

	@Override
	@Transactional(readOnly = true)
	public List<TaskSnapshot> readyForDispatch(String operatorCode) {
		return planning.listTasks(TaskStatus.READY, null, null, operatorCode).stream()
			.filter(task -> task.routeType() == RouteType.SAND_OUTSOURCE)
			.filter(task -> "OUTSOURCE_DISPATCH".equals(task.operationCode()))
			.map(this::snapshot)
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public TaskSnapshot getLinkedTask(UUID dispatchTaskId) {
		TaskView task = planning.getTask(dispatchTaskId);
		requireSandDispatch(task);
		return snapshot(task);
	}

	@Override
	@Transactional
	public TaskSnapshot startOperation(UUID dispatchTaskId, String operationCode, String operatorCode) {
		TaskView task = taskForOperation(dispatchTaskId, operationCode);
		String operator = normalize(operatorCode);
		if (task.status() == TaskStatus.IN_PROGRESS || task.status() == TaskStatus.COMPLETED) return snapshot(task);
		if (task.status() == TaskStatus.READY) {
			planning.assign(task.id(), operator, null, null, null, null, operator);
			task = planning.start(task.id(), operator);
		} else if (task.status() == TaskStatus.ASSIGNED) {
			if (!operator.equals(task.assignedTo())) {
				throw DomainException.conflict("OUTSOURCING_TASK_ASSIGNED_TO_OTHER", "关联外协工序已由其他人员接收");
			}
			task = planning.start(task.id(), operator);
		}
		if (task.status() != TaskStatus.IN_PROGRESS) {
			throw DomainException.conflict("OUTSOURCING_TASK_STATE_INVALID", "关联外协工序当前不能开工");
		}
		return snapshot(task);
	}

	@Override
	@Transactional
	public TaskSnapshot completeOperation(UUID dispatchTaskId, String operationCode, String operatorCode) {
		TaskView task = taskForOperation(dispatchTaskId, operationCode);
		if (task.status() == TaskStatus.COMPLETED) return snapshot(task);
		if (task.status() != TaskStatus.IN_PROGRESS) {
			startOperation(dispatchTaskId, operationCode, operatorCode);
			task = planning.getTask(task.id());
		}
		if (task.status() != TaskStatus.IN_PROGRESS) {
			throw DomainException.conflict("OUTSOURCING_TASK_STATE_INVALID", "关联外协工序当前不能报工");
		}
		execution.reportBySupervisor(new ExecutionApplication.SupervisorReportCommand(
			UUID.randomUUID(), task.id(), task.plannedQuantity(), BigDecimal.ZERO, normalize(operatorCode), null, null, null));
		return snapshot(planning.getTask(task.id()));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isOperationCompleted(UUID dispatchTaskId, String operationCode) {
		return taskForOperation(dispatchTaskId, operationCode).status() == TaskStatus.COMPLETED;
	}

	private TaskView taskForOperation(UUID dispatchTaskId, String operationCode) {
		TaskView dispatch = planning.getTask(dispatchTaskId);
		requireSandDispatch(dispatch);
		return planning.tasksForBatch(dispatch.batchId()).stream()
			.filter(task -> operationCode.equals(task.operationCode()))
			.findFirst()
			.orElseThrow(() -> DomainException.conflict("OUTSOURCING_TASK_MISSING", "关联砂型工序不存在"));
	}

	private static void requireSandDispatch(TaskView task) {
		if (task.routeType() != RouteType.SAND_OUTSOURCE || !"OUTSOURCE_DISPATCH".equals(task.operationCode())) {
			throw DomainException.badRequest("OUTSOURCING_TASK_INVALID", "仅可关联待发出的砂型外协生产任务");
		}
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private TaskSnapshot snapshot(TaskView task) {
		return new TaskSnapshot(task.id(), task.taskNo(), task.workOrderId(), task.workOrderNo(), task.orderId(), task.orderLineId(),
			task.productCode(), task.productName(), task.productMaterial(), task.routeType().name(), task.batchId(), task.batchNo(),
			task.sequenceNo(), task.operationCode(), task.operationName(), task.plannedQuantity(), task.goodQuantity(), task.scrapQuantity(),
			task.status().name(), task.assignedTo(), task.reportingMode(), task.assignedQuantity(), task.settlementUnit(), task.countingDeferred(),
			task.treeCount(), task.piecesPerTree(), task.compensationMode(), task.completedWeightKg(), task.startedAt(), task.completedAt(), task.createdAt());
	}
}
