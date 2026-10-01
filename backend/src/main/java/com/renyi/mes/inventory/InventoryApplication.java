package com.renyi.mes.inventory;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Service
public class InventoryApplication {

	private static final List<String> INBOUND = List.of("RECEIPT", "ADJUSTMENT_IN");
	private static final List<String> OUTBOUND = List.of("ISSUE", "ADJUSTMENT_OUT");

	private final JdbcTemplate jdbc;
	private final WarehouseAccessApplication warehouseAccess;

	public InventoryApplication(JdbcTemplate jdbc, WarehouseAccessApplication warehouseAccess) {
		this.jdbc = jdbc;
		this.warehouseAccess = warehouseAccess;
	}

	@Transactional
	public MovementResult move(MovementCommand command) {
		String type = command.movementType().trim().toUpperCase(Locale.ROOT);
		if (!INBOUND.contains(type) && !OUTBOUND.contains(type)) {
			throw DomainException.badRequest("INVALID_MOVEMENT_TYPE", "库存类型必须是入库、出库、调增或调减");
		}

		String warehouse = normalize(command.warehouseCode());
		String itemCode = normalize(command.itemCode());
		String unit = normalize(command.unit());
		warehouseAccess.requireRawMaterialManage(command.operatorCode(), warehouse);
		MovementView existing = findMovement(command.operationId());
		if (existing != null) {
			return duplicate(existing, warehouse, itemCode, unit, type, command.quantity());
		}

		Instant now = Instant.now();
		BalanceView balance = findBalanceForUpdate(warehouse, itemCode, unit);
		if (balance == null && INBOUND.contains(type)) {
			createBalanceIfMissing(
				warehouse, itemCode, command.itemName().trim(), unit, now);
			balance = findBalanceForUpdate(warehouse, itemCode, unit);
		}
		if (balance == null) {
			throw DomainException.conflict("INSUFFICIENT_STOCK", "库存不足，不能执行出库或调减");
		}
		existing = findMovement(command.operationId());
		if (existing != null) {
			return duplicate(existing, warehouse, itemCode, unit, type, command.quantity());
		}

		BigDecimal delta = INBOUND.contains(type) ? command.quantity() : command.quantity().negate();
		BigDecimal next = balance.quantity().add(delta);
		if (next.signum() < 0) {
			throw DomainException.conflict("INSUFFICIENT_STOCK", "库存不足，不能执行出库或调减");
		}

		jdbc.update("""
			update inventory_balance
			set item_name = ?, quantity = ?, version = version + 1, updated_at = ?
			where id = ?
			""", command.itemName().trim(), next, Timestamp.from(now), balance.id());

		UUID movementId = UUID.randomUUID();
		jdbc.update("""
			insert into inventory_movement (
				id, operation_id, movement_no, balance_id, movement_type, quantity,
				balance_after, reference_type, reference_no, operator_code, remark, occurred_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""",
			movementId,
			command.operationId(),
			identifier("IM"),
			balance.id(),
			type,
			command.quantity(),
			next,
			blankToNull(command.referenceType()),
			blankToNull(command.referenceNo()),
			normalize(command.operatorCode()),
			blankToNull(command.remark()),
			Timestamp.from(now)
		);
		return new MovementResult(requireMovement(movementId), false);
	}

	private void createBalanceIfMissing(
		String warehouse,
		String itemCode,
		String itemName,
		String unit,
		Instant now
	) {
		ensureCreationLock();
		jdbc.queryForObject(
			"select id from inventory_balance_creation_lock where id = 1 for update",
			Integer.class
		);
		if (findBalanceForUpdate(warehouse, itemCode, unit) != null) {
			return;
		}
		jdbc.update("""
			insert into inventory_balance (
				id, warehouse_code, item_code, item_name, unit, quantity, version, updated_at
			) values (?, ?, ?, ?, ?, 0, 0, ?)
			""",
			UUID.randomUUID(),
			warehouse,
			itemCode,
			itemName,
			unit,
			Timestamp.from(now)
		);
	}

