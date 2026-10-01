package com.renyi.mes.customerorder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.customerorder.internal.CustomerEntity;
import com.renyi.mes.customerorder.internal.CustomerOrderEntity;
import com.renyi.mes.customerorder.internal.CustomerOrderLineEntity;
import com.renyi.mes.customerorder.internal.CustomerOrderLineRepository;
import com.renyi.mes.customerorder.internal.CustomerOrderRepository;
import com.renyi.mes.customerorder.internal.CustomerRepository;
import com.renyi.mes.engineering.ProductApplication;
import com.renyi.mes.engineering.RouteType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
public class CustomerOrderApplication {

	private static final List<String> SALES_PRICE_UNITS = List.of("TON", "KG", "PCS", "SET", "EA");

	private final CustomerRepository customers;
	private final CustomerOrderRepository orders;
	private final CustomerOrderLineRepository lines;
	private final ProductApplication products;
	private final JdbcTemplate jdbc;

	public CustomerOrderApplication(
		CustomerRepository customers,
		CustomerOrderRepository orders,
		CustomerOrderLineRepository lines,
		ProductApplication products,
		JdbcTemplate jdbc
	) {
		this.customers = customers;
		this.orders = orders;
		this.lines = lines;
		this.products = products;
		this.jdbc = jdbc;
	}

	@Transactional
	public CustomerView createCustomer(CreateCustomerCommand command) {
		String code = command.code() == null || command.code().isBlank() ? generatedCode("CUS") : normalizeCode(command.code());
		if (customers.existsByCodeIgnoreCase(code)) {
			throw DomainException.conflict("CUSTOMER_CODE_EXISTS", "客户编码已存在");
		}

		CustomerEntity customer = new CustomerEntity(
			UUID.randomUUID(),
			code,
			command.name().trim(),
			trimToNull(command.contactName()),
			trimToNull(command.contactPhone()),
			trimToNull(command.salesOwner()),
			true,
			Instant.now()
		);
		return toCustomerView(customers.save(customer));
	}

	@Transactional(readOnly = true)
	public List<CustomerView> listCustomers() {
		return customers.findAllByOrderByCreatedAtDesc().stream()
			.map(CustomerOrderApplication::toCustomerView)
			.toList();
	}

	@Transactional
	public OrderView createOrder(CreateOrderCommand command) {
		String orderNo = command.orderNo() == null || command.orderNo().isBlank() ? generatedCode("SO") : normalizeCode(command.orderNo());
		if (orders.existsByOrderNoIgnoreCase(orderNo)) {
			throw DomainException.conflict("ORDER_NO_EXISTS", "订单号已存在");
		}

		CustomerEntity customer = customers.findById(command.customerId())
			.filter(CustomerEntity::active)
			.orElseThrow(() -> DomainException.notFound("CUSTOMER_NOT_FOUND", "有效客户不存在"));

		List<ResolvedOrderLine> resolvedLines = command.lines().stream()
			.map(this::resolveOrderLine)
			.toList();
		if (resolvedLines.stream().anyMatch(line -> line.command().quantity().signum() < 1
			|| line.command().quantity().stripTrailingZeros().scale() > 0)) {
			throw DomainException.badRequest("ORDER_QUANTITY_INTEGER_REQUIRED", "订单产品数量必须为大于等于 1 的整数");
		}
		if (resolvedLines.stream().anyMatch(line -> {
			BigDecimal price = line.command().salesUnitPrice();
			String priceUnit = line.command().salesPriceUnit();
			if (price == null && (priceUnit == null || priceUnit.isBlank())) return false;
			return price == null || price.signum() <= 0 || priceUnit == null
				|| !SALES_PRICE_UNITS.contains(normalizeCode(priceUnit));
		})) {
			throw DomainException.badRequest("ORDER_SALES_PRICE_INVALID", "成交单价和计价单位需要同时填写，且单价必须大于零");
		}
		RouteType routeType = resolvedLines.getFirst().product().routeType();
		if (resolvedLines.stream().anyMatch(line -> line.product().routeType() != routeType)) {
			throw DomainException.badRequest("ORDER_ROUTE_MIXED",
				"一张订单只能归属一条生产线。请分别创建中温蜡、低温蜡或砂型外协订单。");
		}
		if (resolvedLines.stream().anyMatch(line -> !line.product().active())) {
			throw DomainException.conflict("PRODUCT_INACTIVE", "订单产品已停用");
		}

		Instant now = Instant.now();
		CustomerOrderEntity order = orders.save(new CustomerOrderEntity(
			UUID.randomUUID(),
			orderNo,
			customer.id(),
			customer.code(),
			customer.name(),
			OrderStatus.DRAFT,
			command.priority(),
			command.requestedDeliveryDate(),
			trimToNull(command.remark()),
			now
		));
		order.setCreatedBy(trimToNull(command.createdBy()));
		order.setSalesOwner(trimToNull(customer.salesOwner()));
		order.setOrderDrawingUrl(trimToNull(command.orderDrawingUrl()));
		order.setContractAttachmentUrl(trimToNull(command.contractAttachmentUrl()));

		for (int index = 0; index < resolvedLines.size(); index++) {
			ResolvedOrderLine resolvedLine = resolvedLines.get(index);
			CreateOrderLineCommand line = resolvedLine.command();
			ProductApplication.ProductView product = resolvedLine.product();
			lines.save(new CustomerOrderLineEntity(
				UUID.randomUUID(),
				order.id(),
				index + 1,
				product.id(),
				product.code(),
				product.name(),
				product.routeType(),
				product.routeVersion(),
				product.modelImageUrl(),
				product.material(),
				line.quantity(),
				line.unit().trim().toUpperCase(Locale.ROOT),
				line.salesUnitPrice(),
				line.salesPriceUnit() == null || line.salesPriceUnit().isBlank() ? null : normalizeCode(line.salesPriceUnit())
			));
		}

		return getOrder(order.id());
	}

