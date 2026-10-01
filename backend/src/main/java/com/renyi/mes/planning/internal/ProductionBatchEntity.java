package com.renyi.mes.planning.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.renyi.mes.planning.BatchStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "planning_batch")
public class ProductionBatchEntity {

	@Id
	private UUID id;
	private String batchNo;
	private UUID workOrderId;
	private BigDecimal plannedQuantity;
	private String batchType;
	private UUID parentBatchId;
	@Enumerated(EnumType.STRING)
	private BatchStatus status;
	private Instant createdAt;

	protected ProductionBatchEntity() {
	}

	public ProductionBatchEntity(
		UUID id,
		String batchNo,
		UUID workOrderId,
		BigDecimal plannedQuantity,
		BatchStatus status,
		Instant createdAt
	) {
		this(id, batchNo, workOrderId, plannedQuantity, status, "PLANNED", null, createdAt);
	}

	public ProductionBatchEntity(
		UUID id,
		String batchNo,
		UUID workOrderId,
		BigDecimal plannedQuantity,
		BatchStatus status,
		String batchType,
		UUID parentBatchId,
		Instant createdAt
	) {
		this.id = id;
		this.batchNo = batchNo;
		this.workOrderId = workOrderId;
		this.plannedQuantity = plannedQuantity;
		this.status = status;
		this.batchType = batchType;
		this.parentBatchId = parentBatchId;
		this.createdAt = createdAt;
	}

	public UUID id() {
		return id;
	}

	public String batchNo() {
		return batchNo;
	}

	public UUID workOrderId() {
		return workOrderId;
	}

	public BigDecimal plannedQuantity() {
		return plannedQuantity;
	}

	public void adjustPlannedQuantity(BigDecimal quantity) {
		this.plannedQuantity = quantity;
	}

	public BatchStatus status() { return status; }

	public void launch() {
		if (status != BatchStatus.PENDING_LAUNCH) {
			throw new IllegalStateException("Only pending batches can be launched");
		}
		status = BatchStatus.READY;
	}

	public String batchType() { return batchType; }
	public UUID parentBatchId() { return parentBatchId; }
}
