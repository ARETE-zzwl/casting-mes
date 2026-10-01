package com.renyi.mes.resource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.BusinessAccess;
import com.renyi.mes.common.OperationalAuditPort;
import com.renyi.mes.common.ProductionTaskPort;
import com.renyi.mes.common.ProductionTaskPort.TaskSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Service
public class CartTransferApplication {

	private final JdbcTemplate jdbc;
	private final ProductionTaskPort tasks;
	private final ResourceApplication resources;
	private final OperationalAuditPort audit;
	private final BusinessAccess access;

	public CartTransferApplication(JdbcTemplate jdbc, ProductionTaskPort tasks, ResourceApplication resources, OperationalAuditPort audit, BusinessAccess access) {
		this.jdbc = jdbc;
		this.tasks = tasks;
		this.resources = resources;
		this.audit = audit;
		this.access = access;
	}

	@Transactional
	public CartTransferView load(LoadCommand command) {
		access.requireManageTask(command.sourceTaskId());
		jdbc.queryForObject("select id from planning_task where id = ? for update", UUID.class, command.sourceTaskId());
		if (command.operationId() != null) {
			List<CartTransferView> existing = jdbc.query(select() + " where c.load_operation_id = ?", (rs, row) -> map(rs), command.operationId());
			if (!existing.isEmpty()) {
				var duplicate = existing.getFirst();
				if (!duplicate.sourceTaskId().equals(command.sourceTaskId()) || !duplicate.cartCode().equals(normalize(command.cartCode()))
						|| duplicate.loadedQuantity().compareTo(command.quantity()) != 0 || !duplicate.loadedBy().equals(normalize(command.loadedBy()))
						|| !java.util.Objects.equals(duplicate.loadPhotoUrl(), blankToNull(command.loadPhotoUrl()))) throw replayConflict();
				return duplicate;
			}
		}
		TaskSnapshot source = tasks.get(command.sourceTaskId());
		if (!"COMPLETED".equals(source.status()) || source.goodQuantity().signum() <= 0) {
			throw DomainException.conflict("CART_SOURCE_NOT_READY", "仅可装载已完成且有合格数量的工序任务");
		}
		BigDecimal available = source.goodQuantity().subtract(loadedQuantity(source.id()));
		if (command.quantity().compareTo(available) > 0) {
			throw DomainException.conflict("CART_QUANTITY_EXCEEDED", "装车数量超过来源工序可流转合格数量");
		}
		CartAsset cart = requireCart(command.cartCode(), true);
		if ("AVAILABLE".equals(cart.status())) {
			resources.occupy(cart.id(), new ResourceApplication.OccupyCommand("CART:" + cart.code(), command.loadedBy(), "周转车装载中"));
		}
		else if (!"OCCUPIED".equals(cart.status()) || !isCartOccupation(cart.id())) {
			throw DomainException.conflict("CART_NOT_AVAILABLE", "该周转车当前不可用于装载");
		}
		TargetTask target = nextTask(command.sourceTaskId());
		Instant now = Instant.now();
		UUID id = UUID.randomUUID();
		try { jdbc.update("""
			insert into production_cart_transfer (
				id, transfer_no, cart_asset_id, source_task_id, target_task_id, loaded_quantity, status,
				load_photo_url, loaded_by, load_operation_id, load_device_code, load_workstation_code, loaded_at
			) values (?, ?, ?, ?, ?, ?, 'LOADED', ?, ?, ?, ?, ?, ?)
			""", id, transferNo(), cart.id(), command.sourceTaskId(), target.taskId(), command.quantity(),
			blankToNull(command.loadPhotoUrl()), normalize(command.loadedBy()), command.operationId(), blankToNull(command.deviceCode()), blankToNull(command.workstationCode()), Timestamp.from(now)); }
		catch (org.springframework.dao.DuplicateKeyException exception) { throw replayConflict(); }
		audit.record("CART_LOAD", "CART_TRANSFER", id, command.operationId(), command.loadedBy(), command.deviceCode(), command.workstationCode(), "扫码装车");
		return require(id);
	}