	@Transactional
	public OrderView approve(UUID orderId) {
		CustomerOrderEntity order = requireOrder(orderId);
		order.approve(Instant.now());
		return toOrderView(order, lines.findByOrderIdOrderByLineNo(order.id()));
	}

	@Transactional
	public OrderView updateDraft(UUID orderId, UpdateOrderCommand command) {
		CustomerOrderEntity order = requireOrder(orderId);
		CustomerEntity customer = customers.findById(command.customerId()).filter(CustomerEntity::active)
			.orElseThrow(() -> DomainException.notFound("CUSTOMER_NOT_FOUND", "有效客户不存在"));
		List<ResolvedOrderLine> resolvedLines = resolveOrderLines(command.lines());
		order.updateDraft(customer.id(), customer.code(), customer.name(), trimToNull(customer.salesOwner()), command.priority(),
			command.requestedDeliveryDate(), trimToNull(command.remark()), trimToNull(command.orderDrawingUrl()), trimToNull(command.contractAttachmentUrl()));
		jdbc.update("delete from order_mold_selection where order_id = ?", order.id());
		lines.deleteAll(lines.findByOrderIdOrderByLineNo(order.id()));
		lines.flush();
		for (int index = 0; index < resolvedLines.size(); index++) {
			ResolvedOrderLine resolvedLine = resolvedLines.get(index);
			CreateOrderLineCommand line = resolvedLine.command();
			ProductApplication.ProductView product = resolvedLine.product();
			lines.save(new CustomerOrderLineEntity(UUID.randomUUID(), order.id(), index + 1, product.id(), product.code(), product.name(),
				product.routeType(), product.routeVersion(), product.modelImageUrl(),
				product.material(),
				line.quantity(), line.unit().trim().toUpperCase(Locale.ROOT), line.salesUnitPrice(),
				line.salesPriceUnit() == null || line.salesPriceUnit().isBlank() ? null : normalizeCode(line.salesPriceUnit())));
		}
		return getOrder(order.id());
	}

	@Transactional
	public OrderView submit(UUID orderId) {
		CustomerOrderEntity order = requireOrder(orderId);
		order.submit(Instant.now());
		return toOrderView(order, lines.findByOrderIdOrderByLineNo(order.id()));
	}

	@Transactional
	public OrderView reviewByCustomerManager(UUID orderId, String managerCode, String note) {
		CustomerOrderEntity order = requireOrder(orderId);
		order.reviewByCustomerManager(normalizeCode(managerCode), trimToNull(note), Instant.now());
		return toOrderView(order, lines.findByOrderIdOrderByLineNo(order.id()));
	}

