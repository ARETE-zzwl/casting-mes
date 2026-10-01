package com.renyi.mes.customerorder.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.renyi.mes.customerorder.CustomerOrderApplication;
import com.renyi.mes.customerorder.CustomerOrderApplication.CreateOrderCommand;
import com.renyi.mes.customerorder.CustomerOrderApplication.CreateOrderLineCommand;
import com.renyi.mes.customerorder.CustomerOrderApplication.OrderView;
import com.renyi.mes.customerorder.OrderPriority;
import com.renyi.mes.common.OrderReleasePort;
import com.renyi.mes.engineering.RouteType;
import com.renyi.mes.notification.NotificationApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
class OrderController {

	private final CustomerOrderApplication customerOrders;
	private final OrderReleasePort orderRelease;
	private final NotificationApplication notifications;
	private final JdbcTemplate jdbc;

	OrderController(CustomerOrderApplication customerOrders, OrderReleasePort orderRelease,
			NotificationApplication notifications, JdbcTemplate jdbc) {
		this.customerOrders = customerOrders;
		this.orderRelease = orderRelease;
		this.notifications = notifications;
		this.jdbc = jdbc;
	}

	@GetMapping
	List<OrderView> list(@RequestParam(required = false) RouteType routeType) {
		return customerOrders.listOrders(routeType);
	}