	@Transactional
	public CartTransferView receive(UUID transferId, ReceiveCommand command) {
		access.requireManageTask(require(transferId).targetTaskId());
		CartTransferRow transfer = requireRow(transferId, true);
		if (command.operationId() != null) {
			List<CartTransferView> existing = jdbc.query(select() + " where c.receive_operation_id = ?", (rs, row) -> map(rs), command.operationId());
			if (!existing.isEmpty()) {
				var duplicate = existing.getFirst();
				String reason = command.receivedQuantity().compareTo(duplicate.loadedQuantity()) == 0 ? blankToNull(command.exceptionReason())
						: optional(command.exceptionReason(), "接收数量与装车数量不一致，已标记待复核");
				if (!duplicate.id().equals(transferId) || duplicate.receivedQuantity().compareTo(command.receivedQuantity()) != 0
						|| !duplicate.receivedBy().equals(normalize(command.receivedBy())) || !java.util.Objects.equals(duplicate.receivePhotoUrl(), blankToNull(command.receivePhotoUrl()))
						|| !java.util.Objects.equals(duplicate.exceptionReason(), reason)) throw replayConflict();
				return duplicate;
			}
		}
		if (!"LOADED".equals(transfer.status())) {
			throw DomainException.conflict("CART_TRANSFER_STATE", "该装车记录已完成接收");
		}
		String status = command.receivedQuantity().compareTo(transfer.loadedQuantity()) == 0 ? "RECEIVED" : "EXCEPTION";
		String reason = "EXCEPTION".equals(status)
			? optional(command.exceptionReason(), "接收数量与装车数量不一致，已标记待复核") : blankToNull(command.exceptionReason());
		Instant now = Instant.now();
		try { jdbc.update("""
			update production_cart_transfer set received_quantity = ?, status = ?, receive_photo_url = ?, received_by = ?,
				exception_reason = ?, receive_operation_id = ?, receive_device_code = ?, receive_workstation_code = ?, received_at = ? where id = ?
			""", command.receivedQuantity(), status, blankToNull(command.receivePhotoUrl()), normalize(command.receivedBy()),
			reason, command.operationId(), blankToNull(command.deviceCode()), blankToNull(command.workstationCode()), Timestamp.from(now), transferId); }
		catch (org.springframework.dao.DuplicateKeyException exception) { throw replayConflict(); }
		audit.record("CART_RECEIVE", "CART_TRANSFER", transferId, command.operationId(), command.receivedBy(), command.deviceCode(), command.workstationCode(), "扫码接收");
		if (!hasLoadedItems(transfer.cartAssetId())) {
			resources.release(transfer.cartAssetId(), new ResourceApplication.ReleaseCommand(command.receivedBy(), false));
		}
		return require(transferId);
	}

	@Transactional(readOnly = true)
	public List<CartSourceView> readySources() {
		var scope = access.taskScope("source.id");
		return jdbc.query("""
			select source.id, source.task_no, source.operation_name, w.route_type, w.product_name, w.product_material,
			       o.order_no, b.batch_no, source.good_quantity - coalesce(sum(c.loaded_quantity), 0) as available_quantity
			from planning_task source
			join planning_task successor on successor.batch_id = source.batch_id and successor.sequence_no = source.sequence_no + 1
			join planning_batch b on b.id = source.batch_id join planning_work_order w on w.id = b.work_order_id
			join customer_order_header o on o.id = w.order_id
			left join production_cart_transfer c on c.source_task_id = source.id
			where source.status = 'COMPLETED' and successor.status <> 'COMPLETED' and
			""" + scope.clause() + """
			 group by source.id, source.task_no, source.operation_name, w.route_type, w.product_name, w.product_material,
			          o.order_no, b.batch_no, source.good_quantity, source.completed_at
			 having source.good_quantity > coalesce(sum(c.loaded_quantity), 0)
			 order by source.completed_at desc, source.task_no
			""", (rs, row) -> new CartSourceView(rs.getObject("id", UUID.class), rs.getString("task_no"), rs.getString("operation_name"),
				rs.getString("route_type"), rs.getString("product_name"), rs.getString("product_material"), rs.getString("order_no"),
				rs.getString("batch_no"), rs.getBigDecimal("available_quantity")), scope.parameters().toArray());
	}

	@Transactional(readOnly = true)
	public List<CartTransferView> list() {
		return jdbc.query(select() + " order by c.loaded_at desc", (rs, rowNum) -> new CartTransferView(
			rs.getObject("id", UUID.class), rs.getString("transfer_no"), rs.getString("cart_code"), rs.getString("cart_name"),
			rs.getObject("source_task_id", UUID.class), rs.getString("source_task_no"), rs.getString("source_operation_name"),
			rs.getObject("target_task_id", UUID.class), rs.getString("target_task_no"), rs.getString("target_operation_name"),
			rs.getString("order_no"), rs.getString("product_code"), rs.getString("product_name"), rs.getBigDecimal("loaded_quantity"),
			rs.getBigDecimal("received_quantity"), rs.getString("status"), rs.getString("load_photo_url"), rs.getString("receive_photo_url"),
			rs.getString("loaded_by"), rs.getString("received_by"), rs.getString("exception_reason"),
			rs.getTimestamp("loaded_at").toInstant(), rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant()
		)).stream().filter(transfer -> access.canReadTask(transfer.sourceTaskId()) || access.canReadTask(transfer.targetTaskId())).toList();
	}