	private void ensureCreationLock() {
		Integer count = jdbc.queryForObject(
			"select count(*) from inventory_balance_creation_lock where id = 1", Integer.class);
		if (count != null && count > 0) return;
		try {
			jdbc.update("insert into inventory_balance_creation_lock (id, lock_name) values (1, 'INVENTORY_BALANCE_CREATION')");
		} catch (DuplicateKeyException ignored) {
			// A concurrent first receipt restored the singleton lock first.
		}
	}

	private MovementResult duplicate(
		MovementView existing,
		String warehouse,
		String itemCode,
		String unit,
		String type,
		BigDecimal quantity
	) {
		if (!existing.warehouseCode().equals(warehouse)
				|| !existing.itemCode().equals(itemCode)
				|| !existing.unit().equals(unit)
				|| !existing.movementType().equals(type)) {
			throw DomainException.conflict(
				"INVENTORY_OPERATION_REUSED", "库存操作号已被其他业务使用");
		}
		if (existing.quantity().compareTo(quantity) != 0) {
			throw DomainException.conflict(
				"INVENTORY_OPERATION_PAYLOAD_MISMATCH", "相同操作号的库存数量不一致");
		}
		return new MovementResult(existing, true);
	}

	@Transactional(readOnly = true)
	public List<BalanceView> listBalances(String viewerCode) {
		warehouseAccess.requireInventoryView(viewerCode);
		if (!warehouseAccess.canViewRawMaterials(viewerCode)) return List.of();
		return jdbc.query("""
			select id, warehouse_code, item_code, item_name, unit, quantity, version, updated_at
			from inventory_balance
			where upper(warehouse_code) not like 'MOLD%' and upper(warehouse_code) not like 'FG%'
			order by warehouse_code, item_code
			""", InventoryApplication::mapBalance);
	}

	@Transactional(readOnly = true)
	public List<MovementView> listMovements(String viewerCode, String itemCode) {
		warehouseAccess.requireInventoryView(viewerCode);
		if (!warehouseAccess.canViewRawMaterials(viewerCode)) return List.of();
		if (itemCode == null || itemCode.isBlank()) {
			return jdbc.query("""
				select m.*, b.warehouse_code, b.item_code, b.item_name, b.unit
				from inventory_movement m join inventory_balance b on b.id = m.balance_id
				where upper(b.warehouse_code) not like 'MOLD%' and upper(b.warehouse_code) not like 'FG%'
				order by m.occurred_at desc
				""", InventoryApplication::mapMovement);
		}
		return jdbc.query("""
			select m.*, b.warehouse_code, b.item_code, b.item_name, b.unit
			from inventory_movement m join inventory_balance b on b.id = m.balance_id
			where b.item_code = ?
			  and upper(b.warehouse_code) not like 'MOLD%' and upper(b.warehouse_code) not like 'FG%'
			order by m.occurred_at desc
			""", InventoryApplication::mapMovement, normalize(itemCode));
	}

	@Transactional(readOnly = true)
	public PageResult<BalanceView> searchBalances(BalanceSearchCommand command) {
		warehouseAccess.requireInventoryView(command.viewerCode());
		if (!warehouseAccess.canViewRawMaterials(command.viewerCode())) return PageResult.of(List.of(), command.page(), command.size(), 0);
		PageRequest page = pageRequest(command.page(), command.size());
		String stockStatus = normalizeFilter(command.stockStatus());
		if (stockStatus != null && !List.of("POSITIVE", "ZERO").contains(stockStatus)) {
			throw DomainException.badRequest("INVALID_STOCK_STATUS", "库存状态仅支持 POSITIVE 或 ZERO");
		}
		List<Object> parameters = new ArrayList<>();
		String where = rawMaterialWhere();
		where = appendWarehouse(where, parameters, command.warehouseCode());
		where = appendBalanceKeyword(where, parameters, command.keyword());
		if (normalizeFilter(command.unit()) != null) {
			where += " and b.unit = ?";
			parameters.add(normalize(command.unit()));
		}
		if ("POSITIVE".equals(stockStatus)) where += " and b.quantity > 0";
		if ("ZERO".equals(stockStatus)) where += " and b.quantity = 0";
		long total = jdbc.queryForObject("select count(*) from inventory_balance b where " + where, Long.class, parameters.toArray());
		List<Object> pageParameters = new ArrayList<>(parameters);
		pageParameters.add(page.size());
		pageParameters.add(page.offset());
		List<BalanceView> items = jdbc.query("""
			select b.id, b.warehouse_code, b.item_code, b.item_name, b.unit, b.quantity, b.version, b.updated_at
			from inventory_balance b where %s
			order by b.updated_at desc, b.item_code asc
			limit ? offset ?
			""".formatted(where), InventoryApplication::mapBalance, pageParameters.toArray());
		return PageResult.of(items, page.page(), page.size(), total);
	}

