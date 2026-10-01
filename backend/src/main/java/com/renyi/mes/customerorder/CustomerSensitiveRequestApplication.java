package com.renyi.mes.customerorder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerSensitiveRequestApplication {

	private final JdbcTemplate jdbc;
	private final CustomerOrderApplication customerOrders;

	public CustomerSensitiveRequestApplication(JdbcTemplate jdbc, CustomerOrderApplication customerOrders) {
		this.jdbc = jdbc;
		this.customerOrders = customerOrders;
	}

	@Transactional
	public RequestView requestCreate(CreateRequestCommand command) {
		Instant now = Instant.now();
		UUID id = UUID.randomUUID();
		String requestNo = "CSR-" + LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
		jdbc.update("""
			insert into customer_sensitive_request (id, request_no, action_type, proposed_code, proposed_name, proposed_contact_name,
				proposed_contact_phone, proposed_sales_owner, request_note, requested_by, requested_at, status)
			values (?, ?, 'CREATE_CUSTOMER', ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
			""", id, requestNo, blankToNull(command.code()), required(command.name(), "客户名称不能为空"), blankToNull(command.contactName()),
			blankToNull(command.contactPhone()), blankToNull(command.salesOwner()), blankToNull(command.requestNote()), normalized(command.requesterCode()), now);
		return get(id);
	}

	@Transactional(readOnly = true)
	public List<RequestView> list() {
		return jdbc.query("select * from customer_sensitive_request order by requested_at desc", (rs, rowNum) -> map(rs));
	}

	@Transactional
	public RequestView approve(UUID requestId, String reviewerCode, String reviewNote) {
		RequestView request = get(requestId);
		ensurePending(request);
		CustomerOrderApplication.CustomerView customer = customerOrders.createCustomer(new CustomerOrderApplication.CreateCustomerCommand(
			request.proposedCode(), request.proposedName(), request.proposedContactName(), request.proposedContactPhone(), request.proposedSalesOwner()));
		Instant now = Instant.now();
		jdbc.update("""
			update customer_sensitive_request
			set status = 'APPROVED', reviewed_by = ?, reviewed_at = ?, review_note = ?, executed_customer_id = ?, executed_at = ?
			where id = ? and status = 'PENDING'
			""", normalized(reviewerCode), now, blankToNull(reviewNote), customer.id(), now, requestId);
		return get(requestId);
	}

	@Transactional
	public RequestView reject(UUID requestId, String reviewerCode, String reviewNote) {
		RequestView request = get(requestId);
		ensurePending(request);
		jdbc.update("""
			update customer_sensitive_request set status = 'REJECTED', reviewed_by = ?, reviewed_at = ?, review_note = ?
			where id = ? and status = 'PENDING'
			""", normalized(reviewerCode), Instant.now(), required(reviewNote, "驳回时请填写原因"), requestId);
		return get(requestId);
	}

	private RequestView get(UUID id) {
		List<RequestView> requests = jdbc.query("select * from customer_sensitive_request where id = ?", (rs, rowNum) -> map(rs), id);
		if (requests.isEmpty()) throw DomainException.notFound("CUSTOMER_SENSITIVE_REQUEST_NOT_FOUND", "客户敏感操作申请不存在");
		return requests.getFirst();
	}

	private static RequestView map(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new RequestView(rs.getObject("id", UUID.class), rs.getString("request_no"), rs.getString("action_type"),
			rs.getString("proposed_code"), rs.getString("proposed_name"), rs.getString("proposed_contact_name"),
			rs.getString("proposed_contact_phone"), rs.getString("proposed_sales_owner"), rs.getString("request_note"),
			rs.getString("requested_by"), rs.getObject("requested_at", Instant.class), rs.getString("status"),
			rs.getString("reviewed_by"), rs.getObject("reviewed_at", Instant.class), rs.getString("review_note"),
			rs.getObject("executed_customer_id", UUID.class), rs.getObject("executed_at", Instant.class));
	}

	private static void ensurePending(RequestView request) {
		if (!"PENDING".equals(request.status())) throw DomainException.conflict("CUSTOMER_SENSITIVE_REQUEST_NOT_PENDING", "申请已处理，不能重复审批");
	}
	private static String normalized(String value) { return required(value, "申请人不能为空").trim().toUpperCase(Locale.ROOT); }
	private static String required(String value, String message) { if (value == null || value.isBlank()) throw DomainException.badRequest("CUSTOMER_SENSITIVE_REQUEST_INVALID", message); return value.trim(); }
	private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

	public record CreateRequestCommand(String requesterCode, String code, String name, String contactName, String contactPhone, String salesOwner, String requestNote) { }
	public record RequestView(UUID id, String requestNo, String actionType, String proposedCode, String proposedName,
			String proposedContactName, String proposedContactPhone, String proposedSalesOwner, String requestNote,
			String requestedBy, Instant requestedAt, String status, String reviewedBy, Instant reviewedAt, String reviewNote,
			UUID executedCustomerId, Instant executedAt) { }
}
