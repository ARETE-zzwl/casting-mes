package com.renyi.mes.execution.web;

import java.math.BigDecimal;
import java.util.UUID;

import com.renyi.mes.execution.ExecutionApplication;
import com.renyi.mes.execution.ExecutionApplication.ReportResult;
import com.renyi.mes.execution.ExecutionApplication.SupervisorReportCommand;
import com.renyi.mes.planning.PlanningApplication;
import com.renyi.mes.planning.PlanningApplication.TaskView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks/{taskId}")
class TaskSupervisorController {

	private final ExecutionApplication execution;
	private final PlanningApplication planning;

	TaskSupervisorController(ExecutionApplication execution, PlanningApplication planning) {
		this.execution = execution;
		this.planning = planning;
	}

	@PostMapping("/supervisor-reports")
	ResponseEntity<ReportResult> report(
		@PathVariable UUID taskId,
		@Valid @RequestBody SupervisorReportRequest request
	) {
		ReportResult result = execution.reportBySupervisor(new SupervisorReportCommand(
			request.operationId(), taskId, request.goodQuantity(), request.scrapQuantity(), request.supervisorCode(), request.deviceCode(), request.workstationCode(), request.photoUrl()));
		return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
	}

	@PostMapping("/weights")
	TaskView recordWeight(@PathVariable UUID taskId, @Valid @RequestBody WeightRequest request) {
		return planning.recordCompletedWeight(taskId, request.weightKg());
	}

	record SupervisorReportRequest(
		@NotNull UUID operationId,
		@NotNull @DecimalMin("0.000") BigDecimal goodQuantity,
		@NotNull @DecimalMin("0.000") BigDecimal scrapQuantity,
		@NotBlank @Size(max = 64) String supervisorCode,
		@Size(max = 64) String deviceCode,
		@Size(max = 64) String workstationCode,
		@Size(max = 1000) String photoUrl
	) {
	}

	record WeightRequest(@NotNull @DecimalMin("0.001") BigDecimal weightKg) {
	}
}