	@Transactional(readOnly = true)
	public PageResult<MovementView> searchMovements(MovementSearchCommand command) {
		warehouseAccess.requireInventoryView(command.viewerCode());
		if (!warehouseAccess.canViewRawMaterials(command.viewerCode())) return PageResult.of(List.of(), command.page(), command.size(), 0);
		if (command.fromDate() != null && command.toDate() != null && command.fromDate().isAfter(command.toDate())) {
			throw DomainException.badRequest("INVALID_MOVEMENT_DATE_RANGE", "开始日期不能晚于结束日期");
		}
		PageRequest page = pageRequest(command.page(), command.size());
		String movementType = normalizeFilter(command.movementType());
		if (movementType != null && !List.of("RECEIPT", "ISSUE", "ADJUSTMENT_IN", "ADJUSTMENT_OUT").contains(movementType)) {
			throw DomainException.badRequest("INVALID_MOVEMENT_TYPE", "库存类型必须是入库、出库、调增或调减");
		}
		List<Object> parameters = new ArrayList<>();
		String where = rawMaterialWhere();
		where = appendWarehouse(where, parameters, command.warehouseCode());
		where = appendMovementKeyword(where, parameters, command.keyword());
		if (movementType != null) {
			where += " and m.movement_type = ?";
			parameters.add(movementType);
		}
		if (normalizeFilter(command.operatorCode()) != null) {
			where += " and m.operator_code = ?";
			parameters.add(normalize(command.operatorCode()));
		}
		if (command.fromDate() != null) {
			where += " and m.occurred_at >= ?";
			parameters.add(Timestamp.valueOf(command.fromDate().atStartOfDay()));
		}
		if (command.toDate() != null) {
			where += " and m.occurred_at < ?";
			parameters.add(Timestamp.valueOf(command.toDate().plusDays(1).atStartOfDay()));
		}
		long total = jdbc.queryForObject("select count(*) from inventory_movement m join inventory_balance b on b.id = m.balance_id where " + where,
			Long.class, parameters.toArray());
		List<Object> pageParameters = new ArrayList<>(parameters);
		pageParameters.add(page.size());
		pageParameters.add(page.offset());
		List<MovementView> items = jdbc.query("""
			select m.*, b.warehouse_code, b.item_code, b.item_name, b.unit
			from inventory_movement m join inventory_balance b on b.id = m.balance_id
			where %s
			order by m.occurred_at desc, m.movement_no desc
			limit ? offset ?
			""".formatted(where), InventoryApplication::mapMovement, pageParameters.toArray());
		return PageResult.of(items, page.page(), page.size(), total);
	}

	private static String rawMaterialWhere() {
		return "upper(b.warehouse_code) not like 'MOLD%' and upper(b.warehouse_code) not like 'FG%'";
	}

	private static String appendWarehouse(String where, List<Object> parameters, String warehouseCode) {
		if (normalizeFilter(warehouseCode) == null) return where;
		parameters.add(normalize(warehouseCode));
		return where + " and b.warehouse_code = ?";
	}

