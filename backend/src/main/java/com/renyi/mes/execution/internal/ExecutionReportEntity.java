package com.renyi.mes.execution.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.renyi.mes.planning.TaskStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "execution_report")
public class ExecutionReportEntity {

	@Id
	private UUID id;
	private UUID operationId;
	private UUID taskId;
	private BigDecimal goodQuantity;
	private BigDecimal scrapQuantity;
	private String operatorCode;
	private String recordedBy;
	private BigDecimal taskGoodTotal;
	private BigDecimal taskScrapTotal;
	@Enumerated(EnumType.STRING)
	private TaskStatus taskStatus;
	private String deviceCode;
	private String workstationCode;
	private String photoUrl;
	private Instant occurredAt;

	protected ExecutionReportEntity() {
	}

	public ExecutionReportEntity(
		UUID id,
		UUID operationId,
		UUID taskId,
		BigDecimal goodQuantity,
		BigDecimal scrapQuantity,
		String operatorCode,
		BigDecimal taskGoodTotal,
		BigDecimal taskScrapTotal,
		TaskStatus taskStatus,
		String deviceCode,
		String workstationCode,
		Instant occurredAt
	) {
		this(id, operationId, taskId, goodQuantity, scrapQuantity, operatorCode, taskGoodTotal, taskScrapTotal,
			taskStatus, deviceCode, workstationCode, null, occurredAt);
	}

	public ExecutionReportEntity(UUID id, UUID operationId, UUID taskId, BigDecimal goodQuantity, BigDecimal scrapQuantity,
			String operatorCode, BigDecimal taskGoodTotal, BigDecimal taskScrapTotal, TaskStatus taskStatus,
			String deviceCode, String workstationCode, String photoUrl, Instant occurredAt) {
		this(id, operationId, taskId, goodQuantity, scrapQuantity, operatorCode, taskGoodTotal, taskScrapTotal,
			taskStatus, deviceCode, workstationCode, photoUrl, occurredAt, operatorCode);
	}
	public ExecutionReportEntity(UUID id, UUID operationId, UUID taskId, BigDecimal goodQuantity, BigDecimal scrapQuantity,
			String operatorCode, BigDecimal taskGoodTotal, BigDecimal taskScrapTotal, TaskStatus taskStatus,
			String deviceCode, String workstationCode, String photoUrl, Instant occurredAt, String recordedBy) {
		this.id = id;
		this.operationId = operationId;
		this.taskId = taskId;
		this.goodQuantity = goodQuantity;
		this.scrapQuantity = scrapQuantity;
		this.operatorCode = operatorCode;
		this.recordedBy = recordedBy;
		this.taskGoodTotal = taskGoodTotal;
		this.taskScrapTotal = taskScrapTotal;
		this.taskStatus = taskStatus;
		this.deviceCode = deviceCode;
		this.workstationCode = workstationCode;
		this.photoUrl = photoUrl;
		this.occurredAt = occurredAt;
	}

	public UUID id() {
		return id;
	}

	public UUID operationId() {
		return operationId;
	}

	public UUID taskId() {
		return taskId;
	}

	public BigDecimal goodQuantity() {
		return goodQuantity;
	}

	public BigDecimal scrapQuantity() {
		return scrapQuantity;
	}

	public String operatorCode() {
		return operatorCode;
	}
	public String recordedBy() { return recordedBy == null ? operatorCode : recordedBy; }

	public BigDecimal taskGoodTotal() {
		return taskGoodTotal;
	}

	public BigDecimal taskScrapTotal() {
		return taskScrapTotal;
	}

	public TaskStatus taskStatus() {
		return taskStatus;
	}

	public String deviceCode() { return deviceCode; }

	public String workstationCode() { return workstationCode; }

	public String photoUrl() { return photoUrl; }

	public Instant occurredAt() {
		return occurredAt;
	}
}
