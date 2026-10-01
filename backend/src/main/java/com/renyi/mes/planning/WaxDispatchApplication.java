package com.renyi.mes.planning;

import java.math.BigDecimal;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.MoldTaskPort;
import com.renyi.mes.common.MoldTaskPort.MoldRequestSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaxDispatchApplication {

	private final PlanningApplication planning;
	private final MoldTaskPort molds;
	private final ProductionLineScopeApplication lineScopes;

	public WaxDispatchApplication(PlanningApplication planning, MoldTaskPort molds, ProductionLineScopeApplication lineScopes) {
		this.planning = planning;
		this.molds = molds;
		this.lineScopes = lineScopes;
	}

	@Transactional
	public PlanningApplication.TaskView dispatch(UUID taskId, Command command) {
		PlanningApplication.TaskView task = planning.getTask(taskId);
		if (!"WAX_INJECTION".equals(task.operationCode())) {
			throw DomainException.badRequest("WAX_TASK_REQUIRED", "一键模具出库仅适用于射蜡任务");
		}
		if (!lineScopes.canOperate(command.workerCode(), task.routeType())) {
			throw DomainException.forbidden("TASK_WORKER_LINE_FORBIDDEN", "该员工不属于当前生产线，不能接收此射蜡任务");
		}
		if (command.productionQuantity() != null) {
			throw DomainException.badRequest("PRODUCTION_QUANTITY_LAUNCH_REQUIRED",
				"Production quantity must be confirmed when launching the batch, before dispatch");
		}
		MoldRequestSnapshot request = molds.findTaskMold(taskId);
		if (request == null) {
			request = molds.requestTaskMold(taskId, command.moldAssetId(), command.supervisorCode(), command.workerCode());
		}
		if ("APPROVED".equals(request.status()) || "RETURNED".equals(request.status())) {
			molds.issueTaskMold(request.id(), taskId, command.warehouseCode(), command.warehouseOperatorCode(), command.workerCode());
		} else if ("ISSUED".equals(request.status()) && !taskId.equals(request.waxTaskId())) {
			throw DomainException.conflict("MOLD_STILL_IN_USE", "前一生产批次正在使用该模具，请先归还模具后再派发下一批");
		} else if (!"ISSUED".equals(request.status())) {
			throw DomainException.conflict("MOLD_REQUEST_STATE_CONFLICT", "当前模具领用状态不能一键出库派工");
		}
		return planning.assign(taskId, command.workerCode(), command.reportingMode(), command.fixedQuantity(),
			command.settlementUnit(), command.compensationMode(), command.supervisorCode());
	}

	@Transactional
	public PlanningApplication.TaskView returnMold(UUID taskId, String supervisorCode) {
		PlanningApplication.TaskView task = planning.getTask(taskId);
		if (!"WAX_INJECTION".equals(task.operationCode())) {
			throw DomainException.badRequest("WAX_TASK_REQUIRED", "只有射蜡任务可归还领用模具");
		}
		if (!lineScopes.canDispatch(supervisorCode, task.routeType(), task.operationCode())) {
			throw DomainException.forbidden("TASK_LINE_FORBIDDEN", "当前主管无权归还该生产线模具");
		}
		if (task.status() != TaskStatus.COMPLETED) {
			throw DomainException.conflict("WAX_TASK_NOT_COMPLETED", "射蜡任务完成并核对后才能归还模具");
		}
		MoldRequestSnapshot request = molds.findTaskMold(taskId);
		if (request == null || !"ISSUED".equals(request.status())) {
			throw DomainException.conflict("MOLD_RETURN_NOT_REQUIRED", "该射蜡任务没有待归还的已领用模具");
		}
		molds.returnTaskMold(request.id(), supervisorCode);
		return planning.getTask(taskId);
	}

	public record Command(
		UUID moldAssetId,
		String warehouseCode,
		String warehouseOperatorCode,
		String workerCode,
		String reportingMode,
		BigDecimal fixedQuantity,
		String settlementUnit,
		String compensationMode,
		BigDecimal productionQuantity,
		String supervisorCode
	) {
	}
}
