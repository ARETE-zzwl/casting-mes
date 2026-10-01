package com.renyi.mes.fulfillment;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.PageResult;
import com.renyi.mes.common.WarehouseAccessApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Service
public class FulfillmentApplication {

	private final JdbcTemplate jdbc;
	private final WarehouseAccessApplication warehouseAccess;

	public FulfillmentApplication(JdbcTemplate jdbc, WarehouseAccessApplication warehouseAccess) {
		this.jdbc = jdbc;
		this.warehouseAccess = warehouseAccess;
	}

	@Transactional
	public LotSubmission registerLot(RegisterLotCommand command) {
		warehouseAccess.requireFinishedGoodsReceipt(command.registeredBy(), command.warehouseCode());
		List<LotView> existing = jdbc.query("""
			select * from finished_goods_lot where operation_id = ?
			""", FulfillmentApplication::mapLot, command.operationId());
		if (!existing.isEmpty()) {
			return duplicateLot(existing.getFirst(), command);
		}

		FinalTaskContext task = requireFinalCompletedTask(command.taskId());
		existing = jdbc.query("""
			select * from finished_goods_lot where operation_id = ?
			""", FulfillmentApplication::mapLot, command.operationId());
		if (!existing.isEmpty()) {
			return duplicateLot(existing.getFirst(), command);
		}
		ReceiptLimit receiptLimit = receiptLimitFor(command.taskId(), task.goodQuantity());
		BigDecimal registered = jdbc.queryForObject("""
			select coalesce(sum(quantity), 0)
			from finished_goods_lot where task_id = ?
			""", BigDecimal.class, command.taskId());
		if (registered == null || registered.add(command.quantity()).compareTo(receiptLimit.allowedQuantity()) > 0) {
			throw DomainException.conflict(
				"FINISHED_GOODS_RECEIPT_QUANTITY_EXCEEDED",
				"成品入库数量不能超过成品清点合格数；已有终检记录时不得超过终检合格数"
			);
		}

		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		try {
			jdbc.update("""
				insert into finished_goods_lot (
					id, operation_id, lot_no, order_id, order_no, task_id,
					product_code, product_name, quantity, available_quantity,
					warehouse_code, registered_by, registered_at
				) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", id, command.operationId(), identifier("FG"), task.orderId(), task.orderNo(),
				command.taskId(), task.productCode(), task.productName(), command.quantity(),
				command.quantity(), normalize(command.warehouseCode()),
				normalize(command.registeredBy()), Timestamp.from(now));
		}
		catch (DuplicateKeyException exception) {
			LotView duplicate = jdbc.query("""
				select * from finished_goods_lot where operation_id = ?
				""", FulfillmentApplication::mapLot, command.operationId()).getFirst();
			return new LotSubmission(duplicate, true);
		}
		return new LotSubmission(requireLot(id, false), false);
	}

	@Transactional(readOnly = true)
	public List<PendingReceiptView> pendingReceipts(String viewerCode) {
		warehouseAccess.requireFulfillmentView(viewerCode);
		return jdbc.query("""
			select t.id as task_id, t.task_no, b.batch_no, w.order_id, o.order_no,
			       w.product_code, w.product_name, w.product_material, t.good_quantity,
			       (select count(*) from quality_inspection q where q.task_id = t.id) as inspection_count,
			       (select coalesce(sum(q.accepted_quantity), 0) from quality_inspection q where q.task_id = t.id) as accepted_quantity,
			       (select coalesce(sum(l.quantity), 0) from finished_goods_lot l where l.task_id = t.id) as registered_quantity
			from planning_task t
			join planning_batch b on b.id = t.batch_id
			join planning_work_order w on w.id = b.work_order_id
			join customer_order_header o on o.id = w.order_id
			where t.status = 'COMPLETED'
			  and t.sequence_no = (select max(last_task.sequence_no) from planning_task last_task where last_task.batch_id = t.batch_id)
			order by t.completed_at desc, t.task_no desc
			""", (rs, row) -> {
			BigDecimal taskGood = rs.getBigDecimal("good_quantity");
			BigDecimal accepted = rs.getBigDecimal("accepted_quantity");
			BigDecimal allowed = rs.getLong("inspection_count") > 0 ? accepted : taskGood;
			BigDecimal registered = rs.getBigDecimal("registered_quantity");
			return new PendingReceiptView(
				rs.getObject("task_id", UUID.class), rs.getString("task_no"), rs.getString("batch_no"),
				rs.getObject("order_id", UUID.class), rs.getString("order_no"),
				rs.getString("product_code"), rs.getString("product_name"), rs.getString("product_material"),
				taskGood, allowed, registered, allowed.subtract(registered).max(BigDecimal.ZERO),
				rs.getLong("inspection_count") > 0
			);
		}).stream().filter(receipt -> receipt.receivableQuantity().signum() > 0).toList();
	}

	private LotSubmission duplicateLot(LotView existing, RegisterLotCommand command) {
		if (!existing.taskId().equals(command.taskId())) {
			throw DomainException.conflict(
				"FINISHED_GOODS_OPERATION_REUSED", "成品登记操作号已被其他任务使用");
		}
		if (existing.quantity().compareTo(command.quantity()) != 0
				|| !existing.warehouseCode().equals(normalize(command.warehouseCode()))
				|| !existing.registeredBy().equals(normalize(command.registeredBy()))) {
			throw DomainException.conflict(
				"FINISHED_GOODS_OPERATION_PAYLOAD_MISMATCH", "相同操作号的成品登记内容不一致");
		}
		return new LotSubmission(existing, true);
	}

	@Transactional
	public DeliveryView createDelivery(CreateDeliveryCommand command) {
		warehouseAccess.requireFulfillmentManage(command.createdBy());
		LotView lot = requireLot(command.lotId(), true);
		if (!lot.orderId().equals(command.orderId())) {
			throw DomainException.badRequest("DELIVERY_ORDER_MISMATCH", "成品批次不属于该订单");
		}
		if (command.quantity().compareTo(lot.availableQuantity()) > 0) {
			throw DomainException.conflict("DELIVERY_STOCK_INSUFFICIENT", "成品批次可交付数量不足");
		}
		String customerName = jdbc.queryForObject("""
			select customer_name from customer_order_header where id = ?
			""", String.class, command.orderId());
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			insert into delivery_order (
				id, delivery_no, order_id, order_no, customer_name, lot_id, lot_no,
				product_code, product_name, quantity, recipient_name, delivery_address,
				status, created_by, created_at, updated_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
			""", id, identifier("DL"), lot.orderId(), lot.orderNo(), customerName,
			lot.id(), lot.lotNo(), lot.productCode(), lot.productName(), command.quantity(),
			command.recipientName().trim(), command.deliveryAddress().trim(),
			normalize(command.createdBy()), Timestamp.from(now), Timestamp.from(now));
		jdbc.update("""
			update finished_goods_lot set available_quantity = available_quantity - ? where id = ?
			""", command.quantity(), lot.id());
		addEvent(id, "NONE", "DRAFT", command.createdBy(), "交付单已创建", now);
		return requireDelivery(id, false);
	}

	@Transactional
	public DeliveryView transition(UUID deliveryId, TransitionCommand command) {
		warehouseAccess.requireFulfillmentManage(command.operatorCode());
		DeliveryView delivery = requireDelivery(deliveryId, true);
		String next = normalize(command.nextStatus());
		String expected = switch (delivery.status()) {
			case "DRAFT" -> "PICKED";
			case "PICKED" -> "SHIPPED";
			case "SHIPPED" -> "DELIVERED";
			default -> null;
		};
		if (!next.equals(expected)) {
			throw DomainException.conflict("DELIVERY_TRANSITION_INVALID", "交付状态流转不合法");
		}
		if (next.equals("SHIPPED")
				&& (isBlank(command.carrier()) || isBlank(command.trackingNo()))) {
			throw DomainException.badRequest("DELIVERY_LOGISTICS_REQUIRED", "发货必须填写承运商和运单号");
		}
		Instant now = Instant.now();
		String timestampColumn = switch (next) {
			case "PICKED" -> "picked_at";
			case "SHIPPED" -> "shipped_at";
			case "DELIVERED" -> "delivered_at";
			default -> throw new IllegalStateException();
		};
		jdbc.update("""
			update delivery_order
			set status = ?, carrier = coalesce(?, carrier), tracking_no = coalesce(?, tracking_no),
			    %s = ?, updated_at = ?
			where id = ?
			""".formatted(timestampColumn), next, blankToNull(command.carrier()),
			blankToNull(command.trackingNo()), Timestamp.from(now), Timestamp.from(now), deliveryId);
		addEvent(deliveryId, delivery.status(), next, command.operatorCode(),
			blankToNull(command.note()), now);
		return requireDelivery(deliveryId, false);
	}

	@Transactional(readOnly = true)
	public List<LotView> listLots(String viewerCode) {
		warehouseAccess.requireFulfillmentView(viewerCode);
		return jdbc.query("""
			select * from finished_goods_lot order by registered_at desc
			""", FulfillmentApplication::mapLot);
	}

	@Transactional(readOnly = true)
	public FulfillmentSummary summary(String viewerCode) {
		warehouseAccess.requireFulfillmentView(viewerCode);
		return jdbc.queryForObject("""
			select (select coalesce(sum(available_quantity), 0) from finished_goods_lot) as available_quantity,
			       (select count(*) from delivery_order where status in ('DRAFT', 'PICKED')) as pending_count,
			       (select count(*) from delivery_order where status = 'SHIPPED') as in_transit_count,
			       (select count(*) from delivery_order where status = 'DELIVERED') as delivered_count
			""", (rs, row) -> new FulfillmentSummary(rs.getBigDecimal("available_quantity"), rs.getInt("pending_count"),
				rs.getInt("in_transit_count"), rs.getInt("delivered_count")));
	}

	@Transactional(readOnly = true)
	public PageResult<LotView> searchLots(LotSearchCommand command) {
		warehouseAccess.requireFulfillmentView(command.viewerCode());
		PageRequest page = pageRequest(command.page(), command.size());
		List<Object> parameters = new ArrayList<>();
		String where = "1 = 1";
		if (command.keyword() != null && !command.keyword().isBlank()) {
			String term = searchTerm(command.keyword());
			where += " and (lot_no like ? or order_no like ? or product_code like ? or upper(product_name) like ?)";
			parameters.add(term); parameters.add(term); parameters.add(term); parameters.add(term);
		}
		if (command.warehouseCode() != null && !command.warehouseCode().isBlank() && !"ALL".equalsIgnoreCase(command.warehouseCode())) {
			where += " and warehouse_code = ?"; parameters.add(command.warehouseCode().trim().toUpperCase(Locale.ROOT));
		}
		if ("AVAILABLE".equalsIgnoreCase(command.availability())) where += " and available_quantity > 0";
		if ("ALLOCATED".equalsIgnoreCase(command.availability())) where += " and available_quantity = 0";
		long total = jdbc.queryForObject("select count(*) from finished_goods_lot where " + where, Long.class, parameters.toArray());
		List<Object> pageParameters = new ArrayList<>(parameters); pageParameters.add(page.size()); pageParameters.add(page.offset());
		List<LotView> items = jdbc.query("select * from finished_goods_lot where %s order by registered_at desc, lot_no desc limit ? offset ?".formatted(where),
			FulfillmentApplication::mapLot, pageParameters.toArray());
		return PageResult.of(items, page.page(), page.size(), total);
	}

	@Transactional(readOnly = true)
	public List<DeliveryView> listDeliveries(String viewerCode) {
		warehouseAccess.requireFulfillmentView(viewerCode);
		return jdbc.query("""
			select * from delivery_order order by created_at desc
			""", FulfillmentApplication::mapDelivery);
	}

	@Transactional(readOnly = true)
	public PageResult<DeliveryView> searchDeliveries(DeliverySearchCommand command) {
		warehouseAccess.requireFulfillmentView(command.viewerCode());
		PageRequest page = pageRequest(command.page(), command.size());
		List<Object> parameters = new ArrayList<>();
		String where = "1 = 1";
		if (command.keyword() != null && !command.keyword().isBlank()) {
			String term = searchTerm(command.keyword());
			where += " and (delivery_no like ? or order_no like ? or upper(customer_name) like ? or lot_no like ? or product_code like ? or upper(product_name) like ? or upper(coalesce(carrier, '')) like ? or upper(coalesce(tracking_no, '')) like ?)";
			for (int index = 0; index < 8; index++) parameters.add(term);
		}
		if (command.status() != null && !command.status().isBlank() && !"ALL".equalsIgnoreCase(command.status())) {
			where += " and status = ?"; parameters.add(command.status().trim().toUpperCase(Locale.ROOT));
		}
		long total = jdbc.queryForObject("select count(*) from delivery_order where " + where, Long.class, parameters.toArray());
		List<Object> pageParameters = new ArrayList<>(parameters); pageParameters.add(page.size()); pageParameters.add(page.offset());
		List<DeliveryView> items = jdbc.query("select * from delivery_order where %s order by created_at desc, delivery_no desc limit ? offset ?".formatted(where),
			FulfillmentApplication::mapDelivery, pageParameters.toArray());
		return PageResult.of(items, page.page(), page.size(), total);
	}

	private static String searchTerm(String keyword) {
		String normalized = keyword.trim().toUpperCase(Locale.ROOT);
		return normalized.matches("[A-Z0-9_-]+") ? normalized + "%" : "%" + normalized + "%";
	}

	@Transactional(readOnly = true)
	public DeliveryView getDelivery(UUID id, String viewerCode) {
		warehouseAccess.requireFulfillmentView(viewerCode);
		return requireDelivery(id, false);
	}

	@Transactional(readOnly = true)
	public List<FulfillmentTimelineEvent> eventsForOrder(UUID orderId) {
		List<FulfillmentTimelineEvent> events = new ArrayList<>();
		jdbc.query("""
			select lot_no, quantity, registered_at from finished_goods_lot where order_id = ?
			""", (RowCallbackHandler) rs -> events.add(new FulfillmentTimelineEvent(
				rs.getTimestamp("registered_at").toInstant(), "FINISHED_GOODS_REGISTERED",
				rs.getString("lot_no"), "成品入库 " + rs.getBigDecimal("quantity"))), orderId);
		jdbc.query("""
			select e.occurred_at, e.to_status, d.delivery_no, d.quantity
			from delivery_event e
			join delivery_order d on d.id = e.delivery_id
			where d.order_id = ?
			""", (RowCallbackHandler) rs -> events.add(new FulfillmentTimelineEvent(
				rs.getTimestamp("occurred_at").toInstant(),
				"DELIVERY_" + rs.getString("to_status"),
				rs.getString("delivery_no"),
				deliverySummary(rs.getString("to_status"), rs.getBigDecimal("quantity")))), orderId);
		return events.stream()
			.sorted(Comparator.comparing(FulfillmentTimelineEvent::occurredAt))
			.toList();
	}

	private FinalTaskContext requireFinalCompletedTask(UUID taskId) {
		try {
			jdbc.queryForObject("""
				select id from planning_task where id = ? for update
				""", UUID.class, taskId);
		}
		catch (EmptyResultDataAccessException exception) {
			throw DomainException.notFound("TASK_NOT_FOUND", "生产任务不存在");
		}
		List<FinalTaskContext> rows = jdbc.query("""
			select t.sequence_no, t.status, t.good_quantity, w.order_id, o.order_no,
			       w.product_code, w.product_name,
			       (select max(t2.sequence_no) from planning_task t2 where t2.batch_id = t.batch_id)
			         as final_sequence
			from planning_task t
			join planning_batch b on b.id = t.batch_id
			join planning_work_order w on w.id = b.work_order_id
			join customer_order_header o on o.id = w.order_id
			where t.id = ?
		""", (rs, rowNum) -> new FinalTaskContext(
			rs.getInt("sequence_no"), rs.getString("status"), rs.getBigDecimal("good_quantity"), rs.getObject("order_id", UUID.class),
				rs.getString("order_no"), rs.getString("product_code"), rs.getString("product_name"),
				rs.getInt("final_sequence")), taskId);
		if (rows.isEmpty()) {
			throw DomainException.notFound("TASK_NOT_FOUND", "生产任务不存在");
		}
		FinalTaskContext task = rows.getFirst();
		if (task.sequenceNo() != task.finalSequence() || !task.status().equals("COMPLETED")) {
			throw DomainException.conflict("FINISHED_GOODS_TASK_INVALID", "只有已完成的末工序可以登记成品");
		}
		return task;
	}

	private ReceiptLimit receiptLimitFor(UUID taskId, BigDecimal taskGoodQuantity) {
		return jdbc.queryForObject("""
			select count(*) as inspection_count, coalesce(sum(accepted_quantity), 0) as accepted_quantity
			from quality_inspection where task_id = ?
			""", (rs, row) -> {
			BigDecimal allowed = rs.getLong("inspection_count") > 0
				? rs.getBigDecimal("accepted_quantity") : taskGoodQuantity;
			return new ReceiptLimit(allowed);
		}, taskId);
	}

	private LotView requireLot(UUID id, boolean lock) {
		String sql = "select * from finished_goods_lot where id = ?" + (lock ? " for update" : "");
		return jdbc.query(sql, FulfillmentApplication::mapLot, id).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("FINISHED_GOODS_LOT_NOT_FOUND", "成品批次不存在"));
	}

