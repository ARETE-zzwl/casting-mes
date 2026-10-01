package com.renyi.mes.outsourcing;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.BusinessAccess;
import com.renyi.mes.common.OutsourcingProductionPort;
import com.renyi.mes.common.ProductionTaskPort.TaskSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Service
public class OutsourcingApplication {

	private final JdbcTemplate jdbc;
	private final OutsourcingProductionPort production;
	private final BusinessAccess access;

	public OutsourcingApplication(JdbcTemplate jdbc, OutsourcingProductionPort production, BusinessAccess access) {
		this.jdbc = jdbc;
		this.production = production;
		this.access = access;
	}

	@Transactional
	public SupplierView createSupplier(SupplierCommand command) {
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			insert into outsourcing_supplier (
				id, code, name, contact_name, contact_phone, active, created_at
			) values (?, ?, ?, ?, ?, true, ?)
			""",
			id,
			normalize(command.code()),
			command.name().trim(),
			blankToNull(command.contactName()),
			blankToNull(command.contactPhone()),
			Timestamp.from(now)
		);
		return requireSupplier(id);
	}

	@Transactional
	public OrderView createOrder(OrderCommand command) {
		if (command.planningTaskId() != null) access.requireManageTask(command.planningTaskId());
		SupplierView supplier = requireSupplier(command.supplierId());
		TaskSnapshot linkedTask = command.planningTaskId() == null ? null : production.getLinkedTask(command.planningTaskId());
		validateLinkedTask(command, linkedTask);
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			insert into outsourcing_order (
				id, order_no, supplier_id, supplier_code, supplier_name, item_code, item_name,
				quantity, received_quantity, unit, due_date, status, remark, planning_task_id, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 'DRAFT', ?, ?, ?, ?)
			""",
			id,
			normalize(command.orderNo()),
			supplier.id(),
			supplier.code(),
			supplier.name(),
			normalize(command.itemCode()),
			command.itemName().trim(),
			command.quantity(),
			normalize(command.unit()),
			command.dueDate(),
			blankToNull(command.remark()),
			command.planningTaskId(),
			Timestamp.from(now),
			Timestamp.from(now)
		);
		return requireOrder(id);
	}

	@Transactional
	public OrderView transition(UUID orderId, MilestoneCommand command) {
		access.requireOutsource(orderId);
		OrderView order = requireOrderForUpdate(orderId);
		String requested = normalize(command.nextStatus());
		String next = requested;
		BigDecimal received = order.receivedQuantity();
		BigDecimal increment = command.receivedQuantity() == null ? BigDecimal.ZERO : command.receivedQuantity();

		switch (requested) {
			case "SENT" -> requireStatus(order.status(), "DRAFT");
			case "IN_PROGRESS" -> requireStatus(order.status(), "SENT");
			case "RECEIVED" -> {
				if (!List.of("SENT", "IN_PROGRESS").contains(order.status())) {
					throw DomainException.conflict("OUTSOURCING_STATUS_INVALID", "当前外协单不能收货");
				}
				if (increment.signum() <= 0) {
					throw DomainException.badRequest("RECEIVED_QUANTITY_REQUIRED", "收货数量必须大于零");
				}
				received = received.add(increment);
				if (received.compareTo(order.quantity()) > 0) {
					throw DomainException.conflict("OUTSOURCING_RECEIPT_EXCEEDED", "累计收货数不能超过外协数量");
				}
				next = received.compareTo(order.quantity()) == 0 ? "RECEIVED" : "IN_PROGRESS";
			}
			case "CLOSED" -> requireStatus(order.status(), "RECEIVED");
			case "CANCELLED" -> {
				if (List.of("RECEIVED", "CLOSED", "CANCELLED").contains(order.status()) || received.signum() > 0) {
					throw DomainException.conflict("OUTSOURCING_CANCEL_INVALID", "已收货或已关闭的外协单不能取消");
				}
			}
			default -> throw DomainException.badRequest("OUTSOURCING_STATUS_UNKNOWN", "未知的外协状态");
		}

		Instant now = Instant.now();
		if (order.planningTaskId() != null && "SENT".equals(requested)) {
			production.completeOperation(order.planningTaskId(), "OUTSOURCE_DISPATCH", command.operatorCode());
		}
		if (order.planningTaskId() != null && "IN_PROGRESS".equals(requested)) {
			production.startOperation(order.planningTaskId(), "OUTSOURCE_PROGRESS", command.operatorCode());
		}
		if (order.planningTaskId() != null && "RECEIVED".equals(next)) {
			production.completeOperation(order.planningTaskId(), "OUTSOURCE_PROGRESS", command.operatorCode());
		}
		if (order.planningTaskId() != null && "CLOSED".equals(requested)
				&& !production.isOperationCompleted(order.planningTaskId(), "INCOMING_INSPECTION")) {
			throw DomainException.conflict("OUTSOURCING_INSPECTION_REQUIRED", "来料检验完成后才能关闭关联外协单");
		}
		jdbc.update("""
			update outsourcing_order set status = ?, received_quantity = ?, updated_at = ? where id = ?
			""", next, received, Timestamp.from(now), orderId);
		jdbc.update("""
			insert into outsourcing_milestone (
				id, order_id, from_status, to_status, received_quantity, note, operator_code, occurred_at
			) values (?, ?, ?, ?, ?, ?, ?, ?)
			""",
			UUID.randomUUID(),
			orderId,
			order.status(),
			next,
			increment,
			blankToNull(command.note()),
			normalize(command.operatorCode()),
			Timestamp.from(now)
		);
		return requireOrder(orderId);
	}

	@Transactional(readOnly = true)
	public List<SupplierView> listSuppliers() {
		return jdbc.query("""
			select id, code, name, contact_name, contact_phone, active, created_at
			from outsourcing_supplier order by code
			""", OutsourcingApplication::mapSupplier);
	}

	@Transactional(readOnly = true)
	public List<OrderView> listOrders() {
		return jdbc.query("""
			select id, order_no, supplier_id, supplier_code, supplier_name, item_code, item_name,
				quantity, received_quantity, unit, due_date, status, remark, planning_task_id, created_at, updated_at
			from outsourcing_order order by created_at desc
			""", OutsourcingApplication::mapOrder).stream().filter(order -> access.canReadOutsource(order.id())).toList();
	}

	@Transactional(readOnly = true)
	public List<TaskSnapshot> readyProductionTasks(String operatorCode) {
		operatorCode = access.viewer(operatorCode);
		List<UUID> linkedTaskIds = jdbc.query("select planning_task_id from outsourcing_order where planning_task_id is not null",
			(rs, rowNum) -> rs.getObject(1, UUID.class));
		return production.readyForDispatch(operatorCode).stream()
			.filter(task -> !linkedTaskIds.contains(task.id()))
			.toList();
	}

	@Transactional(readOnly = true)
	public List<MilestoneView> milestones(UUID orderId) {
		requireOrder(orderId);
		return jdbc.query("""
			select id, order_id, from_status, to_status, received_quantity, note, operator_code, occurred_at
			from outsourcing_milestone where order_id = ? order by occurred_at
			""", OutsourcingApplication::mapMilestone, orderId);
	}

	private SupplierView requireSupplier(UUID id) {
		List<SupplierView> rows = jdbc.query("""
			select id, code, name, contact_name, contact_phone, active, created_at
			from outsourcing_supplier where id = ?
			""", OutsourcingApplication::mapSupplier, id);
		if (rows.isEmpty()) {
			throw DomainException.notFound("SUPPLIER_NOT_FOUND", "供应商不存在");
		}
		return rows.getFirst();
	}

	private OrderView requireOrder(UUID id) {
		List<OrderView> rows = jdbc.query("""
			select id, order_no, supplier_id, supplier_code, supplier_name, item_code, item_name,
				quantity, received_quantity, unit, due_date, status, remark, planning_task_id, created_at, updated_at
			from outsourcing_order where id = ?
			""", OutsourcingApplication::mapOrder, id);
		if (rows.isEmpty()) {
			throw DomainException.notFound("OUTSOURCING_ORDER_NOT_FOUND", "外协单不存在");
		}
		return rows.getFirst();
	}

	private OrderView requireOrderForUpdate(UUID id) {
		List<OrderView> rows = jdbc.query("""
			select id, order_no, supplier_id, supplier_code, supplier_name, item_code, item_name,
				quantity, received_quantity, unit, due_date, status, remark, planning_task_id, created_at, updated_at
			from outsourcing_order where id = ? for update
			""", OutsourcingApplication::mapOrder, id);
		if (rows.isEmpty()) {
			throw DomainException.notFound("OUTSOURCING_ORDER_NOT_FOUND", "外协单不存在");
		}
		return rows.getFirst();
	}

	private static void requireStatus(String actual, String expected) {
		if (!actual.equals(expected)) {
			throw DomainException.conflict("OUTSOURCING_STATUS_INVALID", "当前外协状态不允许该操作");
		}
	}

	private static void validateLinkedTask(OrderCommand command, TaskSnapshot task) {
		if (task == null) return;
		if (!"READY".equals(task.status())) {
			throw DomainException.conflict("OUTSOURCING_TASK_NOT_READY", "关联的砂型外协发出任务当前不可用");
		}
		if (command.quantity().compareTo(task.plannedQuantity()) != 0
				|| !normalize(command.itemCode()).equals(task.productCode())
				|| !command.itemName().trim().equals(task.productName())) {
			throw DomainException.badRequest("OUTSOURCING_TASK_CONTENT_MISMATCH", "关联任务的产品与数量必须与外协单一致");
		}
	}

	private static SupplierView mapSupplier(ResultSet rs, int rowNum) throws SQLException {
		return new SupplierView(
			rs.getObject("id", UUID.class),
			rs.getString("code"),
			rs.getString("name"),
			rs.getString("contact_name"),
			rs.getString("contact_phone"),
			rs.getBoolean("active"),
			rs.getTimestamp("created_at").toInstant()
		);
	}

	private static OrderView mapOrder(ResultSet rs, int rowNum) throws SQLException {
		return new OrderView(
			rs.getObject("id", UUID.class),
			rs.getString("order_no"),
			rs.getObject("supplier_id", UUID.class),
			rs.getString("supplier_code"),
			rs.getString("supplier_name"),
			rs.getString("item_code"),
			rs.getString("item_name"),
			rs.getBigDecimal("quantity"),
			rs.getBigDecimal("received_quantity"),
			rs.getString("unit"),
			rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate(),
			 rs.getString("status"),
			rs.getString("remark"),
			rs.getObject("planning_task_id", UUID.class),
			rs.getTimestamp("created_at").toInstant(),
			rs.getTimestamp("updated_at").toInstant()
		);
	}

	private static MilestoneView mapMilestone(ResultSet rs, int rowNum) throws SQLException {
		return new MilestoneView(
			rs.getObject("id", UUID.class),
			rs.getObject("order_id", UUID.class),
			rs.getString("from_status"),
			rs.getString("to_status"),
			rs.getBigDecimal("received_quantity"),
			rs.getString("note"),
			rs.getString("operator_code"),
			rs.getTimestamp("occurred_at").toInstant()
		);
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	public record SupplierCommand(String code, String name, String contactName, String contactPhone) {
	}

	public record OrderCommand(
		String orderNo,
		UUID supplierId,
		String itemCode,
		String itemName,
		BigDecimal quantity,
		String unit,
		LocalDate dueDate,
		String remark,
		UUID planningTaskId
	) {
		public OrderCommand(String orderNo, UUID supplierId, String itemCode, String itemName, BigDecimal quantity,
				String unit, LocalDate dueDate, String remark) {
			this(orderNo, supplierId, itemCode, itemName, quantity, unit, dueDate, remark, null);
		}
	}

	public record MilestoneCommand(
		String nextStatus,
		BigDecimal receivedQuantity,
		String note,
		String operatorCode
	) {
	}

	public record SupplierView(
		UUID id,
		String code,
		String name,
		String contactName,
		String contactPhone,
		boolean active,
		Instant createdAt
	) {
	}

	public record OrderView(
		UUID id,
		String orderNo,
		UUID supplierId,
		String supplierCode,
		String supplierName,
		String itemCode,
		String itemName,
		BigDecimal quantity,
		BigDecimal receivedQuantity,
		String unit,
		LocalDate dueDate,
		String status,
		String remark,
		UUID planningTaskId,
		Instant createdAt,
		Instant updatedAt
	) {
	}

	public record MilestoneView(
		UUID id,
		UUID orderId,
		String fromStatus,
		String toStatus,
		BigDecimal receivedQuantity,
		String note,
		String operatorCode,
		Instant occurredAt
	) {
	}
}

