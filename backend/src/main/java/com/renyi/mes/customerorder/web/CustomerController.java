package com.renyi.mes.customerorder.web;

import java.util.List;
import java.util.UUID;

import com.renyi.mes.customerorder.CustomerOrderApplication;
import com.renyi.mes.customerorder.CustomerOrderApplication.CreateCustomerCommand;
import com.renyi.mes.customerorder.CustomerOrderApplication.CustomerView;
import com.renyi.mes.customerorder.CustomerSensitiveRequestApplication;
import com.renyi.mes.customerorder.CustomerSensitiveRequestApplication.RequestView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
class CustomerController {

	private final CustomerOrderApplication customerOrders;
	private final CustomerSensitiveRequestApplication customerRequests;
	private final JdbcTemplate jdbc;

	CustomerController(CustomerOrderApplication customerOrders, CustomerSensitiveRequestApplication customerRequests, JdbcTemplate jdbc) {
		this.customerOrders = customerOrders;
		this.customerRequests = customerRequests;
		this.jdbc = jdbc;
	}

	@GetMapping
	List<CustomerView> list() {
		return customerOrders.listCustomers();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	CustomerView create(@Valid @RequestBody CreateCustomerRequest request) {
		return customerOrders.createCustomer(new CreateCustomerCommand(
			request.code(),
			request.name(),
			request.contactName(),
			request.contactPhone(),
			request.salesOwner()
		));
	}

	@GetMapping("/sensitive-requests")
	List<RequestView> listSensitiveRequests() {
		return customerRequests.list();
	}

	@PostMapping("/sensitive-requests")
	@ResponseStatus(HttpStatus.CREATED)
	RequestView requestCreate(@Valid @RequestBody SensitiveCustomerCreateRequest request) {
		requireRole(request.requesterCode(), "CUSTOMER_CHANGE_REQUEST", "仅前台或已授权角色可提交客户敏感操作申请");
		return customerRequests.requestCreate(new CustomerSensitiveRequestApplication.CreateRequestCommand(request.requesterCode(), request.code(), request.name(),
			request.contactName(), request.contactPhone(), request.salesOwner(), request.requestNote()));
	}

	@PostMapping("/sensitive-requests/{requestId}/approve")
	RequestView approve(@PathVariable UUID requestId, @Valid @RequestBody CustomerRequestReviewRequest request) {
		requireRole(request.reviewerCode(), "CUSTOMER_CHANGE_APPROVE", "仅客户经理、总经理或管理员可审批客户敏感操作");
		return customerRequests.approve(requestId, request.reviewerCode(), request.reviewNote());
	}

	@PostMapping("/sensitive-requests/{requestId}/reject")
	RequestView reject(@PathVariable UUID requestId, @Valid @RequestBody CustomerRequestReviewRequest request) {
		requireRole(request.reviewerCode(), "CUSTOMER_CHANGE_APPROVE", "仅客户经理、总经理或管理员可审批客户敏感操作");
		return customerRequests.reject(requestId, request.reviewerCode(), request.reviewNote());
	}

	private void requireRole(String employeeCode, String permissionCode, String message) {
		Integer permitted = jdbc.queryForObject("""
			select count(*) from organization_member_role mr
			join access_role_permission rp on rp.role_code = mr.role_code
			where mr.employee_code = ? and rp.permission_code = ?
			""", Integer.class, employeeCode.trim().toUpperCase(), permissionCode);
		if (permitted == null || permitted == 0) throw com.renyi.mes.common.DomainException.forbidden("CUSTOMER_SENSITIVE_REQUEST_FORBIDDEN", message);
	}

	record CreateCustomerRequest(
		@Size(max = 64) String code,
		@NotBlank @Size(max = 160) String name,
		@Size(max = 100) String contactName,
		@Size(max = 40) String contactPhone,
		@Size(max = 100) String salesOwner
	) {
	}

	record SensitiveCustomerCreateRequest(@NotBlank @Size(max = 64) String requesterCode, @Size(max = 64) String code, @NotBlank @Size(max = 160) String name,
			@Size(max = 100) String contactName, @Size(max = 40) String contactPhone, @Size(max = 100) String salesOwner,
			@Size(max = 500) String requestNote) { }

	record CustomerRequestReviewRequest(@NotBlank @Size(max = 64) String reviewerCode, @Size(max = 500) String reviewNote) { }
}
