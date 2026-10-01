package com.renyi.mes.customerorder.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.customerorder.OrderPriority;
import com.renyi.mes.customerorder.OrderStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "customer_order_header")
public class CustomerOrderEntity {

	@Id
	private UUID id;
	private String orderNo;
	private UUID customerId;
	private String customerCode;
	private String customerName;
	private String salesOwner;
	@Enumerated(EnumType.STRING)
	private OrderStatus status;
	@Enumerated(EnumType.STRING)
	private OrderPriority priority;
	private LocalDate requestedDeliveryDate;
	private String remark;
    private String orderDrawingUrl;
	private String contractAttachmentUrl;
	@Version
	private long version;
	private Instant createdAt;
	private Instant approvedAt;
	private Instant releasedAt;
	private String createdBy;
	private String processCardVersion;
	private String engineeringParameters;
	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	private String engineeringOperationParameters;
	private String engineeringConfirmedBy;
	private Instant engineeringConfirmedAt;
	private String engineeringReturnedBy;
	private Instant engineeringReturnedAt;
	private String engineeringReturnReason;
	private Instant engineeringReturnResolvedAt;
	private String defaultProcessCardVersion;
	private String defaultProcessReleasedBy;
	private Instant defaultProcessReleasedAt;
	private String customerManagerCode;
	private Instant customerManagerReviewedAt;
	private String customerManagerReviewNote;
	private String customerManagerReturnedBy;
	private Instant customerManagerReturnedAt;
	private String customerManagerReturnReason;
	private Instant customerManagerReturnResolvedAt;
	private String generalManagerCode;
	private Instant generalManagerReviewedAt;
	private String generalManagerReviewNote;
	private String generalManagerReturnedBy;
	private Instant generalManagerReturnedAt;
	private String generalManagerReturnReason;
	private Instant generalManagerReturnResolvedAt;

	protected CustomerOrderEntity() {
	}

	public CustomerOrderEntity(
		UUID id,
		String orderNo,
		UUID customerId,
		String customerCode,
		String customerName,
		OrderStatus status,
		OrderPriority priority,
		LocalDate requestedDeliveryDate,
		String remark,
		Instant createdAt
	) {
		this.id = id;
		this.orderNo = orderNo;
		this.customerId = customerId;
		this.customerCode = customerCode;
		this.customerName = customerName;
		this.status = status;
		this.priority = priority;
		this.requestedDeliveryDate = requestedDeliveryDate;
		this.remark = remark;
		this.createdAt = createdAt;
	}

	public void approve(Instant now) {
		if (status != OrderStatus.SUBMITTED) {
			throw DomainException.conflict("ORDER_STATE_CONFLICT", "只有已提交订单可以审批");
		}
		if (!reviewGatePassed()) {
			throw DomainException.conflict("ORDER_REVIEW_GATE_PENDING", "Engineering confirmation and either customer manager or general manager review are required before approval");
		}
		approveWhenReady(now);
	}

	public void release(Instant now) {
		if (status != OrderStatus.APPROVED) {
			throw DomainException.conflict("ORDER_STATE_CONFLICT", "只有已审批订单可以放行");
		}
		status = OrderStatus.RELEASED;
		releasedAt = now;
	}

	public void confirmEngineering(String engineerCode, String cardVersion, String parameters, String operationParameters, Instant now) {
		if (status != OrderStatus.SUBMITTED && !(status == OrderStatus.RELEASED && defaultProcessReleasedAt != null)) {
			throw DomainException.conflict("ORDER_ENGINEERING_CONFIRMATION_INVALID", "Only a reviewed submitted order can be confirmed by engineering");
		}
		if (status == OrderStatus.RELEASED && defaultProcessReleasedAt == null) {
			throw DomainException.conflict("ORDER_STATE_CONFLICT", "Released orders cannot replace their confirmed engineering card");
		}
		processCardVersion = cardVersion;
		engineeringParameters = parameters;
		engineeringOperationParameters = operationParameters;
		engineeringConfirmedBy = engineerCode;
		engineeringConfirmedAt = now;
		if (engineeringReturnedAt != null && engineeringReturnResolvedAt == null) {
			engineeringReturnResolvedAt = now;
		}
		approveWhenReady(now);
	}