	private static String appendBalanceKeyword(String where, List<Object> parameters, String keyword) {
		if (normalizeFilter(keyword) == null) return where;
		String term = searchTerm(keyword);
		parameters.add(term);
		parameters.add(term);
		return where + " and (b.item_code like ? or upper(b.item_name) like ?)";
	}

	private static String appendMovementKeyword(String where, List<Object> parameters, String keyword) {
		if (normalizeFilter(keyword) == null) return where;
		String term = searchTerm(keyword);
		for (int index = 0; index < 6; index++) parameters.add(term);
		return where + " and (m.movement_no like ? or b.item_code like ? or upper(b.item_name) like ?"
			+ " or upper(coalesce(m.reference_no, '')) like ? or upper(coalesce(m.operator_code, '')) like ? or upper(coalesce(m.remark, '')) like ?)";
	}

	private static String searchTerm(String keyword) {
		String normalized = keyword.trim().toUpperCase(Locale.ROOT);
		return normalized.matches("[A-Z0-9_-]+") ? normalized + "%" : "%" + normalized + "%";
	}

	private static PageRequest pageRequest(int page, int size) {
		if (page < 0 || size < 1 || size > 100) {
			throw DomainException.badRequest("INVALID_PAGE_REQUEST", "页码必须从 0 开始，单页数量为 1 至 100");
		}
		return new PageRequest(page, size);
	}