	@Transactional
	public OrderView returnByCustomerManager(UUID orderId, CustomerManagerReturnCommand command) {
		CustomerOrderEntity order = requireOrder(orderId);
		order.returnByCustomerManager(normalizeCode(command.managerCode()), command.reason().trim(), Instant.now());
		lines.findByOrderIdOrderByLineNo(order.id()).forEach(CustomerOrderLineEntity::clearProcessApproval);
		return toOrderView(order, lines.findByOrderIdOrderByLineNo(order.id()));
	}

	@Transactional
	public OrderView reviewByGeneralManager(UUID orderId, String managerCode, String note) {
		CustomerOrderEntity order = requireOrder(orderId);
		order.reviewByGeneralManager(normalizeCode(managerCode), trimToNull(note), Instant.now());
		return toOrderView(order, lines.findByOrderIdOrderByLineNo(order.id()));
	}

	@Transactional
	public OrderView returnByGeneralManager(UUID orderId, GeneralManagerReturnCommand command) {
		CustomerOrderEntity order = requireOrder(orderId);
		order.returnByGeneralManager(normalizeCode(command.managerCode()), command.reason().trim(), Instant.now());
		lines.findByOrderIdOrderByLineNo(order.id()).forEach(CustomerOrderLineEntity::clearProcessApproval);
		return toOrderView(order, lines.findByOrderIdOrderByLineNo(order.id()));
	}

	@Transactional
	public OrderView confirmEngineering(UUID orderId, EngineeringConfirmationCommand command) {
		CustomerOrderEntity order = requireOrder(orderId);
		List<CustomerOrderLineEntity> orderLines = lines.findByOrderIdOrderByLineNo(order.id());
		List<EngineeringLineConfirmation> requested = command.lines() == null || command.lines().isEmpty()
			? orderLines.stream().map(line -> new EngineeringLineConfirmation(line.id(), command.processCardVersion(),
				command.engineeringParameters(), command.engineeringOperationParameters())).toList()
			: command.lines();
		if (requested.size() != orderLines.size() || requested.stream().map(EngineeringLineConfirmation::orderLineId).distinct().count() != orderLines.size()) {
			throw DomainException.badRequest("ORDER_LINE_PROCESS_CARD_REQUIRED", "每个订单产品都必须填写独立工艺卡后才能工程确认");
		}
		Instant now = Instant.now();
		String engineer = normalizeCode(command.engineerCode());
		for (CustomerOrderLineEntity line : orderLines) {
			EngineeringLineConfirmation confirmation = requested.stream()
				.filter(item -> line.id().equals(item.orderLineId())).findFirst()
				.orElseThrow(() -> DomainException.badRequest("ORDER_LINE_PROCESS_CARD_REQUIRED", "订单产品缺少工艺卡"));
			if (confirmation.engineeringParameters() == null || confirmation.engineeringParameters().isBlank()) {
				throw DomainException.badRequest("ORDER_LINE_PROCESS_PARAMETERS_REQUIRED", "每个订单产品都必须填写工程总要求");
			}
			String version = confirmation.processCardVersion() == null || confirmation.processCardVersion().isBlank()
				? generatedCode("PC") : confirmation.processCardVersion().trim();
			line.confirmEngineering(engineer, version, confirmation.engineeringParameters().trim(),
				trimToNull(confirmation.engineeringOperationParameters()), now);
		}
		CustomerOrderLineEntity first = orderLines.getFirst();
		order.confirmEngineering(engineer, first.processCardVersion(), first.engineeringParameters(),
			first.engineeringOperationParameters(), now);
		return toOrderView(order, orderLines);
	}

	@Transactional
	public OrderView authorizeDefaultProcess(UUID orderId, DefaultProcessAuthorizationCommand command) {
		CustomerOrderEntity order = requireOrder(orderId);
		String version = command.processCardVersion() == null || command.processCardVersion().isBlank()
			? "DEFAULT-" + order.id().toString().substring(0, 8).toUpperCase(Locale.ROOT)
			: command.processCardVersion().trim();
		Instant now = Instant.now();
		String supervisor = normalizeCode(command.supervisorCode());
		List<CustomerOrderLineEntity> orderLines = lines.findByOrderIdOrderByLineNo(order.id());
		for (CustomerOrderLineEntity line : orderLines) {
			line.authorizeDefaultProcess(supervisor, version, command.engineeringParameters().trim(),
				trimToNull(command.engineeringOperationParameters()), now);
		}
		order.authorizeDefaultProcess(supervisor, version, command.engineeringParameters().trim(),
			trimToNull(command.engineeringOperationParameters()), now);
		return toOrderView(order, orderLines);
	}