	public void returnForEngineering(String engineerCode, String reason, Instant now) {
		if (status != OrderStatus.SUBMITTED || engineeringConfirmedAt != null) {
			throw DomainException.conflict("ORDER_ENGINEERING_RETURN_INVALID", "Only a submitted order without engineering confirmation can be returned to the front desk");
		}
		engineeringReturnedBy = engineerCode;
		engineeringReturnedAt = now;
		engineeringReturnReason = reason;
		engineeringReturnResolvedAt = null;
		resetReviewForRevision();
	}

	public void authorizeDefaultProcess(String supervisorCode, String cardVersion, String parameters, String operationParameters, Instant now) {
		if (status != OrderStatus.SUBMITTED) {
			throw DomainException.conflict("ORDER_STATE_CONFLICT", "Only submitted orders can start under a default process card");
		}
		if (customerManagerReviewedAt == null && generalManagerReviewedAt == null) {
			throw DomainException.conflict("ORDER_REVIEW_GATE_PENDING", "A customer manager or general manager review is required first");
		}
		defaultProcessCardVersion = cardVersion;
		defaultProcessReleasedBy = supervisorCode;
		defaultProcessReleasedAt = now;
		processCardVersion = cardVersion;
		engineeringParameters = parameters;
		engineeringOperationParameters = operationParameters;
		approveWhenReady(now);
	}

	public UUID id() {
		return id;
	}

	public String orderNo() {
		return orderNo;
	}

	public UUID customerId() {
		return customerId;
	}

	public String customerCode() {
		return customerCode;
	}

	public String customerName() {
		return customerName;
	}

	public String salesOwner() {
		return salesOwner;
	}

	public OrderStatus status() {
		return status;
	}

	public OrderPriority priority() {
		return priority;
	}

	public LocalDate requestedDeliveryDate() {
		return requestedDeliveryDate;
	}

	public String remark() {
		return remark;
	}

	public String orderDrawingUrl() { return orderDrawingUrl; }
	public String contractAttachmentUrl() { return contractAttachmentUrl; }

	public Instant createdAt() {
		return createdAt;
	}

	public Instant approvedAt() {
		return approvedAt;
	}

	public Instant releasedAt() {
		return releasedAt;
	}