@RestController
@RequestMapping("/api/outsourcing")
class OutsourcingController {

	private final OutsourcingApplication outsourcing;

	OutsourcingController(OutsourcingApplication outsourcing) {
		this.outsourcing = outsourcing;
	}

	@GetMapping("/suppliers")
	List<OutsourcingApplication.SupplierView> suppliers() {
		return outsourcing.listSuppliers();
	}

	@PostMapping("/suppliers")
	@ResponseStatus(HttpStatus.CREATED)
	OutsourcingApplication.SupplierView createSupplier(@Valid @RequestBody SupplierRequest request) {
		return outsourcing.createSupplier(new OutsourcingApplication.SupplierCommand(
			request.code(),
			request.name(),
			request.contactName(),
			request.contactPhone()
		));
	}

	@GetMapping("/orders")
	List<OutsourcingApplication.OrderView> orders() {
		return outsourcing.listOrders();
	}

	@GetMapping("/ready-production-tasks")
	List<TaskSnapshot> readyProductionTasks(@org.springframework.web.bind.annotation.RequestParam String operatorCode) {
		return outsourcing.readyProductionTasks(operatorCode);
	}

	@PostMapping("/orders")
	@ResponseStatus(HttpStatus.CREATED)
	OutsourcingApplication.OrderView createOrder(@Valid @RequestBody OrderRequest request) {
		return outsourcing.createOrder(new OutsourcingApplication.OrderCommand(
			request.orderNo(),
			request.supplierId(),
			request.itemCode(),
			request.itemName(),
			request.quantity(),
			request.unit(),
			request.dueDate(),
			request.remark(),
			request.planningTaskId()
		));
	}