	@Transactional
	public OrderView returnForEngineering(UUID orderId, EngineeringReturnCommand command) {
		CustomerOrderEntity order = requireOrder(orderId);
		order.returnForEngineering(normalizeCode(command.engineerCode()), command.reason().trim(), Instant.now());
		lines.findByOrderIdOrderByLineNo(order.id()).forEach(CustomerOrderLineEntity::clearProcessApproval);
		return toOrderView(order, lines.findByOrderIdOrderByLineNo(order.id()));
	}

	@Transactional(readOnly = true)
	public List<OrderView> listOrders(RouteType routeType) {
		return orders.findAllByOrderByCreatedAtDesc().stream()
			.map(order -> toOrderView(order, lines.findByOrderIdOrderByLineNo(order.id())))
			.filter(order -> routeType == null || routeType == order.routeType())
			.toList();
	}

	@Transactional(readOnly = true)
	public OrderView getOrder(UUID orderId) {
		CustomerOrderEntity order = requireOrder(orderId);
		return toOrderView(order, lines.findByOrderIdOrderByLineNo(order.id()));
	}

	@Transactional(readOnly = true)
	public OrderView getApprovedOrder(UUID orderId) {
		OrderView order = getOrder(orderId);
		if (order.status() != OrderStatus.APPROVED) {
			throw DomainException.conflict("ORDER_NOT_APPROVED", "订单必须审批后才能生成工单");
		}
		return order;
	}

	@Transactional
	public void markReleased(UUID orderId) {
		CustomerOrderEntity order = requireOrder(orderId);
		if (!order.reviewGatePassed()) {
			throw DomainException.conflict("ORDER_REVIEW_GATE_PENDING", "Engineering confirmation and either customer manager or general manager review are required before production release");
		}
		order.release(Instant.now());
	}

	@Transactional
	public OrderView lockReleasableOrder(UUID orderId) {
		CustomerOrderEntity order = orders.findLockedById(orderId)
			.orElseThrow(() -> DomainException.notFound("ORDER_NOT_FOUND", "订单不存在"));
		if (order.status() != OrderStatus.APPROVED && order.status() != OrderStatus.RELEASED) {
			throw DomainException.conflict("ORDER_NOT_APPROVED", "订单必须审批后才能生成工单");
		}
		return toOrderView(order, lines.findByOrderIdOrderByLineNo(order.id()));
	}

	private CustomerOrderEntity requireOrder(UUID orderId) {
		return orders.findById(orderId)
			.orElseThrow(() -> DomainException.notFound("ORDER_NOT_FOUND", "订单不存在"));
	}

	private static CustomerView toCustomerView(CustomerEntity customer) {
		return new CustomerView(
			customer.id(),
			customer.code(),
			customer.name(),
			customer.contactName(),
			customer.contactPhone(),
			customer.salesOwner(),
			customer.active(),
			customer.createdAt()
		);
	}

