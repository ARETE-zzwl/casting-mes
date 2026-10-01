package com.renyi.mes.customerorder.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.renyi.mes.engineering.RouteType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "customer_order_line")
public class CustomerOrderLineEntity {

	@Id
	private UUID id;
	private UUID orderId;
	private int lineNo;
	private UUID productId;
	private String productCode;
	private String productName;
	@Enumerated(EnumType.STRING)
	private RouteType routeType;
	private String routeVersion;
	private String modelImageUrl;
	private String productMaterial;
	private BigDecimal orderedQuantity;
	private String unit;
	private BigDecimal salesUnitPrice;
	private String salesPriceUnit;
	private String processCardVersion;
	private String engineeringParameters;
	@org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR)
	private String engineeringOperationParameters;
	private String engineeringConfirmedBy;
	private Instant engineeringConfirmedAt;
	private String defaultProcessCardVersion;
	private String defaultProcessReleasedBy;
	private Instant defaultProcessReleasedAt;

	protected CustomerOrderLineEntity() {
	}

	public CustomerOrderLineEntity(
		UUID id,
		UUID orderId,
		int lineNo,
		UUID productId,
		String productCode,
		String productName,
		RouteType routeType,
		String routeVersion,
		String modelImageUrl,
		String productMaterial,
		BigDecimal orderedQuantity,
		String unit,
		BigDecimal salesUnitPrice,
		String salesPriceUnit
	) {
		this.id = id;
		this.orderId = orderId;
		this.lineNo = lineNo;
		this.productId = productId;
		this.productCode = productCode;
		this.productName = productName;
		this.routeType = routeType;
		this.routeVersion = routeVersion;
		this.modelImageUrl = modelImageUrl;
		this.productMaterial = productMaterial;
		this.orderedQuantity = orderedQuantity;
		this.unit = unit;
		this.salesUnitPrice = salesUnitPrice;
		this.salesPriceUnit = salesPriceUnit;
	}

	public UUID id() {
		return id;
	}

	public UUID orderId() {
		return orderId;
	}

	public int lineNo() {
		return lineNo;
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

	public RouteType routeType() {
		return routeType;
	}

	public String routeVersion() {
		return routeVersion;
	}

	public String modelImageUrl() {
		return modelImageUrl;
	}

	public String productMaterial() {
		return productMaterial;
	}

	public BigDecimal orderedQuantity() {
		return orderedQuantity;
	}

	public String unit() {
		return unit;
	}

	public BigDecimal salesUnitPrice() {
		return salesUnitPrice;
	}

	public String salesPriceUnit() {
		return salesPriceUnit;
	}

	public void confirmEngineering(String engineerCode, String cardVersion, String parameters, String operationParameters, Instant now) {
		processCardVersion = cardVersion;
		engineeringParameters = parameters;
		engineeringOperationParameters = operationParameters;
		engineeringConfirmedBy = engineerCode;
		engineeringConfirmedAt = now;
	}

	public void authorizeDefaultProcess(String supervisorCode, String cardVersion, String parameters, String operationParameters, Instant now) {
		processCardVersion = cardVersion;
		engineeringParameters = parameters;
		engineeringOperationParameters = operationParameters;
		defaultProcessCardVersion = cardVersion;
		defaultProcessReleasedBy = supervisorCode;
		defaultProcessReleasedAt = now;
	}

	public void clearProcessApproval() {
		processCardVersion = null;
		engineeringParameters = null;
		engineeringOperationParameters = null;
		engineeringConfirmedBy = null;
		engineeringConfirmedAt = null;
		defaultProcessCardVersion = null;
		defaultProcessReleasedBy = null;
		defaultProcessReleasedAt = null;
	}

	public String processCardVersion() { return processCardVersion; }
	public String engineeringParameters() { return engineeringParameters; }
	public String engineeringOperationParameters() { return engineeringOperationParameters; }
	public String engineeringConfirmedBy() { return engineeringConfirmedBy; }
	public Instant engineeringConfirmedAt() { return engineeringConfirmedAt; }
	public String defaultProcessCardVersion() { return defaultProcessCardVersion; }
	public String defaultProcessReleasedBy() { return defaultProcessReleasedBy; }
	public Instant defaultProcessReleasedAt() { return defaultProcessReleasedAt; }
	public boolean processReady() { return engineeringConfirmedAt != null || defaultProcessReleasedAt != null; }
}