	@GetMapping("/{orderId}")
	OrderView get(@PathVariable UUID orderId) {
		return customerOrders.getOrder(orderId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	OrderView create(@Valid @RequestBody CreateOrderRequest request) {
		return customerOrders.createOrder(new CreateOrderCommand(
			request.orderNo(),
			request.customerId(),
			request.priority(),
			request.requestedDeliveryDate(),
			request.remark(),
			request.createdBy(),
			request.orderDrawingUrl(),
			request.contractAttachmentUrl(),
			request.lines().stream()
				.map(line -> new CreateOrderLineCommand(line.productId(), line.quantity(), line.unit(),
					line.salesUnitPrice(), line.salesPriceUnit(), line.productMaterial()))
				.toList()
		));
	}

	@PostMapping("/{orderId}/draft")
	OrderView updateDraft(@PathVariable UUID orderId, @Valid @RequestBody UpdateOrderRequest request) {
		requireFrontDeskEditor(request.editorCode());
		return customerOrders.updateDraft(orderId, new CustomerOrderApplication.UpdateOrderCommand(
			request.customerId(), request.priority(), request.requestedDeliveryDate(), request.remark(), request.orderDrawingUrl(),
			request.contractAttachmentUrl(), request.lines().stream().map(line -> new CreateOrderLineCommand(line.productId(),
				line.quantity(), line.unit(), line.salesUnitPrice(), line.salesPriceUnit(), line.productMaterial())).toList()));
	}

	@PostMapping("/{orderId}/submit")
	OrderView submit(@PathVariable UUID orderId, @Valid @RequestBody SubmitOrderRequest request) {
		requireFrontDeskEditor(request.editorCode());
		OrderView order = customerOrders.submit(orderId);
		List<String> managers = jdbc.query("""
			select distinct mr.employee_code from organization_member_role mr join organization_member m on m.employee_code = mr.employee_code
			where mr.role_code in ('GENERAL_MANAGER', 'SYSTEM_ADMIN') and m.active = true
			""", (rs, row) -> rs.getString("employee_code"));
		for (String manager : managers) notifications.create(new NotificationApplication.CreateCommand(manager, "WORKFLOW",
			"Order pending review: " + order.orderNo(), "Front desk submitted the order for review.", "/orders"));
		return order;
	}

	@PostMapping("/{orderId}/approval")
	OrderView approve(@PathVariable UUID orderId) {
		return customerOrders.approve(orderId);
	}

	@PostMapping("/{orderId}/customer-manager-review")
	OrderView reviewByCustomerManager(@PathVariable UUID orderId, @Valid @RequestBody CustomerManagerReviewRequest request) {
		Integer reviewer = jdbc.queryForObject("select count(*) from organization_member_role where employee_code = ? and role_code in ('CUSTOMER_MANAGER', 'SYSTEM_ADMIN')", Integer.class, request.managerCode().trim().toUpperCase());
		if (reviewer == null || reviewer == 0) throw com.renyi.mes.common.DomainException.forbidden("CUSTOMER_MANAGER_REVIEW_FORBIDDEN", "Only customer managers may review orders");
		return releaseIfApproved(customerOrders.reviewByCustomerManager(orderId, request.managerCode(), request.note()));
	}

	@PostMapping("/{orderId}/customer-manager-return")
	OrderView returnByCustomerManager(@PathVariable UUID orderId, @Valid @RequestBody CustomerManagerReturnRequest request) {
		Integer reviewer = jdbc.queryForObject("select count(*) from organization_member_role where employee_code = ? and role_code in ('CUSTOMER_MANAGER', 'SYSTEM_ADMIN')", Integer.class, request.managerCode().trim().toUpperCase());
		if (reviewer == null || reviewer == 0) throw com.renyi.mes.common.DomainException.forbidden("CUSTOMER_MANAGER_RETURN_FORBIDDEN", "Only customer managers may return orders");
		OrderView order = customerOrders.returnByCustomerManager(orderId,
			new CustomerOrderApplication.CustomerManagerReturnCommand(request.managerCode(), request.reason()));
		if (order.createdBy() != null && !order.createdBy().isBlank()) {
			notifications.create(new NotificationApplication.CreateCommand(order.createdBy(), "WORKFLOW",
				"Customer manager returned order: " + order.orderNo(), request.reason(), "/orders"));
		}
		return order;
	}

	@PostMapping("/{orderId}/general-manager-review")
	OrderView reviewByGeneralManager(@PathVariable UUID orderId, @Valid @RequestBody GeneralManagerReviewRequest request) {
		Integer reviewer = jdbc.queryForObject("select count(*) from organization_member_role where employee_code = ? and role_code in ('GENERAL_MANAGER', 'SYSTEM_ADMIN')", Integer.class, request.managerCode().trim().toUpperCase());
		if (reviewer == null || reviewer == 0) throw com.renyi.mes.common.DomainException.forbidden("GENERAL_MANAGER_REVIEW_FORBIDDEN", "Only general managers may review orders");
		return releaseIfApproved(customerOrders.reviewByGeneralManager(orderId, request.managerCode(), request.note()));
	}

	@PostMapping("/{orderId}/general-manager-return")
	OrderView returnByGeneralManager(@PathVariable UUID orderId, @Valid @RequestBody GeneralManagerReturnRequest request) {
		Integer reviewer = jdbc.queryForObject("select count(*) from organization_member_role where employee_code = ? and role_code in ('GENERAL_MANAGER', 'SYSTEM_ADMIN')", Integer.class, request.managerCode().trim().toUpperCase());
		if (reviewer == null || reviewer == 0) throw com.renyi.mes.common.DomainException.forbidden("GENERAL_MANAGER_RETURN_FORBIDDEN", "Only general managers may return orders");
		OrderView order = customerOrders.returnByGeneralManager(orderId, new CustomerOrderApplication.GeneralManagerReturnCommand(request.managerCode(), request.reason()));
		if (order.createdBy() != null && !order.createdBy().isBlank()) notifications.create(new NotificationApplication.CreateCommand(order.createdBy(), "WORKFLOW",
			"General manager returned order: " + order.orderNo(), request.reason(), "/orders"));
		return order;
	}

	@PostMapping("/{orderId}/engineering-confirmation")
	OrderView confirmEngineering(@PathVariable UUID orderId,
			@Valid @RequestBody EngineeringConfirmationRequest request) {
		return releaseIfApproved(customerOrders.confirmEngineering(orderId,
			new CustomerOrderApplication.EngineeringConfirmationCommand(request.engineerCode(),
				request.processCardVersion(), request.engineeringParameters(), request.engineeringOperationParameters(),
				request.lines() == null ? List.of() : request.lines().stream()
					.map(line -> new CustomerOrderApplication.EngineeringLineConfirmation(line.orderLineId(), line.processCardVersion(),
						line.engineeringParameters(), line.engineeringOperationParameters())).toList())));
	}

	@PostMapping("/{orderId}/engineering-return")
	OrderView returnForEngineering(@PathVariable UUID orderId, @Valid @RequestBody EngineeringReturnRequest request) {
		Integer engineer = jdbc.queryForObject("""
			select count(*) from organization_member_role where employee_code = ? and role_code in ('PROCESS_ENGINEER', 'SYSTEM_ADMIN')
			""", Integer.class, request.engineerCode().trim().toUpperCase());
		if (engineer == null || engineer == 0) {
			throw com.renyi.mes.common.DomainException.forbidden("ENGINEERING_RETURN_FORBIDDEN", "Only a process engineer may return an order");
		}
		OrderView order = customerOrders.returnForEngineering(orderId,
			new CustomerOrderApplication.EngineeringReturnCommand(request.engineerCode(), request.reason()));
		if (order.createdBy() != null && !order.createdBy().isBlank()) {
			notifications.create(new NotificationApplication.CreateCommand(order.createdBy(), "WORKFLOW",
				"Engineering returned order: " + order.orderNo(), request.reason(), "/orders"));
		}
		return order;
	}

	@PostMapping("/{orderId}/default-process-release")
	OrderView authorizeDefaultProcess(@PathVariable UUID orderId, @Valid @RequestBody DefaultProcessReleaseRequest request) {
		Integer supervisor = jdbc.queryForObject("""
			select count(*) from organization_member_role
			where employee_code = ? and role_code in ('PRODUCTION_MANAGER', 'WORKSHOP_SUPERVISOR',
				'MID_WAX_SUPERVISOR', 'LOW_WAX_SUPERVISOR', 'MID_SHELL_SUPERVISOR', 'LOW_SHELL_SUPERVISOR',
				'POST_PROCESS_SUPERVISOR', 'FINISHING_SUPERVISOR', 'SYSTEM_ADMIN')
			""", Integer.class, request.supervisorCode().trim().toUpperCase());
		if (supervisor == null || supervisor == 0) {
			throw com.renyi.mes.common.DomainException.forbidden("DEFAULT_PROCESS_RELEASE_FORBIDDEN", "Only a production supervisor may start under the controlled default process");
		}
		OrderView before = customerOrders.getOrder(orderId);
		String version = request.processCardVersion() == null || request.processCardVersion().isBlank()
			? "DEFAULT-" + before.routeType().name() + "-V1" : request.processCardVersion();
		OrderView authorized = customerOrders.authorizeDefaultProcess(orderId,
			new CustomerOrderApplication.DefaultProcessAuthorizationCommand(request.supervisorCode(), version,
				request.engineeringParameters(), request.engineeringOperationParameters()));
		List<String> engineers = jdbc.query("""
			select distinct mr.employee_code from organization_member_role mr
			join organization_member m on m.employee_code = mr.employee_code
			where mr.role_code = 'PROCESS_ENGINEER' and m.active = true
			""", (rs, row) -> rs.getString("employee_code"));
		for (String engineer : engineers) {
			notifications.create(new NotificationApplication.CreateCommand(engineer, "WORKFLOW",
				"Default process started: " + before.orderNo(),
				"Production has started under " + version + ". Confirm the dedicated card; the issued work order keeps its original snapshot.", "/orders"));
		}
		return releaseIfApproved(authorized);
	}

	@PostMapping("/{orderId}/engineering-reminders")
	OrderView remindEngineering(@PathVariable UUID orderId, @Valid @RequestBody EngineeringReminderRequest request) {
		OrderView order = customerOrders.getOrder(orderId);
		if (order.status() != com.renyi.mes.customerorder.OrderStatus.SUBMITTED) {
			throw com.renyi.mes.common.DomainException.conflict("ORDER_ENGINEERING_ALREADY_CONFIRMED", "工程已确认，无需催办");
		}
		Integer supervisorCount = jdbc.queryForObject("""
			select count(*) from organization_member_role
			where employee_code = ? and role_code in ('PRODUCTION_MANAGER', 'WORKSHOP_SUPERVISOR',
				'MID_WAX_SUPERVISOR', 'LOW_WAX_SUPERVISOR', 'MID_SHELL_SUPERVISOR', 'LOW_SHELL_SUPERVISOR',
				'POST_PROCESS_SUPERVISOR', 'FINISHING_SUPERVISOR')
			""", Integer.class, request.supervisorCode().trim().toUpperCase());
		if (supervisorCount == null || supervisorCount == 0) {
			throw com.renyi.mes.common.DomainException.forbidden("ORDER_ENGINEERING_REMINDER_FORBIDDEN", "仅生产主管可催办工程确认");
		}
		jdbc.update("insert into order_engineering_reminder (id, order_id, supervisor_code, reminded_at) values (?, ?, ?, current_timestamp)",
			UUID.randomUUID(), orderId, request.supervisorCode().trim().toUpperCase());
		List<String> engineers = jdbc.query("""
			select distinct mr.employee_code
			from organization_member_role mr
			join organization_member m on m.employee_code = mr.employee_code
			where mr.role_code = 'PROCESS_ENGINEER' and m.active = true
			""", (rs, rowNum) -> rs.getString("employee_code"));
		for (String engineer : engineers) {
			notifications.create(new NotificationApplication.CreateCommand(engineer, "SYSTEM",
				"待工程确认订单：" + order.orderNo(),
				"生产主管催办，请补充订单专属工艺参数并确认：" + order.orderNo(),
				"/orders"));
		}
		List<String> managers = jdbc.query("""
			select distinct mr.employee_code from organization_member_role mr
			join organization_member m on m.employee_code = mr.employee_code
			where mr.role_code in ('GENERAL_MANAGER', 'SYSTEM_ADMIN') and m.active = true
			""", (rs, rowNum) -> rs.getString("employee_code"));
		for (String manager : managers) {
			notifications.create(new NotificationApplication.CreateCommand(manager, "SYSTEM",
				"工程确认催办记录：" + order.orderNo(), "生产主管已催办工程确认：" + order.orderNo(), "/orders"));
		}
		return order;
	}

	record CreateOrderRequest(
		@Size(max = 64) String orderNo,
		@NotNull UUID customerId,
		@NotNull OrderPriority priority,
		LocalDate requestedDeliveryDate,
		@Size(max = 500) String remark,
		@Size(max = 64) String createdBy,
		@Size(max = 3000000) String orderDrawingUrl,
		@Size(max = 3000000) String contractAttachmentUrl,
		@NotEmpty List<@Valid CreateOrderLineRequest> lines
	) {
	}

	record CreateOrderLineRequest(
		@NotNull UUID productId,
		@NotNull @DecimalMin(value = "1") @Digits(integer = 19, fraction = 0) BigDecimal quantity,
		@NotBlank @Size(max = 16) String unit,
		@DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal salesUnitPrice,
		@Pattern(regexp = "TON|KG|PCS|SET|EA") String salesPriceUnit,
		@Size(max = 160) String productMaterial
	) {
	}

	record UpdateOrderRequest(
		@NotBlank @Size(max = 64) String editorCode,
		@NotNull UUID customerId,
		@NotNull OrderPriority priority,
		LocalDate requestedDeliveryDate,
		@Size(max = 500) String remark,
		@Size(max = 3000000) String orderDrawingUrl,
		@Size(max = 3000000) String contractAttachmentUrl,
		@NotEmpty List<@Valid CreateOrderLineRequest> lines
	) { }
	record SubmitOrderRequest(@NotBlank @Size(max = 64) String editorCode) { }

	record EngineeringConfirmationRequest(
		@NotBlank @Size(max = 64) String engineerCode,
		@Size(max = 64) String processCardVersion,
		@Size(max = 2000) String engineeringParameters,
		@Size(max = 10000) String engineeringOperationParameters,
		List<EngineeringLineRequest> lines
	) {
	}

	record EngineeringLineRequest(
		@NotNull UUID orderLineId,
		@Size(max = 64) String processCardVersion,
		@NotBlank @Size(max = 2000) String engineeringParameters,
		@Size(max = 10000) String engineeringOperationParameters
	) { }

	record EngineeringReminderRequest(@NotBlank @Size(max = 64) String supervisorCode) {
	}

	record EngineeringReturnRequest(@NotBlank @Size(max = 64) String engineerCode,
		@NotBlank @Size(max = 500) String reason) { }

	record DefaultProcessReleaseRequest(
		@NotBlank @Size(max = 64) String supervisorCode,
		@Size(max = 64) String processCardVersion,
		@NotBlank @Size(max = 2000) String engineeringParameters,
		@Size(max = 10000) String engineeringOperationParameters
	) { }

	record CustomerManagerReviewRequest(@NotBlank @Size(max = 64) String managerCode, @Size(max = 500) String note) {
	}

	record CustomerManagerReturnRequest(@NotBlank @Size(max = 64) String managerCode,
		@NotBlank @Size(max = 500) String reason) { }

	record GeneralManagerReviewRequest(@NotBlank @Size(max = 64) String managerCode, @Size(max = 500) String note) {
	}
	record GeneralManagerReturnRequest(@NotBlank @Size(max = 64) String managerCode, @NotBlank @Size(max = 500) String reason) { }

	private void requireFrontDeskEditor(String employeeCode) {
		Integer editor = jdbc.queryForObject("select count(*) from organization_member_role where employee_code = ? and role_code in ('FRONT_DESK_CLERK', 'SYSTEM_ADMIN')", Integer.class, employeeCode.trim().toUpperCase());
		if (editor == null || editor == 0) throw com.renyi.mes.common.DomainException.forbidden("ORDER_DRAFT_EDIT_FORBIDDEN", "Only front desk staff may edit or submit order drafts");
	}

	private OrderView releaseIfApproved(OrderView order) {
		if (order.status() == com.renyi.mes.customerorder.OrderStatus.APPROVED) {
			orderRelease.release(order.id());
			return customerOrders.getOrder(order.id());
		}
		return order;
	}
}
