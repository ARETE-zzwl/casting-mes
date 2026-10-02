package com.renyi.mes.resource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.MoldTaskPort;
import com.renyi.mes.common.MoldTaskPort.MoldRequestSnapshot;
import com.renyi.mes.resource.internal.MoldStorageLocations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
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
public class MoldApplication implements MoldTaskPort {

	private final JdbcTemplate jdbc;
	private final ResourceApplication resources;
	private final AssetQrApplication labels;
	private final ProductMoldCatalogApplication productMolds;
	private final com.renyi.mes.common.BusinessAccess access;
	private final MoldStorageLocations moldLocations;

	public MoldApplication(JdbcTemplate jdbc, ResourceApplication resources, AssetQrApplication labels,
			ProductMoldCatalogApplication productMolds, com.renyi.mes.common.BusinessAccess access, MoldStorageLocations moldLocations) {
		this.jdbc = jdbc;
		this.resources = resources;
		this.labels = labels;
		this.productMolds = productMolds;
		this.access = access;
		this.moldLocations = moldLocations;
	}

	@Transactional
	public MoldRequestView request(RequestCommand command) {
		UUID assetId = command.moldAssetId();
		MoldCustody custody = null;
		if (assetId != null) {
			custody = requireMold(assetId, false);
		}
		Instant now = Instant.now();
		UUID id = UUID.randomUUID();
		String status = assetId == null ? "CUSTOM_MOLD_REQUIRED" : "REQUESTED";
		jdbc.update("""
			insert into mold_request (
				id, request_no, mold_asset_id, product_code, mold_ownership_type, mold_owner_name,
				status, requested_by, requested_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
			""", id, identifier(), assetId, normalize(command.productCode()),
			custody == null ? null : custody.ownershipType(), custody == null ? null : custody.ownerName(), status,
			normalize(command.requestedBy()), Timestamp.from(now));
		movement(id, "REQUESTED", command.requestedBy(), null, now);
		return require(id, false);
	}

	@Transactional
	public MoldRequestView requestForWaxTask(UUID taskId, TaskRequestCommand command) {
		WaxTaskContext task = requireWaxTask(taskId);
		if (task.processAuthorizedBy() == null) {
			throw DomainException.conflict("ENGINEERING_NOT_CONFIRMED", "工程师补齐订单专属参数后才能申请领用模具");
		}
		if (jdbc.queryForObject("select count(*) from mold_request where order_line_id = ?", Integer.class,
			task.orderLineId()) > 0) {
			throw DomainException.conflict("ORDER_LINE_MOLD_ALREADY_REQUESTED", "该订单产品已存在模具领用申请");
		}
		UUID selectedAssetId = selectedMold(task.orderLineId());
		UUID moldAssetId = selectedAssetId == null ? command.moldAssetId() : selectedAssetId;
		if (moldAssetId == null) {
			throw DomainException.conflict("ORDER_MOLD_NOT_SELECTED", "请先由工程师或主管为订单产品选定模具");
		}
		if (selectedAssetId == null) {
			selectForOrderLine(task.orderId(), task.orderLineId(), moldAssetId, command.supervisorCode());
		}
		MoldUsage activeUsage = activeMoldUsage(moldAssetId);
		if (activeUsage != null) throw activeMoldConflict(activeUsage);
		MoldCustody custody = requireMold(moldAssetId, false);
		Instant now = Instant.now();
		UUID id = UUID.randomUUID();
		jdbc.update("""
			insert into mold_request (
				id, request_no, mold_asset_id, product_code, mold_ownership_type, mold_owner_name,
				order_id, order_line_id, work_order_id, wax_task_id, issued_to_worker_code,
				status, requested_by, engineer_code, requested_at, approved_at
			) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED', ?, ?, ?, ?)
			""", id, identifier(), moldAssetId, task.productCode(), custody.ownershipType(), custody.ownerName(),
			task.orderId(), task.orderLineId(), task.workOrderId(), taskId, normalize(command.intendedWorkerCode()),
			normalize(command.supervisorCode()), task.processAuthorizedBy(), Timestamp.from(now), Timestamp.from(now));
		movement(id, "REQUESTED", command.supervisorCode(), null, now);
		movement(id, "APPROVED", task.processAuthorizedBy(), null, now);
		return require(id, false);
	}

	@Transactional(readOnly = true)
	public MoldRequestView findForWaxTask(UUID taskId) {
		WaxTaskContext task = requireWaxTask(taskId);
		return jdbc.query("select * from mold_request where order_line_id = ? order by requested_at desc", MoldApplication::map, task.orderLineId())
			.stream().findFirst().orElse(null);
	}

	@Override
	@Transactional(readOnly = true)
	public MoldRequestSnapshot findTaskMold(UUID taskId) {
		return toTaskMoldSnapshot(findForWaxTask(taskId));
	}

	@Override
	@Transactional
	public MoldRequestSnapshot requestTaskMold(UUID taskId, UUID moldAssetId, String supervisorCode, String workerCode) {
		return toTaskMoldSnapshot(requestForWaxTask(taskId,
			new TaskRequestCommand(moldAssetId, supervisorCode, workerCode)));
	}

	@Override
	@Transactional
	public void issueTaskMold(UUID requestId, UUID taskId, String warehouseCode, String warehouseOperatorCode, String workerCode) {
		issueForWaxTask(requestId, taskId, new IssueCommand(warehouseCode, warehouseOperatorCode, workerCode));
	}

	@Override
	@Transactional
	public void returnTaskMold(UUID requestId, String supervisorCode) {
		returnMold(requestId, supervisorCode);
	}