	private CartTransferView require(UUID id) {
		return jdbc.query(select() + " where c.id = ?", (rs, rowNum) -> new CartTransferView(
			rs.getObject("id", UUID.class), rs.getString("transfer_no"), rs.getString("cart_code"), rs.getString("cart_name"),
			rs.getObject("source_task_id", UUID.class), rs.getString("source_task_no"), rs.getString("source_operation_name"),
			rs.getObject("target_task_id", UUID.class), rs.getString("target_task_no"), rs.getString("target_operation_name"),
			rs.getString("order_no"), rs.getString("product_code"), rs.getString("product_name"), rs.getBigDecimal("loaded_quantity"),
			rs.getBigDecimal("received_quantity"), rs.getString("status"), rs.getString("load_photo_url"), rs.getString("receive_photo_url"),
			rs.getString("loaded_by"), rs.getString("received_by"), rs.getString("exception_reason"),
			rs.getTimestamp("loaded_at").toInstant(), rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant()
		), id).stream().findFirst().orElseThrow(() -> DomainException.notFound("CART_TRANSFER_NOT_FOUND", "周转车流转记录不存在"));
	}

	private CartTransferRow requireRow(UUID id, boolean lock) {
		return jdbc.query("select id, cart_asset_id, loaded_quantity, status from production_cart_transfer where id = ?" + (lock ? " for update" : ""),
			(rs, rowNum) -> new CartTransferRow(rs.getObject("id", UUID.class), rs.getObject("cart_asset_id", UUID.class),
				rs.getBigDecimal("loaded_quantity"), rs.getString("status")), id).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("CART_TRANSFER_NOT_FOUND", "周转车流转记录不存在"));
	}

	private CartAsset requireCart(String code, boolean lock) {
		return jdbc.query("select id, asset_code, asset_name, asset_type, status from resource_asset where asset_code = ?" + (lock ? " for update" : ""),
			(rs, rowNum) -> new CartAsset(rs.getObject("id", UUID.class), rs.getString("asset_code"), rs.getString("asset_name"),
				rs.getString("asset_type"), rs.getString("status")), normalize(code))
			.stream().findFirst().filter(cart -> "CARRIER".equals(cart.type()))
			.orElseThrow(() -> DomainException.notFound("CART_NOT_FOUND", "未找到可用的周转车编码"));
	}

	private TargetTask nextTask(UUID sourceTaskId) {
		TargetTask target = jdbc.query("""
			select successor.id, successor.task_no, successor.operation_name, successor.status
			from planning_task source
			join planning_task successor on successor.batch_id = source.batch_id and successor.sequence_no = source.sequence_no + 1
			where source.id = ?
			""", (rs, rowNum) -> new TargetTask(rs.getObject("id", UUID.class), rs.getString("task_no"), rs.getString("operation_name"), rs.getString("status")), sourceTaskId)
			.stream().findFirst().orElseThrow(() -> DomainException.conflict("CART_TARGET_MISSING", "当前任务没有可流转的下一工序"));
		if ("COMPLETED".equals(target.status())) {
			throw DomainException.conflict("CART_TARGET_ALREADY_COMPLETED", "下一工序已完成，不能再新建物理流转");
		}
		return target;
	}

	private BigDecimal loadedQuantity(UUID sourceTaskId) {
		BigDecimal quantity = jdbc.queryForObject("select coalesce(sum(loaded_quantity), 0) from production_cart_transfer where source_task_id = ?", BigDecimal.class, sourceTaskId);
		return quantity == null ? BigDecimal.ZERO : quantity;
	}

	private boolean hasLoadedItems(UUID cartAssetId) {
		Integer count = jdbc.queryForObject("select count(*) from production_cart_transfer where cart_asset_id = ? and status = 'LOADED'", Integer.class, cartAssetId);
		return count != null && count > 0;
	}

	private boolean isCartOccupation(UUID cartAssetId) {
		Integer count = jdbc.queryForObject("""
			select count(*) from resource_occupation where active_asset_id = ? and status = 'ACTIVE' and business_key like 'CART:%'
			""", Integer.class, cartAssetId);
		return count != null && count > 0;
	}

	private static String select() {
		return """
			select c.*, a.asset_code as cart_code, a.asset_name as cart_name,
				source.task_no as source_task_no, source.operation_name as source_operation_name,
				target.task_no as target_task_no, target.operation_name as target_operation_name,
				wo.work_order_no, wo.product_code, wo.product_name, o.order_no
			from production_cart_transfer c
			join resource_asset a on a.id = c.cart_asset_id
			join planning_task source on source.id = c.source_task_id
			join planning_task target on target.id = c.target_task_id
			join planning_batch b on b.id = source.batch_id
			join planning_work_order wo on wo.id = b.work_order_id
			join customer_order_header o on o.id = wo.order_id
			""";
	}

	private static String transferNo() {
		return "CT-" + LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
	}

	private static CartTransferView map(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new CartTransferView(rs.getObject("id", UUID.class), rs.getString("transfer_no"), rs.getString("cart_code"), rs.getString("cart_name"),
			rs.getObject("source_task_id", UUID.class), rs.getString("source_task_no"), rs.getString("source_operation_name"),
			rs.getObject("target_task_id", UUID.class), rs.getString("target_task_no"), rs.getString("target_operation_name"),
			rs.getString("order_no"), rs.getString("product_code"), rs.getString("product_name"), rs.getBigDecimal("loaded_quantity"),
			rs.getBigDecimal("received_quantity"), rs.getString("status"), rs.getString("load_photo_url"), rs.getString("receive_photo_url"),
			rs.getString("loaded_by"), rs.getString("received_by"), rs.getString("exception_reason"),
			rs.getTimestamp("loaded_at").toInstant(), rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant());
	}
	private static String normalize(String value) { return value.trim().toUpperCase(Locale.ROOT); }
	private static DomainException replayConflict() { return DomainException.conflict("CART_OPERATION_PAYLOAD_MISMATCH", "流转操作编号已用于其他内容，请核对后重新提交"); }
	private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
	private static String optional(String value, String fallback) { String normalized = blankToNull(value); return normalized == null ? fallback : normalized; }

	private record CartAsset(UUID id, String code, String name, String type, String status) { }
	private record TargetTask(UUID taskId, String taskNo, String operationName, String status) { }
	private record CartTransferRow(UUID id, UUID cartAssetId, BigDecimal loadedQuantity, String status) { }

	public record LoadCommand(UUID sourceTaskId, String cartCode, BigDecimal quantity, String loadPhotoUrl, String loadedBy, UUID operationId, String deviceCode, String workstationCode) { }
	public record CartSourceView(UUID id, String taskNo, String operationName, String routeType, String productName,
			String productMaterial, String orderNo, String batchNo, BigDecimal availableQuantity) { }
	public record ReceiveCommand(BigDecimal receivedQuantity, String receivePhotoUrl, String exceptionReason, String receivedBy, UUID operationId, String deviceCode, String workstationCode) { }
	public record CartTransferView(UUID id, String transferNo, String cartCode, String cartName, UUID sourceTaskId,
			String sourceTaskNo, String sourceOperationName, UUID targetTaskId, String targetTaskNo, String targetOperationName,
			String orderNo, String productCode, String productName, BigDecimal loadedQuantity, BigDecimal receivedQuantity,
			String status, String loadPhotoUrl, String receivePhotoUrl, String loadedBy, String receivedBy,
			String exceptionReason, Instant loadedAt, Instant receivedAt) { }
}