	public String createdBy() { return createdBy; }
	public String processCardVersion() { return processCardVersion; }
	public String engineeringParameters() { return engineeringParameters; }
	public String engineeringOperationParameters() { return engineeringOperationParameters; }
	public String engineeringConfirmedBy() { return engineeringConfirmedBy; }
	public Instant engineeringConfirmedAt() { return engineeringConfirmedAt; }
	public String engineeringReturnedBy() { return engineeringReturnedBy; }
	public Instant engineeringReturnedAt() { return engineeringReturnedAt; }
	public String engineeringReturnReason() { return engineeringReturnReason; }
	public Instant engineeringReturnResolvedAt() { return engineeringReturnResolvedAt; }
	public String defaultProcessCardVersion() { return defaultProcessCardVersion; }
	public String defaultProcessReleasedBy() { return defaultProcessReleasedBy; }
	public Instant defaultProcessReleasedAt() { return defaultProcessReleasedAt; }
	public String customerManagerCode() { return customerManagerCode; }
	public Instant customerManagerReviewedAt() { return customerManagerReviewedAt; }
	public String customerManagerReviewNote() { return customerManagerReviewNote; }
	public String customerManagerReturnedBy() { return customerManagerReturnedBy; }
	public Instant customerManagerReturnedAt() { return customerManagerReturnedAt; }
	public String customerManagerReturnReason() { return customerManagerReturnReason; }
	public Instant customerManagerReturnResolvedAt() { return customerManagerReturnResolvedAt; }
	public String generalManagerCode() { return generalManagerCode; }
	public Instant generalManagerReviewedAt() { return generalManagerReviewedAt; }
	public String generalManagerReviewNote() { return generalManagerReviewNote; }
	public void setCreatedBy(String value) { createdBy = value; }
	public void setSalesOwner(String value) { salesOwner = value; }
	public void setOrderDrawingUrl(String value) { orderDrawingUrl = value; }
	public void setContractAttachmentUrl(String value) { contractAttachmentUrl = value; }
	public void reviewByCustomerManager(String managerCode, String note, Instant now) {
		ensureReviewable();
		customerManagerCode = managerCode;
		customerManagerReviewNote = note;
		customerManagerReviewedAt = now;
		if (customerManagerReturnedAt != null && customerManagerReturnResolvedAt == null) {
			customerManagerReturnResolvedAt = now;
		}
		approveWhenReady(now);
	}
	public void returnByCustomerManager(String managerCode, String reason, Instant now) {
		if (status != OrderStatus.SUBMITTED) {
			throw DomainException.conflict("ORDER_CUSTOMER_MANAGER_RETURN_INVALID", "Only a submitted order can be returned to the front desk");
		}
		customerManagerReturnedBy = managerCode;
		customerManagerReturnedAt = now;
		customerManagerReturnReason = reason;
		customerManagerReturnResolvedAt = null;
		resetReviewForRevision();
	}
	public void reviewByGeneralManager(String managerCode, String note, Instant now) {
		ensureReviewable();
		generalManagerCode = managerCode;
		generalManagerReviewNote = note;
		generalManagerReviewedAt = now;
		approveWhenReady(now);
	}
	public void returnByGeneralManager(String managerCode, String reason, Instant now) {
		if (status != OrderStatus.SUBMITTED) {
			throw DomainException.conflict("ORDER_GENERAL_MANAGER_RETURN_INVALID", "Only a submitted order can be returned to the front desk");
		}
		generalManagerReturnedBy = managerCode;
		generalManagerReturnedAt = now;
		generalManagerReturnReason = reason;
		generalManagerReturnResolvedAt = null;
		resetReviewForRevision();
	}
	public void submit(Instant now) {
		if (status != OrderStatus.DRAFT) {
			throw DomainException.conflict("ORDER_SUBMISSION_INVALID", "Only a draft order can be submitted for review");
		}
		status = OrderStatus.SUBMITTED;
		if (engineeringReturnedAt != null && engineeringReturnResolvedAt == null) engineeringReturnResolvedAt = now;
		if (customerManagerReturnedAt != null && customerManagerReturnResolvedAt == null) customerManagerReturnResolvedAt = now;
		if (generalManagerReturnedAt != null && generalManagerReturnResolvedAt == null) generalManagerReturnResolvedAt = now;
	}
	public void updateDraft(UUID customerId, String customerCode, String customerName, String salesOwner,
		OrderPriority priority, LocalDate requestedDeliveryDate, String remark, String orderDrawingUrl, String contractAttachmentUrl) {
		if (status != OrderStatus.DRAFT) {
			throw DomainException.conflict("ORDER_EDIT_LOCKED", "Only a draft order can be edited");
		}
		this.customerId = customerId;
		this.customerCode = customerCode;
		this.customerName = customerName;
		this.salesOwner = salesOwner;
		this.priority = priority;
		this.requestedDeliveryDate = requestedDeliveryDate;
		this.remark = remark;
		this.orderDrawingUrl = orderDrawingUrl;
		this.contractAttachmentUrl = contractAttachmentUrl;
	}
	public boolean reviewGatePassed() {
		return (customerManagerReviewedAt != null || generalManagerReviewedAt != null)
			&& (engineeringConfirmedAt != null || defaultProcessReleasedAt != null);
	}
	private void ensureReviewable() {
		if (status != OrderStatus.SUBMITTED) {
			throw DomainException.conflict("ORDER_REVIEW_INVALID", "Only submitted orders can be reviewed");
		}
	}
	private void approveWhenReady(Instant now) {
		if (status == OrderStatus.SUBMITTED && reviewGatePassed()) {
			status = OrderStatus.APPROVED;
			approvedAt = now;
		}
	}
	private void resetReviewForRevision() {
		status = OrderStatus.DRAFT;
		approvedAt = null;
		processCardVersion = null;
		engineeringParameters = null;
		engineeringOperationParameters = null;
		engineeringConfirmedBy = null;
		engineeringConfirmedAt = null;
		defaultProcessCardVersion = null;
		defaultProcessReleasedBy = null;
		defaultProcessReleasedAt = null;
		customerManagerCode = null;
		customerManagerReviewedAt = null;
		customerManagerReviewNote = null;
		generalManagerCode = null;
		generalManagerReviewedAt = null;
		generalManagerReviewNote = null;
	}
	public String generalManagerReturnedBy() { return generalManagerReturnedBy; }
	public Instant generalManagerReturnedAt() { return generalManagerReturnedAt; }
	public String generalManagerReturnReason() { return generalManagerReturnReason; }
	public Instant generalManagerReturnResolvedAt() { return generalManagerReturnResolvedAt; }
}
