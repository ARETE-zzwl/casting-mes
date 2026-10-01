package com.renyi.mes.planning.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.renyi.mes.engineering.RouteType;
import com.renyi.mes.planning.WorkOrderStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "planning_work_order")
public class WorkOrderEntity {

	@Id
	private UUID id;
	private String workOrderNo;
	private UUID orderId;
	private UUID orderLineId;
	private UUID productId;
	private String productCode;
	private String productName;
	private String productMaterial;
	@Enumerated(EnumType.STRING)
	private RouteType routeType;
	private String routeVersion;
	private String processCardVersion;
	private String engineeringParameters;
	@org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR)
	private String engineeringOperationParameters;
	private BigDecimal plannedQuantity;
	@Enumerated(EnumType.STRING)
	private WorkOrderStatus status;
	private Instant createdAt;

	protected WorkOrderEntity() {
	}

	public WorkOrderEntity(
		UUID id,
		String workOrderNo,
		UUID orderId,
		UUID orderLineId,
		UUID productId,
		String productCode,
		String productName,
		String productMaterial,
		RouteType routeType,
		String routeVersion,
		String processCardVersion,
		String engineeringParameters,
		String engineeringOperationParameters,
		BigDecimal plannedQuantity,
		WorkOrderStatus status,
		Instant createdAt
	) {
		this.id = id;
		this.workOrderNo = workOrderNo;
		this.orderId = orderId;
		this.orderLineId = orderLineId;
		this.productId = productId;
		this.productCode = productCode;
		this.productName = productName;
		this.productMaterial = productMaterial;
		this.routeType = routeType;
		this.routeVersion = routeVersion;
		this.processCardVersion = processCardVersion;
		this.engineeringParameters = engineeringParameters;
		this.engineeringOperationParameters = engineeringOperationParameters;
		this.plannedQuantity = plannedQuantity;
		this.status = status;
		this.createdAt = createdAt;
	}

	public UUID id() {
		return id;
	}

	public String workOrderNo() {
		return workOrderNo;
	}

	public UUID orderId() {
		return orderId;
	}

	public UUID orderLineId() {
		return orderLineId;
	}

	public UUID productId() {
		return productId;
	}

	public String productCode() {
		return productCode;
	}

	public String productName() {
		return productName;
	}

	public String productMaterial() {
		return productMaterial;
	}

	public RouteType routeType() {
		return routeType;
	}

	public String routeVersion() {
		return routeVersion;
	}

	public String processCardVersion() { return processCardVersion; }
	public String engineeringParameters() { return engineeringParameters; }
	public String engineeringOperationParameters() { return engineeringOperationParameters; }

	public BigDecimal plannedQuantity() {
		return plannedQuantity;
	}

	public void adjustPlannedQuantity(BigDecimal quantity) {
		this.plannedQuantity = quantity;
	}

	public WorkOrderStatus status() {
		return status;
	}

	public Instant createdAt() {
		return createdAt;
	}
}