@RestController
@RequestMapping("/api/cart-transfers")
class CartTransferController {
	private final CartTransferApplication transfers;
	CartTransferController(CartTransferApplication transfers) { this.transfers = transfers; }

	@GetMapping
	List<CartTransferApplication.CartTransferView> list() { return transfers.list(); }
	@GetMapping("/ready-sources")
	List<CartTransferApplication.CartSourceView> readySources() { return transfers.readySources(); }

	@PostMapping("/load")
	CartTransferApplication.CartTransferView load(@Valid @RequestBody LoadRequest request) {
		return transfers.load(new CartTransferApplication.LoadCommand(request.sourceTaskId(), request.cartCode(), request.quantity(), request.loadPhotoUrl(), request.loadedBy(), request.operationId(), request.deviceCode(), request.workstationCode()));
	}

	@PostMapping("/{transferId}/receive")
	CartTransferApplication.CartTransferView receive(@PathVariable UUID transferId, @Valid @RequestBody ReceiveRequest request) {
		return transfers.receive(transferId, new CartTransferApplication.ReceiveCommand(request.receivedQuantity(), request.receivePhotoUrl(), request.exceptionReason(), request.receivedBy(), request.operationId(), request.deviceCode(), request.workstationCode()));
	}

	record LoadRequest(@NotNull UUID sourceTaskId, @NotBlank @Size(max = 64) String cartCode,
			@NotNull @DecimalMin(value = "0.001") BigDecimal quantity, @Size(max = 1000) String loadPhotoUrl,
			@NotBlank @Size(max = 64) String loadedBy, UUID operationId, @Size(max = 64) String deviceCode, @Size(max = 64) String workstationCode) { }
	record ReceiveRequest(@NotNull @DecimalMin("0") BigDecimal receivedQuantity, @Size(max = 1000) String receivePhotoUrl,
			@Size(max = 500) String exceptionReason, @NotBlank @Size(max = 64) String receivedBy, UUID operationId, @Size(max = 64) String deviceCode, @Size(max = 64) String workstationCode) { }
}