	private static OrderView toOrderView(CustomerOrderEntity order, List<CustomerOrderLineEntity> lines) {
		RouteType routeType = lines.isEmpty() ? null : lines.getFirst().routeType();
		if (lines.stream().anyMatch(line -> line.routeType() != routeType)) {
			throw DomainException.conflict("ORDER_ROUTE_MIXED",
				"订单存在跨生产线产品，需拆分后再继续处理：" + order.orderNo());
		}
		return new OrderView(
			order.id(),
			order.orderNo(),
			order.customerId(),
			order.customerCode(),
			order.customerName(),
			order.salesOwner(),
			order.status(),
			order.priority(),
			routeType,
			order.requestedDeliveryDate(),
			order.remark(),
			order.orderDrawingUrl(),
			order.contractAttachmentUrl(),
			order.createdBy(),
			order.processCardVersion(),
			order.engineeringParameters(),
			order.engineeringOperationParameters(),
			order.engineeringConfirmedBy(),
			order.engineeringConfirmedAt(),
			order.engineeringReturnedBy(),
			order.engineeringReturnedAt(),
			order.engineeringReturnReason(),
			order.engineeringReturnResolvedAt(),
			order.defaultProcessCardVersion(),
			order.defaultProcessReleasedBy(),
			order.defaultProcessReleasedAt(),
			lines.stream().map(CustomerOrderApplication::toLineView).toList(),
			order.createdAt(),
			order.approvedAt(),
			order.releasedAt(),
			order.customerManagerCode(),
			order.customerManagerReviewedAt(),
			order.customerManagerReviewNote(),
			order.customerManagerReturnedBy(),
			order.customerManagerReturnedAt(),
			order.customerManagerReturnReason(),
			order.customerManagerReturnResolvedAt(),
			order.generalManagerCode(),
			order.generalManagerReviewedAt(),
			order.generalManagerReviewNote(),
			order.generalManagerReturnedBy(),
			order.generalManagerReturnedAt(),
			order.generalManagerReturnReason(),
			order.generalManagerReturnResolvedAt()
		);
	}

	private static OrderLineView toLineView(CustomerOrderLineEntity line) {
		return new OrderLineView(
			line.id(),
			line.lineNo(),
			line.productId(),
			line.productCode(),
			line.productName(),
			line.routeType(),
			line.routeVersion(),
			line.modelImageUrl(),
			line.productMaterial(),
			line.orderedQuantity(),
			line.unit(),
			line.salesUnitPrice(),
			line.salesPriceUnit(),
			line.processCardVersion(),
			line.engineeringParameters(),
			line.engineeringOperationParameters(),
			line.engineeringConfirmedBy(),
			line.engineeringConfirmedAt(),
			line.defaultProcessCardVersion(),
			line.defaultProcessReleasedBy(),
			line.defaultProcessReleasedAt()
		);
	}

