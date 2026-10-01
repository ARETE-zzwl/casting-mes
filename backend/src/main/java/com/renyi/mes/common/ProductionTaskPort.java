package com.renyi.mes.common;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read and dispatch boundary for modules that need a production task without owning planning.
 */
public interface ProductionTaskPort {

	TaskSnapshot get(UUID taskId);

	TaskSnapshot assignAndStart(UUID taskId, String workerCode, String supervisorCode);

	record TaskSnapshot(
		UUID id,
		String taskNo,
		UUID workOrderId,
		String workOrderNo,
		UUID orderId,
		UUID orderLineId,
		String productCode,
		String productName,
		String productMaterial,
		String routeType,
		UUID batchId,
		String batchNo,
		int sequenceNo,
		String operationCode,
		String operationName,
		BigDecimal plannedQuantity,
		BigDecimal goodQuantity,
		BigDecimal scrapQuantity,
		String status,
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
	) { }
}