	@Transactional
	public MoldRequestView approve(UUID id, String engineerCode) {
		MoldRequestView request = require(id, true);
		if (!"REQUESTED".equals(request.status())) {
			throw DomainException.conflict("MOLD_REQUEST_STATE_CONFLICT", "当前模具申请不能由工程确认");
		}
		Instant now = Instant.now();
		jdbc.update("""
			update mold_request set status = 'APPROVED', engineer_code = ?, approved_at = ? where id = ?
			""", normalize(engineerCode), Timestamp.from(now), id);
		movement(id, "APPROVED", engineerCode, null, now);
		return require(id, false);
	}

	@Transactional
	public MoldRequestView issue(UUID id, IssueCommand command) {
		return issue(id, command, null);
	}

	@Transactional
	public MoldRequestView issueForWaxTask(UUID id, UUID taskId, IssueCommand command) {
		WaxTaskContext task = requireWaxTask(taskId);
		MoldRequestView request = require(id, true);
		if (request.orderLineId() == null || !request.orderLineId().equals(task.orderLineId())) {
			throw DomainException.conflict("MOLD_REQUEST_TASK_MISMATCH", "模具领用申请不属于当前订单产品");
		}
		return issue(request, command, taskId);
	}

	private MoldRequestView issue(UUID id, IssueCommand command, UUID waxTaskId) {
		MoldRequestView request = require(id, true);
		return issue(request, command, waxTaskId);
	}

	private MoldRequestView issue(MoldRequestView request, IssueCommand command, UUID waxTaskId) {
		UUID id = request.id();
		if (!("APPROVED".equals(request.status()) || "RETURNED".equals(request.status())) || request.moldAssetId() == null) {
			throw DomainException.conflict("MOLD_REQUEST_STATE_CONFLICT", "仅已确认且已有模具的申请可以出库");
		}
		requireMold(request.moldAssetId(), true);
		String recipient = command.recipientWorkerCode() == null || command.recipientWorkerCode().isBlank()
			? request.issuedToWorkerCode() == null ? request.requestedBy() : request.issuedToWorkerCode()
			: normalize(command.recipientWorkerCode());
		if (recipient == null || recipient.isBlank()) {
			throw DomainException.badRequest("MOLD_RECIPIENT_REQUIRED", "模具出库时必须填写接收射蜡工");
		}
		MoldUsage activeUsage = activeMoldUsage(request.moldAssetId());
		if (activeUsage != null) throw activeMoldConflict(activeUsage);
		resources.occupy(request.moldAssetId(), new ResourceApplication.OccupyCommand(
			"MOLD_REQUEST:" + id, command.operatorCode(), "模具仓出库，交接给 " + recipient));
		Instant now = Instant.now();
		setCustody(request.moldAssetId(), "INTERNAL_IN_USE", now);
		jdbc.update("""
			update mold_request set status = 'ISSUED', warehouse_code = ?, issued_to_worker_code = ?, issued_at = ?, wax_task_id = ? where id = ?
			""", normalize(command.warehouseCode()), recipient, Timestamp.from(now),
			waxTaskId == null ? request.waxTaskId() : waxTaskId, request.id());
		movement(request.id(), "ISSUED", command.operatorCode(), command.warehouseCode(), now);
		return require(request.id(), false);
	}

	@Transactional
	public MoldRequestView returnMold(UUID id, String operatorCode) {
		MoldRequestView request = require(id, true);
		if (!"ISSUED".equals(request.status()) || request.moldAssetId() == null) {
			throw DomainException.conflict("MOLD_REQUEST_STATE_CONFLICT", "仅已领出的模具可以归还");
		}
		resources.release(request.moldAssetId(), new ResourceApplication.ReleaseCommand(operatorCode, false));
		Instant now = Instant.now();
		setCustody(request.moldAssetId(), "IN_STOCK", now);
		jdbc.update("update mold_request set status = 'RETURNED', returned_at = ? where id = ?",
			Timestamp.from(now), id);
		movement(id, "RETURNED", operatorCode, request.warehouseCode(), now);
		return require(id, false);
	}

	@Transactional(readOnly = true)
	public List<MoldRequestView> list() {
		return jdbc.query("select * from mold_request order by requested_at desc", MoldApplication::map).stream()
			.filter(request -> !access.secured() || access.permission("MOLD_WAREHOUSE_MANAGE", "MOLD_RECEIVE")
				|| request.orderId() != null && access.canReadOrder(request.orderId()) || request.requestedBy().equals(access.actor())).toList();
	}

	@Transactional(readOnly = true)
	public List<OrderMoldSelectionView> orderSelections() {
		return jdbc.query("""
			select s.*, o.order_no, o.customer_id, o.customer_name, l.line_no, l.product_code, l.product_name, l.route_type,
			       a.asset_code, a.asset_name
			from order_mold_selection s
			join customer_order_header o on o.id = s.order_id
			join customer_order_line l on l.id = s.order_line_id
			left join resource_asset a on a.id = s.mold_asset_id
			order by s.updated_at desc
			""", (rs, row) -> new OrderMoldSelectionView(rs.getObject("id", UUID.class),
			rs.getObject("order_id", UUID.class), rs.getString("order_no"), rs.getObject("customer_id", UUID.class),
			rs.getString("customer_name"), rs.getObject("order_line_id", UUID.class),
			rs.getInt("line_no"), rs.getString("product_code"), rs.getString("product_name"), rs.getString("route_type"),
			rs.getObject("mold_asset_id", UUID.class), rs.getString("asset_code"), rs.getString("asset_name"),
			rs.getString("mold_ownership_type"), rs.getString("mold_owner_name"), rs.getString("selection_status"),
			rs.getString("pending_reason"), rs.getString("selected_by"),
			rs.getTimestamp("selected_at").toInstant(), rs.getTimestamp("updated_at").toInstant())).stream()
			.filter(selection -> !access.secured() || access.permission("MOLD_WAREHOUSE_MANAGE", "MOLD_RECEIVE") || access.canReadOrder(selection.orderId())).toList();
	}