	@PostMapping("/orders/{orderId}/milestones")
	OutsourcingApplication.OrderView transition(
		@PathVariable UUID orderId,
		@Valid @RequestBody MilestoneRequest request
	) {
		return outsourcing.transition(orderId, new OutsourcingApplication.MilestoneCommand(
			request.nextStatus(),
			request.receivedQuantity(),
			request.note(),
			request.operatorCode()
		));
	}

	@GetMapping("/orders/{orderId}/milestones")
	List<OutsourcingApplication.MilestoneView> milestones(@PathVariable UUID orderId) {
		return outsourcing.milestones(orderId);
	}

	record SupplierRequest(
		@NotBlank @Size(max = 64) String code,
		@NotBlank @Size(max = 160) String name,
		@Size(max = 100) String contactName,
		@Size(max = 40) String contactPhone
	) {
	}

	record OrderRequest(
		@NotBlank @Size(max = 64) String orderNo,
		@NotNull UUID supplierId,
		@NotBlank @Size(max = 64) String itemCode,
		@NotBlank @Size(max = 160) String itemName,
		@NotNull @DecimalMin("0.001") BigDecimal quantity,
		@NotBlank @Size(max = 16) String unit,
		LocalDate dueDate,
		@Size(max = 500) String remark,
		UUID planningTaskId
	) {
	}

	record MilestoneRequest(
		@NotBlank String nextStatus,
		@DecimalMin("0.001") BigDecimal receivedQuantity,
		@Size(max = 500) String note,
		@NotBlank @Size(max = 64) String operatorCode
	) {
	}
}
