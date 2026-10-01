package com.renyi.mes.execution.web;

import java.math.BigDecimal;
import java.util.UUID;

import com.renyi.mes.execution.ExecutionApplication;
import com.renyi.mes.execution.HandoffApplication;
import com.renyi.mes.execution.HandoffApplication.HandoffCommand;
import com.renyi.mes.execution.HandoffApplication.HandoffView;
import com.renyi.mes.execution.HandoffApplication.ReceiptCommand;
import com.renyi.mes.execution.HandoffApplication.ReceiptView;
import com.renyi.mes.execution.ExecutionApplication.TreeReportCommand;
import com.renyi.mes.execution.ExecutionApplication.TreeReportResult;
import com.renyi.mes.planning.PlanningApplication.TaskView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks/{taskId}")
class TaskCountingController {

	private final ExecutionApplication execution;
	private final HandoffApplication handoffs;

	TaskCountingController(ExecutionApplication execution, HandoffApplication handoffs) {
		this.execution = execution;
		this.handoffs = handoffs;
	}

	@PostMapping("/handoff-without-count")
	HandoffView handoffWithoutCount(
		@PathVariable UUID taskId,
		@RequestHeader("X-Operator-Code") @NotBlank @Size(max = 64) String operatorCode,
		@Valid @RequestBody(required = false) HandoffRequest request
	) {
		return handoffs.handoff(new HandoffCommand(
			taskId,
			request == null ? null : request.receivedQuantity(),
			request == null ? null : request.exceptionReason(),
			operatorCode,
			request == null ? null : request.receivedBy()
		));
	}

	@GetMapping("/handoff-receipt")
	ReceiptView handoffReceipt(@PathVariable UUID taskId) {
		return handoffs.receipt(taskId);
	}

	@PostMapping("/handoff-receipt")
	ReceiptView acceptHandoffReceipt(
		@PathVariable UUID taskId,
		@RequestHeader("X-Operator-Code") @NotBlank @Size(max = 64) String operatorCode,
		@Valid @RequestBody ReceiptRequest request
	) {
		return handoffs.acceptReceipt(new ReceiptCommand(taskId, request.receivedQuantity(), request.exceptionType(),
			request.exceptionReason(), request.photoUrl(), operatorCode, request.deviceCode(), request.workstationCode()));
	}

	@PostMapping("/tree-reports")
	ResponseEntity<TreeReportResult> reportTree(
		@PathVariable UUID taskId,
		@RequestHeader("X-Operator-Code") @NotBlank @Size(max = 64) String operatorCode,
		@Valid @RequestBody TreeReportRequest request
	) {
		TreeReportResult result = execution.reportTree(new TreeReportCommand(
			request.operationId(), taskId, request.treeCount(), request.piecesPerTree(), request.scrapQuantity(), operatorCode, request.deviceCode(), request.workstationCode(), request.photoUrl()));
		return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
	}

	record TreeReportRequest(
		@NotNull UUID operationId,
		@NotNull @DecimalMin("0.001") BigDecimal treeCount,
		@NotNull @DecimalMin("0.001") BigDecimal piecesPerTree,
		@NotNull @DecimalMin("0.000") BigDecimal scrapQuantity,
		@Size(max = 64) String deviceCode,
		@Size(max = 64) String workstationCode,
		@Size(max = 1000) String photoUrl
	) {
	}

	record HandoffRequest(
		@DecimalMin("0.000") BigDecimal receivedQuantity,
		@Size(max = 500) String exceptionReason,
		@Size(max = 64) String receivedBy
	) {
	}

	record ReceiptRequest(
		@NotNull @DecimalMin("0.000") BigDecimal receivedQuantity,
		@Size(max = 32) String exceptionType,
		@Size(max = 500) String exceptionReason,
		@Size(max = 1000) String photoUrl,
		@Size(max = 64) String deviceCode,
		@Size(max = 64) String workstationCode
	) {
	}
}