	@Transactional
	public ResourceApplication.AssetView receive(ReceiveCommand command) {
		OrderLineContext targetLine = command.orderLineId() == null ? null : requireOrderLine(command.orderLineId());
		if (targetLine != null) {
			OrderMoldSelectionView planned = requirePendingSelection(targetLine.orderLineId());
			validateReceiptAgainstPlan(planned, targetLine, command);
		}
		String assetCode = command.assetCode() == null || command.assetCode().isBlank()
			? "MOLD-" + LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT)
			: normalize(command.assetCode());
		ResourceApplication.AssetView asset = resources.register(new ResourceApplication.RegisterCommand(
			assetCode, command.assetName(), "MOLD", moldLocations.allocate(command.locationCode()), command.lifeLimit(),
			command.ownershipType(), command.ownerName()));
		if (command.scannedValue() != null && !command.scannedValue().isBlank()) {
			labels.bind(new AssetQrApplication.BindCommand(command.scannedValue(), asset.id(), command.operatorCode()));
		}
		if (targetLine != null) {
			selectForOrderLine(targetLine.orderId(), targetLine.orderLineId(), asset.id(), command.operatorCode());
		}
		return asset;
	}

	@Transactional(readOnly = true)
	public List<LocationSuggestion> locationSuggestions() {
		return jdbc.query("""
			select l.location_code, count(a.id) as occupied_count, l.capacity
			from mold_storage_location l
			left join resource_asset a on a.asset_type = 'MOLD' and a.location_code = l.location_code
			where l.active = true
			group by l.location_code, l.capacity
			order by count(a.id), l.location_code
			""", (rs, row) -> new LocationSuggestion(rs.getString("location_code"),
			rs.getInt("occupied_count"), rs.getInt("capacity")));
	}

	@Transactional(readOnly = true)
	public List<StorageLocationView> storageLocations() {
		return jdbc.query("""
			select l.location_code, l.location_name, l.capacity, l.active, count(a.id) as occupied_count,
				l.created_by, l.created_at, l.updated_at
			from mold_storage_location l
			left join resource_asset a on a.asset_type = 'MOLD' and a.location_code = l.location_code
			group by l.location_code, l.location_name, l.capacity, l.active, l.created_by, l.created_at, l.updated_at
			order by l.location_code
			""", (rs, row) -> new StorageLocationView(rs.getString("location_code"), rs.getString("location_name"),
			rs.getInt("capacity"), rs.getBoolean("active"), rs.getInt("occupied_count"), rs.getString("created_by"),
			rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()));
	}

	@Transactional
	public StorageLocationView createStorageLocation(CreateStorageLocationCommand command) {
		String code = normalize(command.locationCode());
		Integer existing = jdbc.queryForObject("select count(*) from mold_storage_location where location_code = ?", Integer.class, code);
		if (existing != null && existing > 0) throw DomainException.conflict("MOLD_LOCATION_EXISTS", "模具库位编码已存在");
		Instant now = Instant.now();
		jdbc.update("""
			insert into mold_storage_location (location_code, location_name, capacity, active, created_by, created_at, updated_at)
			values (?, ?, ?, true, ?, ?, ?)
			""", code, command.locationName().trim(), command.capacity(), normalize(command.operatorCode()), Timestamp.from(now), Timestamp.from(now));
		return storageLocation(code);
	}

	@Transactional
	public StorageLocationView updateStorageLocation(String locationCode, UpdateStorageLocationCommand command) {
		String code = normalize(locationCode);
		moldLocations.lock(code);
		int occupied = moldLocations.occupancy(code);
		if (command.capacity() < occupied) throw DomainException.conflict("MOLD_LOCATION_CAPACITY_TOO_SMALL", "库位容量不能小于当前已占用数量");
		if (!command.active() && occupied > 0) throw DomainException.conflict("MOLD_LOCATION_IN_USE", "库位仍有在库模具，不能停用");
		Instant now = Instant.now();
		int updated = jdbc.update("""
			update mold_storage_location set location_name = ?, capacity = ?, active = ?, updated_at = ? where location_code = ?
			""", command.locationName().trim(), command.capacity(), command.active(), Timestamp.from(now), code);
		if (updated == 0) throw DomainException.notFound("MOLD_LOCATION_NOT_FOUND", "模具库位不存在");
		return storageLocation(code);
	}

	@Transactional
	public ExternalMovementView checkOutExternally(ExternalCheckoutCommand command) {
		MoldCustody mold = requireMold(command.moldAssetId(), true);
		resources.occupy(command.moldAssetId(), new ResourceApplication.OccupyCommand(
			"MOLD_EXTERNAL:" + UUID.randomUUID(), command.operatorCode(), "模具对外出库：" + command.reasonCode()));
		Instant now = Instant.now();
		setCustody(command.moldAssetId(), "EXTERNAL_OUT", now);
		UUID id = UUID.randomUUID();
		jdbc.update("""
			insert into mold_external_movement (
				id, movement_no, mold_asset_id, movement_type, reason_code, reason_note, counterparty_name,
				expected_return_date, status, checked_out_by, checked_out_at
			) values (?, ?, ?, 'OUTBOUND', ?, ?, ?, ?, 'OPEN', ?, ?)
			""", id, externalMovementNo(), command.moldAssetId(), normalizeReason(command.reasonCode()), trim(command.reasonNote()),
			trim(command.counterpartyName()), command.expectedReturnDate(), normalize(command.operatorCode()), Timestamp.from(now));
		return requireExternal(id, false);
	}

	@Transactional
	public ExternalMovementView returnFromExternal(UUID movementId, ExternalReturnCommand command) {
		ExternalMovementView movement = requireExternal(movementId, true);
		if (!"OPEN".equals(movement.status())) throw DomainException.conflict("MOLD_EXTERNAL_STATUS_CONFLICT", "该对外出库记录已归还");
		resources.release(movement.moldAssetId(), new ResourceApplication.ReleaseCommand(command.operatorCode(), false));
		Instant now = Instant.now();
		setCustody(movement.moldAssetId(), "IN_STOCK", now);
		jdbc.update("update mold_external_movement set status = 'RETURNED', returned_by = ?, returned_at = ? where id = ?",
			normalize(command.operatorCode()), Timestamp.from(now), movementId);
		return requireExternal(movementId, false);
	}

	@Transactional(readOnly = true)
	public List<ExternalMovementView> externalMovements() {
		return jdbc.query(externalSelect() + " order by m.checked_out_at desc", MoldApplication::mapExternal);
	}

	@Transactional(readOnly = true)
	public List<ExternalMovementView> overdueMaintenance() {
		return jdbc.query(externalSelect() + " where m.status = 'OPEN' and m.reason_code = 'MAINTENANCE' and m.expected_return_date < current_date order by m.expected_return_date", MoldApplication::mapExternal);
	}

	@Transactional
	public OrderMoldSelectionView selectForOrderLine(UUID orderId, UUID orderLineId, UUID moldAssetId, String selectedBy) {
		OrderLineContext line = requireOrderLine(orderLineId);
		if (!line.orderId().equals(orderId)) throw DomainException.notFound("ORDER_LINE_NOT_FOUND", "订单产品不存在");
		MoldCustody custody = requireMold(moldAssetId, false);
		Instant now = Instant.now();
		Integer existing = jdbc.queryForObject("select count(*) from order_mold_selection where order_line_id = ?", Integer.class, orderLineId);
		if (existing != null && existing > 0) {
			jdbc.update("""
				update order_mold_selection set mold_asset_id = ?, mold_ownership_type = ?, mold_owner_name = ?,
					selection_status = 'SELECTED', pending_reason = null, selected_by = ?, selected_at = ?, updated_at = ? where order_line_id = ?
				""", moldAssetId, custody.ownershipType(), custody.ownerName(), normalize(selectedBy),
				Timestamp.from(now), Timestamp.from(now), orderLineId);
		} else {
			jdbc.update("""
				insert into order_mold_selection (id, order_id, order_line_id, mold_asset_id, mold_ownership_type, mold_owner_name, selection_status, selected_by, selected_at, updated_at)
				values (?, ?, ?, ?, ?, ?, 'SELECTED', ?, ?, ?)
				""", UUID.randomUUID(), orderId, orderLineId, moldAssetId, custody.ownershipType(), custody.ownerName(),
				normalize(selectedBy), Timestamp.from(now), Timestamp.from(now));
		}
		int relationUpdated = jdbc.update("""
			update customer_product_mold_relation set created_by = ?, last_used_at = ?
			where customer_id = ? and product_id = ? and mold_asset_id = ?
			""", normalize(selectedBy), Timestamp.from(now), line.customerId(), line.productId(), moldAssetId);
		if (relationUpdated == 0) {
			try {
				jdbc.update("""
					insert into customer_product_mold_relation (id, customer_id, product_id, mold_asset_id, created_by, created_at, last_used_at)
					values (?, ?, ?, ?, ?, ?, ?)
					""", UUID.randomUUID(), line.customerId(), line.productId(), moldAssetId, normalize(selectedBy),
					Timestamp.from(now), Timestamp.from(now));
			} catch (DuplicateKeyException ignored) {
				jdbc.update("""
					update customer_product_mold_relation set created_by = ?, last_used_at = ?
					where customer_id = ? and product_id = ? and mold_asset_id = ?
					""", normalize(selectedBy), Timestamp.from(now), line.customerId(), line.productId(), moldAssetId);
			}
		}
		productMolds.recordOrderUsage(line.productId(), moldAssetId, selectedBy);
		return orderSelections().stream().filter(selection -> selection.orderLineId().equals(orderLineId)).findFirst().orElseThrow();
	}

	@Transactional
	public OrderMoldSelectionView planForOrderLine(OrderMoldPlanCommand command) {
		OrderLineContext line = requireOrderLine(command.orderLineId());
		if (!line.orderId().equals(command.orderId())) {
			throw DomainException.badRequest("ORDER_LINE_NOT_FOUND", "订单产品不属于指定订单");
		}
		if (!("MID_TEMP_WAX".equals(line.routeType()) || "LOW_TEMP_WAX".equals(line.routeType()))) {
			throw DomainException.badRequest("ORDER_MOLD_ROUTE_INVALID", "仅中温蜡和低温蜡订单需要安排模具");
		}
		String status = normalizePlanStatus(command.selectionStatus());
		Instant now = Instant.now();
		Integer existing = jdbc.queryForObject("select count(*) from order_mold_selection where order_line_id = ?", Integer.class, line.orderLineId());
		if (existing != null && existing > 0) {
			jdbc.update("""
				update order_mold_selection set mold_asset_id = null, mold_ownership_type = null, mold_owner_name = null,
					selection_status = ?, pending_reason = ?, selected_by = ?, selected_at = ?, updated_at = ? where order_line_id = ?
				""", status, trim(command.pendingReason()), normalize(command.selectedBy()), Timestamp.from(now), Timestamp.from(now), line.orderLineId());
		} else {
			jdbc.update("""
				insert into order_mold_selection (id, order_id, order_line_id, selection_status, pending_reason, selected_by, selected_at, updated_at)
				values (?, ?, ?, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), line.orderId(), line.orderLineId(), status, trim(command.pendingReason()),
				normalize(command.selectedBy()), Timestamp.from(now), Timestamp.from(now));
		}
		return orderSelections().stream().filter(selection -> selection.orderLineId().equals(line.orderLineId())).findFirst().orElseThrow();
	}

	private UUID selectedMold(UUID orderLineId) {
		return jdbc.query("select mold_asset_id from order_mold_selection where order_line_id = ?",
			(rs, row) -> rs.getObject(1, UUID.class), orderLineId).stream().findFirst().orElse(null);
	}

	private OrderMoldSelectionView requirePendingSelection(UUID orderLineId) {
		return orderSelections().stream().filter(selection -> selection.orderLineId().equals(orderLineId))
			.filter(selection -> !"SELECTED".equals(selection.selectionStatus())).findFirst()
			.orElseThrow(() -> DomainException.conflict("ORDER_MOLD_RECEIPT_NOT_PENDING", "该订单产品没有待入库的模具安排"));
	}

	private OrderLineContext requireOrderLine(UUID orderLineId) {
		return jdbc.query("""
			select l.id as order_line_id, l.order_id, l.product_id, l.route_type, o.customer_id, o.customer_name
			from customer_order_line l join customer_order_header o on o.id = l.order_id
			where l.id = ?
			""", (rs, row) -> new OrderLineContext(rs.getObject("order_line_id", UUID.class),
			rs.getObject("order_id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("route_type"),
			rs.getObject("customer_id", UUID.class), rs.getString("customer_name")), orderLineId)
			.stream().findFirst().orElseThrow(() -> DomainException.notFound("ORDER_LINE_NOT_FOUND", "订单产品不存在"));
	}

	private static void validateReceiptAgainstPlan(OrderMoldSelectionView plan, OrderLineContext line, ReceiveCommand command) {
		if ("CUSTOMER_DELIVERY_PENDING".equals(plan.selectionStatus())) {
			if (!"CUSTOMER_OWNED".equals(normalize(command.ownershipType()))) {
				throw DomainException.badRequest("ORDER_MOLD_RECEIPT_OWNERSHIP_INVALID", "待客户送模必须按客户寄存模具入库");
			}
			if (command.ownerName() == null || !line.customerName().equalsIgnoreCase(command.ownerName().trim())) {
				throw DomainException.badRequest("ORDER_MOLD_RECEIPT_CUSTOMER_MISMATCH", "客户送达模具的所有方必须与订单客户一致");
			}
		}
	}

	private static String normalizePlanStatus(String value) {
		String status = normalize(value);
		if (!"CUSTOM_MOLD_TO_RECEIVE".equals(status) && !"CUSTOMER_DELIVERY_PENDING".equals(status)) {
			throw DomainException.badRequest("ORDER_MOLD_PLAN_STATUS_INVALID", "模具待入库状态必须为定制后入库或待客户送模");
		}
		return status;
	}

	private MoldCustody requireMold(UUID assetId, boolean lock) {
		String sql = "select asset_type, ownership_type, owner_name, status, mold_custody_status, mold_lock_reason from resource_asset where id = ?" + (lock ? " for update" : "");
		MoldCustody custody = jdbc.query(sql, (rs, row) -> new MoldCustody(
			rs.getString("asset_type"), rs.getString("ownership_type"), rs.getString("owner_name"), rs.getString("status"), rs.getString("mold_custody_status"), rs.getString("mold_lock_reason")), assetId).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("MOLD_ASSET_NOT_FOUND", "模具资产不存在"));
		if (!"MOLD".equals(custody.assetType())) {
			throw DomainException.badRequest("MOLD_ASSET_INVALID", "指定资产不是模具");
		}
		if (custody.lockReason() != null) {
			throw DomainException.conflict("MOLD_LOCKED", "该模具已异常锁定：" + custody.lockReason());
		}
		if (!"AVAILABLE".equals(custody.resourceStatus()) || !"IN_STOCK".equals(custody.custodyStatus())) {
			throw DomainException.conflict("MOLD_NOT_IN_STOCK", "仅在库且未被占用的模具可以选定或出库");
		}
		return custody;
	}

	private void setCustody(UUID moldAssetId, String custodyStatus, Instant now) {
		jdbc.update("update resource_asset set mold_custody_status = ?, updated_at = ?, version = version + 1 where id = ?",
			custodyStatus, Timestamp.from(now), moldAssetId);
	}

	private ExternalMovementView requireExternal(UUID id, boolean lock) {
		return jdbc.query(externalSelect() + " where m.id = ?" + (lock ? " for update" : ""), MoldApplication::mapExternal, id)
			.stream().findFirst().orElseThrow(() -> DomainException.notFound("MOLD_EXTERNAL_MOVEMENT_NOT_FOUND", "模具对外出库记录不存在"));
	}

	private static String externalSelect() {
		return "select m.*, a.asset_code, a.asset_name, a.location_code from mold_external_movement m join resource_asset a on a.id = m.mold_asset_id";
	}

	private static ExternalMovementView mapExternal(ResultSet rs, int row) throws SQLException {
		return new ExternalMovementView(rs.getObject("id", UUID.class), rs.getString("movement_no"), rs.getObject("mold_asset_id", UUID.class),
			rs.getString("asset_code"), rs.getString("asset_name"), rs.getString("location_code"), rs.getString("reason_code"),
			rs.getString("reason_note"), rs.getString("counterparty_name"), rs.getDate("expected_return_date") == null ? null : rs.getDate("expected_return_date").toLocalDate(),
			rs.getString("status"), rs.getString("checked_out_by"), rs.getTimestamp("checked_out_at").toInstant(),
			rs.getString("returned_by"), instant(rs, "returned_at"));
	}

	private MoldUsage activeMoldUsage(UUID moldAssetId) {
		return jdbc.query("""
			select header.order_no, line.product_code, line.product_name, work_order.product_material, batch.batch_no
			from mold_request r
			left join customer_order_header header on header.id = r.order_id
			left join customer_order_line line on line.id = r.order_line_id
			left join planning_task task on task.id = r.wax_task_id
			left join planning_batch batch on batch.id = task.batch_id
			left join planning_work_order work_order on work_order.id = batch.work_order_id
			where r.mold_asset_id = ? and r.status = 'ISSUED'
			order by r.issued_at desc
			""", (rs, row) -> new MoldUsage(rs.getString("order_no"), rs.getString("product_code"),
			rs.getString("product_name"), rs.getString("product_material"), rs.getString("batch_no")), moldAssetId)
			.stream().findFirst().orElse(null);
	}

	private DomainException activeMoldConflict(MoldUsage usage) {
		return DomainException.conflict("MOLD_ACTIVE_FOR_OTHER_BATCH", String.format(
			"Mold is occupied by order %s, product %s %s, material %s, batch %s. Return it before dispatching another batch.",
			usage.orderNo(), usage.productCode(), usage.productName(),
			usage.productMaterial() == null ? "not recorded" : usage.productMaterial(),
			usage.batchNo() == null ? "not recorded" : usage.batchNo()));
	}

	private MoldRequestView require(UUID id, boolean lock) {
		String sql = "select * from mold_request where id = ?" + (lock ? " for update" : "");
		return jdbc.query(sql, MoldApplication::map, id).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("MOLD_REQUEST_NOT_FOUND", "模具申请不存在"));
	}

	private WaxTaskContext requireWaxTask(UUID taskId) {
		return jdbc.query("""
			select wo.order_id, wo.order_line_id, wo.id as work_order_id, wo.product_code,
				o.engineering_confirmed_by, o.default_process_released_by
			from planning_task task
			join planning_batch batch on batch.id = task.batch_id
			join planning_work_order wo on wo.id = batch.work_order_id
			join customer_order_header o on o.id = wo.order_id
			where task.id = ? and task.operation_code = 'WAX_INJECTION'
			""", (rs, row) -> new WaxTaskContext(
				rs.getObject("order_id", UUID.class), rs.getObject("order_line_id", UUID.class),
				rs.getObject("work_order_id", UUID.class), rs.getString("product_code"),
				rs.getString("engineering_confirmed_by"), rs.getString("default_process_released_by")), taskId).stream().findFirst()
			.orElseThrow(() -> DomainException.badRequest("WAX_TASK_REQUIRED", "仅射蜡任务可以申请模具领用"));
	}

	private void movement(UUID requestId, String type, String operatorCode, String warehouseCode, Instant now) {
		jdbc.update("""
			insert into mold_movement (id, mold_request_id, movement_type, operator_code, warehouse_code, occurred_at)
			values (?, ?, ?, ?, ?, ?)
			""", UUID.randomUUID(), requestId, type, normalize(operatorCode),
			warehouseCode == null ? null : normalize(warehouseCode), Timestamp.from(now));
	}

	private static MoldRequestView map(ResultSet rs, int rowNum) throws SQLException {
		return new MoldRequestView(rs.getObject("id", UUID.class), rs.getString("request_no"),
			rs.getObject("mold_asset_id", UUID.class), rs.getString("product_code"), rs.getString("status"),
			rs.getString("mold_ownership_type"), rs.getString("mold_owner_name"), rs.getString("requested_by"),
			rs.getString("engineer_code"), rs.getString("warehouse_code"),
			rs.getTimestamp("requested_at").toInstant(), instant(rs, "approved_at"), instant(rs, "issued_at"),
			instant(rs, "returned_at"), rs.getObject("order_id", UUID.class),
			rs.getObject("order_line_id", UUID.class), rs.getObject("work_order_id", UUID.class),
			rs.getObject("wax_task_id", UUID.class), rs.getString("issued_to_worker_code"));
	}

	private static MoldRequestSnapshot toTaskMoldSnapshot(MoldRequestView request) {
		return request == null ? null : new MoldRequestSnapshot(request.id(), request.status(), request.waxTaskId());
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String identifier() {
		return "MR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

	private static String externalMovementNo() {
		return "ME-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

	private static String normalizeReason(String value) {
		String reason = normalize(value);
		if (!List.of("CUSTOMER_RECALL", "MAINTENANCE", "OTHER").contains(reason)) {
			throw DomainException.badRequest("MOLD_EXTERNAL_REASON_INVALID", "对外出库原因必须为客户召回、维修保养或其他");
		}
		return reason;
	}

	private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
	private StorageLocationView storageLocation(String locationCode) {
		return storageLocations().stream().filter(location -> location.locationCode().equals(locationCode)).findFirst()
			.orElseThrow(() -> DomainException.notFound("MOLD_LOCATION_NOT_FOUND", "模具库位不存在"));
	}

	public record RequestCommand(UUID moldAssetId, String productCode, String requestedBy) {
	}

	public record TaskRequestCommand(UUID moldAssetId, String supervisorCode, String intendedWorkerCode) {
	}

	public record IssueCommand(String warehouseCode, String operatorCode, String recipientWorkerCode) {
	}

	public record ReceiveCommand(String scannedValue, String assetCode, String assetName, String locationCode,
			Integer lifeLimit, String ownershipType, String ownerName, String operatorCode, UUID orderLineId) { }

	public record OrderMoldPlanCommand(UUID orderId, UUID orderLineId, String selectionStatus, String pendingReason,
			String selectedBy) { }

	public record ExternalCheckoutCommand(UUID moldAssetId, String reasonCode, String reasonNote,
			String counterpartyName, LocalDate expectedReturnDate, String operatorCode) { }

	public record ExternalReturnCommand(String operatorCode) { }

	public record MoldRequestView(UUID id, String requestNo, UUID moldAssetId, String productCode,
			String status, String moldOwnershipType, String moldOwnerName, String requestedBy,
			String engineerCode, String warehouseCode,
			Instant requestedAt, Instant approvedAt, Instant issuedAt, Instant returnedAt,
			UUID orderId, UUID orderLineId, UUID workOrderId, UUID waxTaskId, String issuedToWorkerCode) {
	}

	public record OrderMoldSelectionView(UUID id, UUID orderId, String orderNo, UUID customerId, String customerName,
		UUID orderLineId, int lineNo, String productCode, String productName, String routeType, UUID moldAssetId,
		String moldAssetCode, String moldAssetName, String moldOwnershipType, String moldOwnerName, String selectionStatus,
		String pendingReason, String selectedBy,
		Instant selectedAt, Instant updatedAt) { }

	public record ExternalMovementView(UUID id, String movementNo, UUID moldAssetId, String moldAssetCode,
			String moldAssetName, String locationCode, String reasonCode, String reasonNote, String counterpartyName,
			LocalDate expectedReturnDate, String status, String checkedOutBy, Instant checkedOutAt,
			String returnedBy, Instant returnedAt) { }

	public record LocationSuggestion(String locationCode, int occupiedCount, int capacity) { }

	public record StorageLocationView(String locationCode, String locationName, int capacity, boolean active, int occupiedCount,
		String createdBy, Instant createdAt, Instant updatedAt) { }

	public record CreateStorageLocationCommand(String locationCode, String locationName, int capacity, String operatorCode) { }

	public record UpdateStorageLocationCommand(String locationName, int capacity, boolean active, String operatorCode) { }

	private record MoldCustody(String assetType, String ownershipType, String ownerName, String resourceStatus, String custodyStatus, String lockReason) {
	}

	private record MoldUsage(String orderNo, String productCode, String productName, String productMaterial, String batchNo) { }

	private record WaxTaskContext(UUID orderId, UUID orderLineId, UUID workOrderId, String productCode,
			String engineeringConfirmedBy, String defaultProcessReleasedBy) {
		String processAuthorizedBy() {
			return engineeringConfirmedBy != null ? engineeringConfirmedBy : "DEFAULT_PROCESS:" + defaultProcessReleasedBy;
		}
	}

	private record OrderLineContext(UUID orderLineId, UUID orderId, UUID productId, String routeType, UUID customerId,
		String customerName) { }
}

@RestController
@RequestMapping("/api/factory/mold-requests")
class MoldController {

	private final MoldApplication molds;

	MoldController(MoldApplication molds) {
		this.molds = molds;
	}

	@GetMapping
	List<MoldApplication.MoldRequestView> list() {
		return molds.list();
	}

	@GetMapping("/order-selections")
	List<MoldApplication.OrderMoldSelectionView> orderSelections() {
		return molds.orderSelections();
	}

	@GetMapping("/location-suggestions")
	List<MoldApplication.LocationSuggestion> locationSuggestions() {
		return molds.locationSuggestions();
	}

	@GetMapping("/locations")
	List<MoldApplication.StorageLocationView> locations() {
		return molds.storageLocations();
	}

	@PostMapping("/locations")
	@ResponseStatus(HttpStatus.CREATED)
	MoldApplication.StorageLocationView createLocation(@Valid @RequestBody StorageLocationBody body) {
		return molds.createStorageLocation(new MoldApplication.CreateStorageLocationCommand(
			body.locationCode(), body.locationName(), body.capacity(), body.operatorCode()));
	}

	@PostMapping("/locations/{locationCode}")
	MoldApplication.StorageLocationView updateLocation(@PathVariable String locationCode,
			@Valid @RequestBody UpdateStorageLocationBody body) {
		return molds.updateStorageLocation(locationCode, new MoldApplication.UpdateStorageLocationCommand(
			body.locationName(), body.capacity(), body.active(), body.operatorCode()));
	}

	@GetMapping("/external-movements")
	List<MoldApplication.ExternalMovementView> externalMovements() {
		return molds.externalMovements();
	}

	@GetMapping("/maintenance-overdue")
	List<MoldApplication.ExternalMovementView> maintenanceOverdue() {
		return molds.overdueMaintenance();
	}

	@PostMapping("/order-selections")
	MoldApplication.OrderMoldSelectionView selectForOrderLine(@Valid @RequestBody OrderMoldSelectionBody body) {
		return molds.selectForOrderLine(body.orderId(), body.orderLineId(), body.moldAssetId(), body.selectedBy());
	}

	@PostMapping("/order-mold-plans")
	MoldApplication.OrderMoldSelectionView planForOrderLine(@Valid @RequestBody OrderMoldPlanBody body) {
		return molds.planForOrderLine(new MoldApplication.OrderMoldPlanCommand(body.orderId(), body.orderLineId(),
			body.selectionStatus(), body.pendingReason(), body.selectedBy()));
	}

	@PostMapping("/receipts")
	@ResponseStatus(HttpStatus.CREATED)
	ResourceApplication.AssetView receive(@Valid @RequestBody MoldReceiptBody body) {
		return molds.receive(new MoldApplication.ReceiveCommand(body.scannedValue(), body.assetCode(), body.assetName(),
			body.locationCode(), body.lifeLimit(), body.ownershipType(), body.ownerName(), body.operatorCode(), body.orderLineId()));
	}

	@PostMapping("/external-movements")
	@ResponseStatus(HttpStatus.CREATED)
	MoldApplication.ExternalMovementView checkOutExternally(@Valid @RequestBody ExternalCheckoutBody body) {
		return molds.checkOutExternally(new MoldApplication.ExternalCheckoutCommand(body.moldAssetId(), body.reasonCode(),
			body.reasonNote(), body.counterpartyName(), body.expectedReturnDate(), body.operatorCode()));
	}

	@PostMapping("/external-movements/{id}/return")
	MoldApplication.ExternalMovementView returnFromExternal(@PathVariable UUID id, @Valid @RequestBody ExternalReturnBody body) {
		return molds.returnFromExternal(id, new MoldApplication.ExternalReturnCommand(body.operatorCode()));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	MoldApplication.MoldRequestView request(@Valid @RequestBody MoldRequestBody body) {
		return molds.request(new MoldApplication.RequestCommand(body.moldAssetId(), body.productCode(), body.requestedBy()));
	}

	@PostMapping("/tasks/{taskId}")
	@ResponseStatus(HttpStatus.CREATED)
	MoldApplication.MoldRequestView requestForWaxTask(@PathVariable UUID taskId,
			@Valid @RequestBody TaskMoldRequestBody body) {
		return molds.requestForWaxTask(taskId, new MoldApplication.TaskRequestCommand(
			body.moldAssetId(), body.supervisorCode(), body.intendedWorkerCode()));
	}

	@PostMapping("/{id}/approval")
	MoldApplication.MoldRequestView approve(@PathVariable UUID id, @Valid @RequestBody ApprovalBody body) {
		return molds.approve(id, body.engineerCode());
	}

	@PostMapping("/{id}/issue")
	MoldApplication.MoldRequestView issue(@PathVariable UUID id, @Valid @RequestBody IssueBody body) {
		return molds.issue(id, new MoldApplication.IssueCommand(
			body.warehouseCode(), body.operatorCode(), body.recipientWorkerCode()));
	}

	@PostMapping("/{id}/return")
	MoldApplication.MoldRequestView returnMold(@PathVariable UUID id, @Valid @RequestBody ReturnBody body) {
		return molds.returnMold(id, body.operatorCode());
	}

	record MoldRequestBody(UUID moldAssetId, @NotBlank @Size(max = 64) String productCode,
			@NotBlank @Size(max = 64) String requestedBy) {
	}
	record StorageLocationBody(@NotBlank @Size(max = 64) String locationCode, @NotBlank @Size(max = 160) String locationName,
			@NotNull @jakarta.validation.constraints.Min(1) Integer capacity, @NotBlank @Size(max = 64) String operatorCode) { }
	record UpdateStorageLocationBody(@NotBlank @Size(max = 160) String locationName,
			@NotNull @jakarta.validation.constraints.Min(1) Integer capacity, @NotNull Boolean active,
			@NotBlank @Size(max = 64) String operatorCode) { }
	record OrderMoldSelectionBody(@NotNull UUID orderId, @NotNull UUID orderLineId, @NotNull UUID moldAssetId,
			@NotBlank @Size(max = 64) String selectedBy) { }
	record OrderMoldPlanBody(@NotNull UUID orderId, @NotNull UUID orderLineId, @NotBlank @Size(max = 40) String selectionStatus,
			@Size(max = 500) String pendingReason, @NotBlank @Size(max = 64) String selectedBy) { }
	record MoldReceiptBody(@Size(max = 128) String scannedValue, @Size(max = 64) String assetCode,
			@NotBlank @Size(max = 160) String assetName, @Size(max = 64) String locationCode,
			@jakarta.validation.constraints.Min(1) Integer lifeLimit, @NotBlank @Size(max = 32) String ownershipType,
			@Size(max = 160) String ownerName, @NotBlank @Size(max = 64) String operatorCode, UUID orderLineId) { }
	record ExternalCheckoutBody(@NotNull UUID moldAssetId, @NotBlank @Size(max = 32) String reasonCode,
			@Size(max = 500) String reasonNote, @Size(max = 160) String counterpartyName,
			LocalDate expectedReturnDate, @NotBlank @Size(max = 64) String operatorCode) { }
	record ExternalReturnBody(@NotBlank @Size(max = 64) String operatorCode) { }
	record TaskMoldRequestBody(@NotNull UUID moldAssetId, @NotBlank @Size(max = 64) String supervisorCode,
			@NotBlank @Size(max = 64) String intendedWorkerCode) {
	}
	record ApprovalBody(@NotBlank @Size(max = 64) String engineerCode) {
	}
	record IssueBody(@NotBlank @Size(max = 64) String warehouseCode,
			@NotBlank @Size(max = 64) String operatorCode, @Size(max = 64) String recipientWorkerCode) {
	}
	record ReturnBody(@NotBlank @Size(max = 64) String operatorCode) {
	}
}