	private static String normalizeCode(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String generatedCode(String prefix) {
		return prefix + "-" + LocalDate.now().toString().replace("-", "") + "-"
			+ UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
	}

	private static String trimToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	private List<ResolvedOrderLine> resolveOrderLines(List<CreateOrderLineCommand> requestedLines) {
		List<ResolvedOrderLine> resolvedLines = requestedLines.stream().map(this::resolveOrderLine).toList();
		if (resolvedLines.stream().anyMatch(line -> line.command().quantity().signum() < 1 || line.command().quantity().stripTrailingZeros().scale() > 0)) {
			throw DomainException.badRequest("ORDER_QUANTITY_INTEGER_REQUIRED", "订单产品数量必须为大于等于 1 的整数");
		}
		if (resolvedLines.stream().anyMatch(line -> {
			BigDecimal price = line.command().salesUnitPrice();
			String priceUnit = line.command().salesPriceUnit();
			return price == null || price.signum() <= 0 || priceUnit == null || !SALES_PRICE_UNITS.contains(normalizeCode(priceUnit));
		})) throw DomainException.badRequest("ORDER_SALES_PRICE_INVALID", "成交单价和计价单位需要同时填写，且单价必须大于零");
		RouteType routeType = resolvedLines.getFirst().product().routeType();
		if (resolvedLines.stream().anyMatch(line -> line.product().routeType() != routeType)) throw DomainException.badRequest("ORDER_ROUTE_MIXED", "一张订单只能归属一条生产线");
		if (resolvedLines.stream().anyMatch(line -> !line.product().active())) throw DomainException.conflict("PRODUCT_INACTIVE", "订单产品已停用");
		return resolvedLines;
	}

	private ResolvedOrderLine resolveOrderLine(CreateOrderLineCommand line) {
		return new ResolvedOrderLine(line, products.resolveMaterialVariant(line.productId(), trimToNull(line.productMaterial())));
	}

	private record ResolvedOrderLine(CreateOrderLineCommand command, ProductApplication.ProductView product) {
	}

	public record CreateCustomerCommand(
		String code,
		String name,
		String contactName,
		String contactPhone,
		String salesOwner
	) {
		public CreateCustomerCommand(String code, String name, String contactName, String contactPhone) {
			this(code, name, contactName, contactPhone, null);
		}
	}

	public record CustomerView(
		UUID id,
		String code,
		String name,
		String contactName,
		String contactPhone,
		String salesOwner,
		boolean active,
		Instant createdAt
	) {
	}

	public record CreateOrderCommand(
		String orderNo,
		UUID customerId,
		OrderPriority priority,
		LocalDate requestedDeliveryDate,
		String remark,
		String createdBy,
		String orderDrawingUrl,
		String contractAttachmentUrl,
		List<CreateOrderLineCommand> lines
	) {
	}

	public record EngineeringConfirmationCommand(String engineerCode, String processCardVersion,
			String engineeringParameters, String engineeringOperationParameters, List<EngineeringLineConfirmation> lines) {
		public EngineeringConfirmationCommand(String engineerCode, String processCardVersion, String engineeringParameters,
				String engineeringOperationParameters) {
			this(engineerCode, processCardVersion, engineeringParameters, engineeringOperationParameters, List.of());
		}
		public EngineeringConfirmationCommand(String engineerCode, String processCardVersion, String engineeringParameters) {
			this(engineerCode, processCardVersion, engineeringParameters, null, List.of());
		}
	}

	public record EngineeringLineConfirmation(UUID orderLineId, String processCardVersion,
			String engineeringParameters, String engineeringOperationParameters) { }

	public record DefaultProcessAuthorizationCommand(String supervisorCode, String processCardVersion,
			String engineeringParameters, String engineeringOperationParameters) { }

	public record EngineeringReturnCommand(String engineerCode, String reason) { }

	public record CustomerManagerReturnCommand(String managerCode, String reason) { }

	public record CreateOrderLineCommand(UUID productId, BigDecimal quantity, String unit,
		BigDecimal salesUnitPrice, String salesPriceUnit, String productMaterial) {
		public CreateOrderLineCommand(UUID productId, BigDecimal quantity, String unit) {
			this(productId, quantity, unit, BigDecimal.ONE, "PCS", null);
		}
	}

	public record UpdateOrderCommand(UUID customerId, OrderPriority priority, LocalDate requestedDeliveryDate, String remark,
		String orderDrawingUrl, String contractAttachmentUrl, List<CreateOrderLineCommand> lines) { }
	public record GeneralManagerReturnCommand(String managerCode, String reason) { }

	public record OrderView(
		UUID id,
		String orderNo,
		UUID customerId,
		String customerCode,
		String customerName,
		String salesOwner,
		OrderStatus status,
		OrderPriority priority,
		RouteType routeType,
		LocalDate requestedDeliveryDate,
		String remark,
		String orderDrawingUrl,
		String contractAttachmentUrl,
		String createdBy,
		String processCardVersion,
		String engineeringParameters,
		String engineeringOperationParameters,
		String engineeringConfirmedBy,
		Instant engineeringConfirmedAt,
		String engineeringReturnedBy,
		Instant engineeringReturnedAt,
		String engineeringReturnReason,
		Instant engineeringReturnResolvedAt,
		String defaultProcessCardVersion,
		String defaultProcessReleasedBy,
		Instant defaultProcessReleasedAt,
		List<OrderLineView> lines,
		Instant createdAt,
		Instant approvedAt,
		Instant releasedAt,
		String customerManagerCode,
		Instant customerManagerReviewedAt,
		String customerManagerReviewNote,
		String customerManagerReturnedBy,
		Instant customerManagerReturnedAt,
		String customerManagerReturnReason,
		Instant customerManagerReturnResolvedAt,
		String generalManagerCode,
		Instant generalManagerReviewedAt,
		String generalManagerReviewNote,
		String generalManagerReturnedBy,
		Instant generalManagerReturnedAt,
		String generalManagerReturnReason,
		Instant generalManagerReturnResolvedAt
	) {
	}

	public record OrderLineView(
		UUID id,
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
		String salesPriceUnit,
		String processCardVersion,
		String engineeringParameters,
		String engineeringOperationParameters,
		String engineeringConfirmedBy,
		Instant engineeringConfirmedAt,
		String defaultProcessCardVersion,
		String defaultProcessReleasedBy,
		Instant defaultProcessReleasedAt
	) {
	}
}
