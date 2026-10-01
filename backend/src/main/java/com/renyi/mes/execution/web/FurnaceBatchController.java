package com.renyi.mes.execution.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.renyi.mes.execution.FurnaceBatchApplication;
import com.renyi.mes.execution.FurnaceBatchApplication.FurnaceBatchView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/furnace-batches")
class FurnaceBatchController {
	private final FurnaceBatchApplication batches;
	FurnaceBatchController(FurnaceBatchApplication batches) { this.batches = batches; }
	@GetMapping List<FurnaceBatchView> list() { return batches.list(); }
	@PostMapping @ResponseStatus(HttpStatus.CREATED) FurnaceBatchView create(@Valid @RequestBody CreateRequest request) {
		return batches.create(new FurnaceBatchApplication.CreateCommand(request.operationCode(), request.furnaceAssetId(), request.materialBatch(),
			request.chargeQuantity(), request.targetTemperature(), request.actualTemperature(), request.pressureMpa(), request.taskIds(), request.note(), request.createdBy()));
	}
	@PostMapping("/{id}/complete") FurnaceBatchView complete(@PathVariable UUID id, @Valid @RequestBody CompleteRequest request) {
		return batches.complete(id, new FurnaceBatchApplication.CompleteCommand(request.actualTemperature(), request.pressureMpa(), request.note(), request.completedBy()));
	}
	record CreateRequest(@NotBlank @Size(max = 32) String operationCode, UUID furnaceAssetId, @Size(max = 128) String materialBatch,
			@NotNull @DecimalMin("0.001") BigDecimal chargeQuantity, BigDecimal targetTemperature, BigDecimal actualTemperature,
			BigDecimal pressureMpa, @NotEmpty List<UUID> taskIds, @Size(max = 1000) String note, @NotBlank @Size(max = 64) String createdBy) { }
	record CompleteRequest(BigDecimal actualTemperature, BigDecimal pressureMpa, @Size(max = 1000) String note,
			@NotBlank @Size(max = 64) String completedBy) { }
}