	private BalanceView findBalanceForUpdate(String warehouse, String itemCode, String unit) {
		List<BalanceView> rows = jdbc.query("""
			select id, warehouse_code, item_code, item_name, unit, quantity, version, updated_at
			from inventory_balance
			where warehouse_code = ? and item_code = ? and unit = ?
			for update
			""", InventoryApplication::mapBalance, warehouse, itemCode, unit);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private MovementView findMovement(UUID operationId) {
		List<MovementView> rows = jdbc.query("""
			select m.*, b.warehouse_code, b.item_code, b.item_name, b.unit
			from inventory_movement m join inventory_balance b on b.id = m.balance_id
			where m.operation_id = ?
			""", InventoryApplication::mapMovement, operationId);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private MovementView requireMovement(UUID id) {
		return jdbc.query("""
			select m.*, b.warehouse_code, b.item_code, b.item_name, b.unit
			from inventory_movement m join inventory_balance b on b.id = m.balance_id
			where m.id = ?
			""", InventoryApplication::mapMovement, id).getFirst();
	}

	private static BalanceView mapBalance(ResultSet rs, int rowNum) throws SQLException {
		return new BalanceView(
			rs.getObject("id", UUID.class),
			rs.getString("warehouse_code"),
			rs.getString("item_code"),
			rs.getString("item_name"),
			rs.getString("unit"),
			rs.getBigDecimal("quantity"),
			rs.getLong("version"),
			rs.getTimestamp("updated_at").toInstant()
		);
	}

	private static MovementView mapMovement(ResultSet rs, int rowNum) throws SQLException {
		return new MovementView(
			rs.getObject("id", UUID.class),
			rs.getObject("operation_id", UUID.class),
			rs.getString("movement_no"),
			rs.getObject("balance_id", UUID.class),
			rs.getString("warehouse_code"),
			rs.getString("item_code"),
			rs.getString("item_name"),
			rs.getString("unit"),
			rs.getString("movement_type"),
			rs.getBigDecimal("quantity"),
			rs.getBigDecimal("balance_after"),
			rs.getString("reference_type"),
			rs.getString("reference_no"),
			rs.getString("operator_code"),
			rs.getString("remark"),
			rs.getTimestamp("occurred_at").toInstant()
		);
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String normalizeFilter(String value) {
		return value == null || value.isBlank() || "ALL".equalsIgnoreCase(value) ? null : normalize(value);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String identifier(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

	public record MovementCommand(
		UUID operationId,
		String warehouseCode,
		String itemCode,
		String itemName,
		String unit,
		String movementType,
		BigDecimal quantity,
		String referenceType,
		String referenceNo,
		String operatorCode,
		String remark
	) {
	}

	public record MovementResult(MovementView movement, boolean duplicate) {
	}

	public record BalanceSearchCommand(String viewerCode, String warehouseCode, String keyword, String unit,
		String stockStatus, int page, int size) {
	}

	public record MovementSearchCommand(String viewerCode, String warehouseCode, String keyword, String movementType,
		String operatorCode, LocalDate fromDate, LocalDate toDate, int page, int size) {
	}

	private record PageRequest(int page, int size) {
		private long offset() { return (long) page * size; }
	}

	public record BalanceView(
		UUID id,
		String warehouseCode,
		String itemCode,
		String itemName,
		String unit,
		BigDecimal quantity,
		long version,
		Instant updatedAt
	) {
	}

	public record MovementView(
		UUID id,
		UUID operationId,
		String movementNo,
		UUID balanceId,
		String warehouseCode,
		String itemCode,
		String itemName,
		String unit,
		String movementType,
		BigDecimal quantity,
		BigDecimal balanceAfter,
		String referenceType,
		String referenceNo,
		String operatorCode,
		String remark,
		Instant occurredAt
	) {
	}
}

@RestController
@RequestMapping("/api/inventory")
class InventoryController {

	private final InventoryApplication inventory;

	InventoryController(InventoryApplication inventory) {
		this.inventory = inventory;
	}

	@GetMapping("/balances")
	List<InventoryApplication.BalanceView> balances(@RequestParam String viewerCode) {
		return inventory.listBalances(viewerCode);
	}

	@GetMapping("/balances/query")
	PageResult<InventoryApplication.BalanceView> searchBalances(@RequestParam String viewerCode,
			@RequestParam(required = false) String warehouseCode, @RequestParam(required = false) String keyword,
			@RequestParam(required = false) String unit, @RequestParam(required = false) String stockStatus,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return inventory.searchBalances(new InventoryApplication.BalanceSearchCommand(
			viewerCode, warehouseCode, keyword, unit, stockStatus, page, size));
	}

	@GetMapping("/movements")
	List<InventoryApplication.MovementView> movements(@RequestParam String viewerCode,
			@RequestParam(required = false) String itemCode) {
		return inventory.listMovements(viewerCode, itemCode);
	}

	@GetMapping("/movements/query")
	PageResult<InventoryApplication.MovementView> searchMovements(@RequestParam String viewerCode,
			@RequestParam(required = false) String warehouseCode, @RequestParam(required = false) String keyword,
			@RequestParam(required = false) String movementType, @RequestParam(required = false) String operatorCode,
			@RequestParam(required = false) LocalDate fromDate, @RequestParam(required = false) LocalDate toDate,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return inventory.searchMovements(new InventoryApplication.MovementSearchCommand(
			viewerCode, warehouseCode, keyword, movementType, operatorCode, fromDate, toDate, page, size));
	}

	@PostMapping("/movements")
	ResponseEntity<InventoryApplication.MovementResult> move(@Valid @RequestBody MovementRequest request) {
		InventoryApplication.MovementResult result = inventory.move(new InventoryApplication.MovementCommand(
			request.operationId(),
			request.warehouseCode(),
			request.itemCode(),
			request.itemName(),
			request.unit(),
			request.movementType(),
			request.quantity(),
			request.referenceType(),
			request.referenceNo(),
			request.operatorCode(),
			request.remark()
		));
		return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
	}

	record MovementRequest(
		@NotNull UUID operationId,
		@NotBlank @Size(max = 64) String warehouseCode,
		@NotBlank @Size(max = 64) String itemCode,
		@NotBlank @Size(max = 160) String itemName,
		@NotBlank @Size(max = 16) String unit,
		@NotBlank String movementType,
		@NotNull @DecimalMin("0.001") BigDecimal quantity,
		@Size(max = 64) String referenceType,
		@Size(max = 64) String referenceNo,
		@NotBlank @Size(max = 64) String operatorCode,
		@Size(max = 500) String remark
	) {
	}
}
