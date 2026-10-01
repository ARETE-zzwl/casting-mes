package com.renyi.mes.planning.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.renyi.mes.planning.PlanningApplication;
import com.renyi.mes.planning.PlanningApplication.PartialFlowView;
import com.renyi.mes.planning.PlanningApplication.TaskView;
import com.renyi.mes.planning.PlanningApplication.WorkOrderView;
import com.renyi.mes.planning.TaskStatus;
import com.renyi.mes.engineering.RouteType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class ProductionController {

	private final PlanningApplication planning;
	private final com.renyi.mes.planning.WaxDispatchApplication waxDispatch;
	private final com.renyi.mes.planning.DispatchRecommendationApplication recommendations;
	private final com.renyi.mes.common.BusinessAccess access;

	ProductionController(PlanningApplication planning,
			com.renyi.mes.planning.WaxDispatchApplication waxDispatch,
			com.renyi.mes.planning.DispatchRecommendationApplication recommendations,
			com.renyi.mes.common.BusinessAccess access) {
		this.planning = planning;
		this.waxDispatch = waxDispatch;
		this.recommendations = recommendations;
		this.access = access;
	}

	@PostMapping("/orders/{orderId}/release")
	List<WorkOrderView> releaseOrder(@PathVariable UUID orderId) {
		return planning.releaseOrder(orderId);
	}

	@GetMapping("/work-orders")
	List<WorkOrderView> listWorkOrders(@RequestParam(required = false) UUID orderId,
			@RequestParam(required = false) RouteType routeType) {
		return (orderId == null
			? planning.listWorkOrders(routeType)
			: planning.workOrdersForOrder(orderId)).stream().filter(order -> access.canReadOrder(order.orderId())).toList();
	}

	@PostMapping("/work-orders/{workOrderId}/batches")
	List<WorkOrderView> configureBatches(@PathVariable UUID workOrderId, @Valid @RequestBody BatchConfigurationRequest request) {
		return planning.configureBatches(workOrderId, request.quantities(), request.supervisorCode());
	}

	@PostMapping("/work-orders/{workOrderId}/production-quantity")
	List<WorkOrderView> applyInitialProductionQuantity(@PathVariable UUID workOrderId,
			@Valid @RequestBody ProductionQuantityRequest request) {
		return planning.applyInitialProductionQuantity(workOrderId, request.productionQuantity(), request.supervisorCode());
	}

	@PostMapping("/batches/{batchId}/launch")
	WorkOrderView launchBatch(@PathVariable UUID batchId, @Valid @RequestBody ProductionQuantityRequest request) {
		return planning.launchBatch(batchId, request.productionQuantity(), request.supervisorCode());
	}

	@GetMapping("/tasks")
	List<TaskView> listTasks(
		@RequestParam(required = false) TaskStatus status,
		@RequestParam(required = false) String assignedTo,
		@RequestParam(required = false) UUID orderId,
		@RequestParam(required = false) String supervisorCode
	) {
		String supervisor = access.secured() && !access.permission("TASK_DISPATCH") ? null : supervisorCode;
		return planning.listTasks(status, assignedTo, orderId, supervisor).stream()
			.filter(task -> access.canReadTask(task.id())).toList();
	}

	@GetMapping("/tasks/{taskId}")
	TaskView getTask(@PathVariable UUID taskId) {
		return planning.getTask(taskId);
	}

	@GetMapping("/tasks/claimable")
	List<TaskView> claimable(@RequestParam String workerCode) {
		return planning.claimableTasks(workerCode);
	}

	@GetMapping("/planning/dispatch-recommendations")
	com.renyi.mes.planning.DispatchRecommendationApplication.DispatchRecommendationView recommendations(
			@RequestParam(required = false) String operationCode,
			@RequestParam(required = false) String supervisorCode,
			@RequestParam(required = false) RouteType routeType) {
		return recommendations.recommendations(operationCode, access.secured() ? access.actor() : supervisorCode, routeType);
	}

	@PostMapping("/tasks/{taskId}/assignment")
	TaskView assign(@PathVariable UUID taskId, @Valid @RequestBody AssignmentRequest request) {
		return planning.assign(
			taskId,
			request.workerCode(),
			request.reportingMode(),
			request.fixedQuantity(),
			request.settlementUnit(),
			request.compensationMode(),
			request.supervisorCode()
		);
	}

	@PostMapping("/tasks/{taskId}/shell-line")
	TaskView configureShellLine(@PathVariable UUID taskId, @Valid @RequestBody ShellLineRequest request) {
		return planning.configureShellLineMode(taskId, request.shellLineMode(), request.supervisorCode());
	}

	@PostMapping("/tasks/{taskId}/wax-dispatch")
	TaskView dispatchWaxWithMold(@PathVariable UUID taskId, @Valid @RequestBody WaxDispatchRequest request) {
		return waxDispatch.dispatch(taskId, new com.renyi.mes.planning.WaxDispatchApplication.Command(
			request.moldAssetId(), request.warehouseCode(), request.warehouseOperatorCode(), request.workerCode(),
			request.reportingMode(), request.fixedQuantity(), request.settlementUnit(), request.compensationMode(),
			request.productionQuantity(),
			request.supervisorCode()));
	}

	@PostMapping("/tasks/{taskId}/mold-return")
	TaskView returnWaxMold(@PathVariable UUID taskId, @Valid @RequestBody SupervisorRequest request) {
		return waxDispatch.returnMold(taskId, request.supervisorCode());
	}

	@PostMapping("/tasks/{taskId}/start")
	TaskView start(
		@PathVariable UUID taskId,
		@RequestHeader("X-Operator-Code") @NotBlank @Size(max = 64) String operatorCode
	) {
		return planning.start(taskId, operatorCode);
	}

	@PostMapping("/tasks/{taskId}/claim-and-start")
	TaskView claimAndStart(
		@PathVariable UUID taskId,
		@RequestHeader("X-Operator-Code") @NotBlank @Size(max = 64) String operatorCode
	) {
		return planning.claimAndStart(taskId, operatorCode);
	}

	@PostMapping("/tasks/{taskId}/claim")
	TaskView claim(
		@PathVariable UUID taskId,
		@RequestHeader("X-Operator-Code") @NotBlank @Size(max = 64) String operatorCode
	) {
		return planning.claim(taskId, operatorCode);
	}

	@PostMapping("/tasks/{taskId}/partial-flow")
	PartialFlowView releasePartialFlow(@PathVariable UUID taskId, @Valid @RequestBody PartialFlowRequest request) {
		return planning.releasePartialFlow(taskId, request.quantity(), request.releasedBy(), request.handoffPhotoUrl());
	}

	record AssignmentRequest(
		@NotBlank @Size(max = 64) String workerCode,
		@Size(max = 32) String reportingMode,
		@DecimalMin("0.001") BigDecimal fixedQuantity,
		@Size(max = 16) String settlementUnit,
		@Size(max = 16) String compensationMode,
		@Size(max = 64) String supervisorCode
	) {
	}

	record ShellLineRequest(@NotBlank @Size(max = 16) String shellLineMode,
			@NotBlank @Size(max = 64) String supervisorCode) { }

	record BatchConfigurationRequest(@NotEmpty List<@NotNull @DecimalMin("0.001") BigDecimal> quantities,
			@NotBlank @Size(max = 64) String supervisorCode) { }

	record ProductionQuantityRequest(@NotNull @DecimalMin("0.001") BigDecimal productionQuantity,
			@NotBlank @Size(max = 64) String supervisorCode) { }

	record PartialFlowRequest(@NotNull @DecimalMin("0.001") BigDecimal quantity,
			@NotBlank @Size(max = 64) String releasedBy,
			@Size(max = 1000) String handoffPhotoUrl) { }

	record SupervisorRequest(@NotBlank @Size(max = 64) String supervisorCode) { }

	record WaxDispatchRequest(
		UUID moldAssetId,
		@NotBlank @Size(max = 64) String warehouseCode,
		@NotBlank @Size(max = 64) String warehouseOperatorCode,
		@NotBlank @Size(max = 64) String workerCode,
		@Size(max = 32) String reportingMode,
		@DecimalMin("0.001") BigDecimal fixedQuantity,
		@Size(max = 16) String settlementUnit,
		@Size(max = 16) String compensationMode,
		BigDecimal productionQuantity,
		@NotBlank @Size(max = 64) String supervisorCode
	) {
	}
}
