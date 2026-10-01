package com.renyi.mes.execution;

import java.util.List;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scan-events")
class ScanAuditController {

	private static final List<String> ENTITY_TYPES = List.of("TASK", "ASSET", "BATCH");
	private final OperationalAuditApplication audit;
	private final ScanResolutionApplication scans;

	ScanAuditController(OperationalAuditApplication audit, ScanResolutionApplication scans) {
		this.audit = audit;
		this.scans = scans;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void record(@Valid @RequestBody ScanEventRequest request) {
		String entityType = request.entityType().trim().toUpperCase();
		if (!ENTITY_TYPES.contains(entityType)) {
			throw DomainException.badRequest("SCAN_ENTITY_TYPE_INVALID", "扫码对象仅支持生产任务、资产或生产批次");
		}
		String intent = request.intent().trim().toUpperCase();
        if (!List.of("VERIFY", "TRACE", "TASK_VIEW", "RECEIVE", "CLAIM", "DISPLAY_VIEW", "ASSET_VIEW", "CURRENT_TASK", "VIEW", "MOLD_VERIFY", "LOOKUP", "BIND",
                "CLAIM_TASK", "VIEW_TRACE", "VIEW_JOB_CARD", "OPEN_WORKBENCH", "VIEW_BATCH_TRACE", "OPEN_BATCH_TASK", "CLAIM_BATCH_TASK", "OPEN_MOLD_LEDGER", "OPEN_CART_TRANSFER", "OPEN_ASSET").contains(intent)) {
			throw DomainException.badRequest("SCAN_INTENT_INVALID", "扫码用途无效");
		}
		var resolved = scans.resolve(request.scannedValue());
		UUID resolvedId = switch (resolved.kind()) { case "task" -> resolved.task().id(); case "batch" -> resolved.batchId(); default -> resolved.asset().id(); };
		if (!request.entityType().equalsIgnoreCase(resolved.kind()) || !request.entityId().equals(resolvedId)) {
			throw DomainException.badRequest("SCAN_ENTITY_MISMATCH", "扫码内容与记录对象不一致");
		}
		audit.record("SCAN_" + intent, entityType, request.entityId(), request.operationId(), request.operatorCode(),
			request.deviceCode(), request.workstationCode(), "扫码编码=" + request.scannedValue().trim());
	}

	record ScanEventRequest(
		@NotNull UUID operationId,
		@NotBlank @Size(max = 32) String entityType,
		@NotNull UUID entityId,
		@NotBlank @Size(max = 32) String intent,
		@NotBlank @Size(max = 64) String operatorCode,
		@NotBlank @Size(max = 256) String scannedValue,
		@Size(max = 64) String deviceCode,
		@Size(max = 64) String workstationCode
	) { }
}
