package com.renyi.mes.execution.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.renyi.mes.execution.ExecutionApplication;
import com.renyi.mes.execution.ExecutionApplication.ReportCommand;
import com.renyi.mes.execution.ExecutionApplication.ReportResult;
import com.renyi.mes.execution.ExecutionApplication.ReportView;
import com.renyi.mes.execution.ExecutionApplication.UpstreamReportsView;
import com.renyi.mes.common.PageResult;
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
@RequestMapping("/api/execution/reports")
class ExecutionLedgerController {
	private final ExecutionApplication execution;
	ExecutionLedgerController(ExecutionApplication execution) { this.execution = execution; }
	@GetMapping
	List<ExecutionApplication.ReportLedgerView> ledger(
			@org.springframework.web.bind.annotation.RequestParam String viewerCode,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String workerCode,
			@org.springframework.web.bind.annotation.RequestParam(required = false) UUID orderId,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String operationCode,
			@org.springframework.web.bind.annotation.RequestParam(required = false) Instant from,
			@org.springframework.web.bind.annotation.RequestParam(required = false) Instant to) {
		return execution.reportLedger(viewerCode, workerCode, orderId, operationCode, from, to);
	}

	@GetMapping("/query")
	PageResult<ExecutionApplication.ReportLedgerView> ledgerPage(
			@org.springframework.web.bind.annotation.RequestParam String viewerCode,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String workerCode,
			@org.springframework.web.bind.annotation.RequestParam(required = false) UUID orderId,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String operationCode,
			@org.springframework.web.bind.annotation.RequestParam(required = false) Instant from,
			@org.springframework.web.bind.annotation.RequestParam(required = false) Instant to,
			@org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
			@org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
		return execution.searchReportLedger(viewerCode, workerCode, orderId, operationCode, from, to, page, size);
	}
}

@RestController
@RequestMapping("/api/tasks/{taskId}/reports")
class ExecutionController {

	private final ExecutionApplication execution;

	ExecutionController(ExecutionApplication execution) {
		this.execution = execution;
	}

	@GetMapping
	List<ReportView> list(@PathVariable UUID taskId) {
		return execution.reportsForTask(taskId);
	}

	@GetMapping("/upstream")
	UpstreamReportsView upstream(@PathVariable UUID taskId) {
		return execution.upstreamReportsForTask(taskId);
	}

	@PostMapping
	ResponseEntity<ReportResult> report(
		@PathVariable UUID taskId,
		@RequestHeader("X-Operator-Code") @NotBlank @Size(max = 64) String operatorCode,
		@Valid @RequestBody ReportRequest request
	) {
		ReportResult result = execution.report(new ReportCommand(
			request.operationId(),
			taskId,
			request.goodQuantity(),
			request.scrapQuantity(),
			operatorCode, request.deviceCode(), request.workstationCode(), request.photoUrl()
		));
		return ResponseEntity
			.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
			.body(result);
	}

	record ReportRequest(
		@NotNull UUID operationId,
		@NotNull @DecimalMin("0.000") BigDecimal goodQuantity,
		@NotNull @DecimalMin("0.000") BigDecimal scrapQuantity,
		@Size(max = 64) String deviceCode,
		@Size(max = 64) String workstationCode,
		@Size(max = 1000) String photoUrl
	) {
	}
}