	private DeliveryView requireDelivery(UUID id, boolean lock) {
		String sql = "select * from delivery_order where id = ?" + (lock ? " for update" : "");
		return jdbc.query(sql, FulfillmentApplication::mapDelivery, id).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("DELIVERY_NOT_FOUND", "交付单不存在"));
	}

	private void addEvent(UUID deliveryId, String fromStatus, String toStatus,
			String operatorCode, String note, Instant occurredAt) {
		jdbc.update("""
			insert into delivery_event (
				id, delivery_id, from_status, to_status, operator_code, note, occurred_at
			) values (?, ?, ?, ?, ?, ?, ?)
			""", UUID.randomUUID(), deliveryId, fromStatus, toStatus, normalize(operatorCode),
			note, Timestamp.from(occurredAt));
	}

	private static LotView mapLot(ResultSet rs, int rowNum) throws SQLException {
		return new LotView(rs.getObject("id", UUID.class), rs.getObject("operation_id", UUID.class),
			rs.getString("lot_no"), rs.getObject("order_id", UUID.class), rs.getString("order_no"),
			rs.getObject("task_id", UUID.class), rs.getString("product_code"),
			rs.getString("product_name"), rs.getBigDecimal("quantity"),
			rs.getBigDecimal("available_quantity"), rs.getString("warehouse_code"),
			rs.getString("registered_by"), rs.getTimestamp("registered_at").toInstant());
	}

	private static DeliveryView mapDelivery(ResultSet rs, int rowNum) throws SQLException {
		return new DeliveryView(rs.getObject("id", UUID.class), rs.getString("delivery_no"),
			rs.getObject("order_id", UUID.class), rs.getString("order_no"),
			rs.getString("customer_name"), rs.getObject("lot_id", UUID.class),
			rs.getString("lot_no"), rs.getString("product_code"), rs.getString("product_name"),
			rs.getBigDecimal("quantity"), rs.getString("recipient_name"),
			rs.getString("delivery_address"), rs.getString("carrier"),
			rs.getString("tracking_no"), rs.getString("status"), rs.getString("created_by"),
			rs.getTimestamp("created_at").toInstant(), instant(rs, "picked_at"),
			instant(rs, "shipped_at"), instant(rs, "delivered_at"),
			rs.getTimestamp("updated_at").toInstant());
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static PageRequest pageRequest(int page, int size) {
		if (page < 0 || size < 1 || size > 100) throw DomainException.badRequest("INVALID_PAGE_REQUEST", "页码必须从 0 开始，单页数量为 1 至 100");
		return new PageRequest(page, size);
	}

	private record PageRequest(int page, int size) {
		private long offset() { return (long) page * size; }
	}

	private static String deliverySummary(String status, BigDecimal quantity) {
		return switch (status) {
			case "DRAFT" -> "交付单已创建 " + quantity;
			case "PICKED" -> "成品已备货 " + quantity;
			case "SHIPPED" -> "成品已发出 " + quantity;
			case "DELIVERED" -> "客户已签收 " + quantity;
			default -> "交付状态已更新";
		};
	}

	private static String identifier(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static String blankToNull(String value) {
		return isBlank(value) ? null : value.trim();
	}

	private record FinalTaskContext(int sequenceNo, String status, BigDecimal goodQuantity, UUID orderId, String orderNo,
			String productCode, String productName, int finalSequence) {
	}

	private record ReceiptLimit(BigDecimal allowedQuantity) {
	}

	public record RegisterLotCommand(UUID operationId, UUID taskId, BigDecimal quantity,
			String warehouseCode, String registeredBy) {
	}

	public record PendingReceiptView(UUID taskId, String taskNo, String batchNo, UUID orderId, String orderNo,
			String productCode, String productName, String productMaterial, BigDecimal finalCountQuantity,
			BigDecimal allowedQuantity, BigDecimal registeredQuantity, BigDecimal receivableQuantity,
			boolean qualityInspected) {
	}

	public record CreateDeliveryCommand(UUID orderId, UUID lotId, BigDecimal quantity,
			String recipientName, String deliveryAddress, String createdBy) {
	}

	public record TransitionCommand(String nextStatus, String operatorCode, String carrier,
			String trackingNo, String note) {
	}

	public record LotSubmission(LotView lot, boolean duplicate) {
	}

	public record LotView(UUID id, UUID operationId, String lotNo, UUID orderId, String orderNo,
			UUID taskId, String productCode, String productName, BigDecimal quantity,
			BigDecimal availableQuantity, String warehouseCode, String registeredBy,
			Instant registeredAt) {
	}

	public record DeliveryView(UUID id, String deliveryNo, UUID orderId, String orderNo,
			String customerName, UUID lotId, String lotNo, String productCode, String productName,
			BigDecimal quantity, String recipientName, String deliveryAddress, String carrier,
			String trackingNo, String status, String createdBy, Instant createdAt,
			Instant pickedAt, Instant shippedAt, Instant deliveredAt, Instant updatedAt) {
	}

	public record LotSearchCommand(String viewerCode, String keyword, String availability, String warehouseCode, int page, int size) { }

	public record DeliverySearchCommand(String viewerCode, String keyword, String status, int page, int size) { }
	public record FulfillmentSummary(BigDecimal availableQuantity, int pendingCount, int inTransitCount, int deliveredCount) { }

	public record FulfillmentTimelineEvent(Instant occurredAt, String type, String reference,
			String summary) {
	}
}

@RestController
@RequestMapping("/api/fulfillment")
class FulfillmentController {

	private final FulfillmentApplication fulfillment;

	FulfillmentController(FulfillmentApplication fulfillment) {
		this.fulfillment = fulfillment;
	}

	@GetMapping("/lots")
	List<FulfillmentApplication.LotView> lots(@RequestParam String viewerCode) {
		return fulfillment.listLots(viewerCode);
	}

	@GetMapping("/lots/query")
	PageResult<FulfillmentApplication.LotView> searchLots(@RequestParam String viewerCode,
			@RequestParam(required = false) String keyword, @RequestParam(required = false) String availability,
			@RequestParam(required = false) String warehouseCode, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return fulfillment.searchLots(new FulfillmentApplication.LotSearchCommand(viewerCode, keyword, availability, warehouseCode, page, size));
	}

	@GetMapping("/summary")
	FulfillmentApplication.FulfillmentSummary summary(@RequestParam String viewerCode) { return fulfillment.summary(viewerCode); }

	@GetMapping("/receipts")
	List<FulfillmentApplication.PendingReceiptView> pendingReceipts(@RequestParam String viewerCode) {
		return fulfillment.pendingReceipts(viewerCode);
	}

	@PostMapping("/lots")
	ResponseEntity<FulfillmentApplication.LotSubmission> registerLot(
			@Valid @RequestBody RegisterLotRequest request) {
		FulfillmentApplication.LotSubmission result = fulfillment.registerLot(
			new FulfillmentApplication.RegisterLotCommand(
				request.operationId(), request.taskId(), request.quantity(),
				request.warehouseCode(), request.registeredBy()));
		return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
	}

	@GetMapping("/deliveries")
	List<FulfillmentApplication.DeliveryView> deliveries(@RequestParam String viewerCode) {
		return fulfillment.listDeliveries(viewerCode);
	}

	@GetMapping("/deliveries/query")
	PageResult<FulfillmentApplication.DeliveryView> searchDeliveries(@RequestParam String viewerCode,
			@RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return fulfillment.searchDeliveries(new FulfillmentApplication.DeliverySearchCommand(viewerCode, keyword, status, page, size));
	}

	@GetMapping("/deliveries/{id}")
	FulfillmentApplication.DeliveryView delivery(@PathVariable UUID id, @RequestParam String viewerCode) {
		return fulfillment.getDelivery(id, viewerCode);
	}

	@PostMapping("/deliveries")
	ResponseEntity<FulfillmentApplication.DeliveryView> createDelivery(
			@Valid @RequestBody CreateDeliveryRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(
			fulfillment.createDelivery(new FulfillmentApplication.CreateDeliveryCommand(
				request.orderId(), request.lotId(), request.quantity(), request.recipientName(),
				request.deliveryAddress(), request.createdBy())));
	}

	@PostMapping("/deliveries/{id}/transitions")
	FulfillmentApplication.DeliveryView transition(@PathVariable UUID id,
			@Valid @RequestBody TransitionRequest request) {
		return fulfillment.transition(id, new FulfillmentApplication.TransitionCommand(
			request.nextStatus(), request.operatorCode(), request.carrier(),
			request.trackingNo(), request.note()));
	}

	record RegisterLotRequest(@NotNull UUID operationId, @NotNull UUID taskId,
			@NotNull @DecimalMin("0.001") BigDecimal quantity,
			@NotBlank @Size(max = 64) String warehouseCode,
			@NotBlank @Size(max = 64) String registeredBy) {
	}

	record CreateDeliveryRequest(@NotNull UUID orderId, @NotNull UUID lotId,
			@NotNull @DecimalMin("0.001") BigDecimal quantity,
			@NotBlank @Size(max = 100) String recipientName,
			@NotBlank @Size(max = 500) String deliveryAddress,
			@NotBlank @Size(max = 64) String createdBy) {
	}

	record TransitionRequest(@NotBlank String nextStatus,
			@NotBlank @Size(max = 64) String operatorCode,
			@Size(max = 160) String carrier, @Size(max = 120) String trackingNo,
			@Size(max = 500) String note) {
	}
}
