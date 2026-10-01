package com.renyi.mes.resource;

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
import com.renyi.mes.common.BusinessAccess;
import com.renyi.mes.common.PageResult;
import com.renyi.mes.common.ProductionTaskPort;
import com.renyi.mes.common.ProductionTaskPort.TaskSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Service
public class ResourceApplication {

	private static final List<String> TYPES = List.of("MOLD", "EQUIPMENT", "TREE", "FURNACE", "CARRIER");
	private static final List<String> OWNERSHIP_TYPES = List.of("COMPANY_OWNED", "CUSTOMER_OWNED");
	private final JdbcTemplate jdbc;
	private final ProductionTaskPort tasks;
	private final BusinessAccess access;

	public ResourceApplication(JdbcTemplate jdbc, ProductionTaskPort tasks, BusinessAccess access) {
		this.jdbc = jdbc;
		this.tasks = tasks;
		this.access = access;
	}

	@Transactional
	public AssetView register(RegisterCommand command) {
		String type = normalize(command.assetType());
		if (!TYPES.contains(type)) {
			throw DomainException.badRequest("RESOURCE_TYPE_INVALID", "资源类型不受支持");
		}
		String ownershipType = command.ownershipType() == null || command.ownershipType().isBlank()
			? "COMPANY_OWNED" : normalize(command.ownershipType());
		if (!OWNERSHIP_TYPES.contains(ownershipType)) {
			throw DomainException.badRequest("RESOURCE_OWNERSHIP_INVALID", "资源权属类型不受支持");
		}
		String ownerName = blankToNull(command.ownerName());
		if ("CUSTOMER_OWNED".equals(ownershipType) && !"MOLD".equals(type)) {
			throw DomainException.badRequest("RESOURCE_OWNERSHIP_INVALID", "客户资产仅支持登记为模具");
		}
		if ("CUSTOMER_OWNED".equals(ownershipType) && ownerName == null) {
			throw DomainException.badRequest("RESOURCE_OWNER_REQUIRED", "客户寄存模具必须填写客户名称");
		}
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		try {
			jdbc.update("""
				insert into resource_asset (
					id, asset_code, asset_name, asset_type, ownership_type, owner_name, status, location_code,
					life_limit, life_used, version, created_at, updated_at
				) values (?, ?, ?, ?, ?, ?, 'AVAILABLE', ?, ?, 0, 0, ?, ?)
				""", id, normalize(command.assetCode()), command.assetName().trim(), type,
				ownershipType, ownerName, blankToNull(command.locationCode()), command.lifeLimit(),
				Timestamp.from(now), Timestamp.from(now));
		}
		catch (DuplicateKeyException exception) {
			throw DomainException.conflict("RESOURCE_CODE_EXISTS", "资源编码已存在");
		}
		return requireAsset(id, false);
	}

	@Transactional
	public AssetView occupy(UUID assetId, OccupyCommand command) {
		AssetView asset = requireAsset(assetId, true);
		if (!asset.status().equals("AVAILABLE")) {
			throw DomainException.conflict("RESOURCE_NOT_AVAILABLE", "资源当前不可占用");
		}
		if (asset.lifeLimit() != null && asset.lifeUsed() >= asset.lifeLimit()) {
			throw DomainException.conflict("RESOURCE_LIFE_EXHAUSTED", "资源寿命已耗尽");
		}
		Instant now = Instant.now();
		try {
			jdbc.update("""
				insert into resource_occupation (
					id, asset_id, active_asset_id, business_key, status, occupied_by, occupied_at, note
				) values (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
				""", UUID.randomUUID(), assetId, assetId, command.businessKey().trim(),
				normalize(command.operatorCode()), Timestamp.from(now), blankToNull(command.note()));
		}
		catch (DuplicateKeyException exception) {
			throw DomainException.conflict("RESOURCE_ALREADY_OCCUPIED", "资源已被其他业务占用");
		}
		jdbc.update("""
			update resource_asset
			set status = 'OCCUPIED', version = version + 1, updated_at = ?
			where id = ?
			""", Timestamp.from(now), assetId);
		return requireAsset(assetId, false);
	}

	@Transactional
	public AssetView release(UUID assetId, ReleaseCommand command) {
		AssetView asset = requireAsset(assetId, true);
		if (!asset.status().equals("OCCUPIED")) {
			throw DomainException.conflict("RESOURCE_NOT_OCCUPIED", "资源当前没有活动占用");
		}
		List<OccupationView> active = jdbc.query("""
			select * from resource_occupation
			where active_asset_id = ? and status = 'ACTIVE'
			for update
			""", ResourceApplication::mapOccupation, assetId);
		if (active.isEmpty()) {
			throw DomainException.conflict("RESOURCE_OCCUPATION_MISSING", "资源占用记录缺失");
		}
		Instant now = Instant.now();
		jdbc.update("""
			update resource_occupation
			set status = 'RELEASED', active_asset_id = null, released_by = ?, released_at = ?
			where id = ?
			""", normalize(command.operatorCode()), Timestamp.from(now), active.getFirst().id());
		int nextLife = asset.lifeUsed() + (command.consumeLife() ? 1 : 0);
		if (asset.lifeLimit() != null && nextLife > asset.lifeLimit()) {
			throw DomainException.conflict("RESOURCE_LIFE_EXCEEDED", "本次释放将超过资源寿命");
		}
		String nextStatus = asset.lifeLimit() != null && nextLife >= asset.lifeLimit() ? "EXHAUSTED" : "AVAILABLE";
		jdbc.update("""
			update resource_asset
			set status = ?, life_used = ?, version = version + 1, updated_at = ?
			where id = ?
			""", nextStatus, nextLife, Timestamp.from(now), assetId);
		return requireAsset(assetId, false);
	}

	@Transactional(readOnly = true)
	public List<AssetView> listAssets() {
		return jdbc.query("select * from resource_asset order by asset_code", ResourceApplication::mapAsset);
	}

	@Transactional(readOnly = true)
	public java.util.Optional<AssetView> findAssetForScan(String scannedValue) {
		String value = scannedValue == null ? "" : scannedValue.trim();
		if (value.regionMatches(true, 0, "MES:ASSET_QR:", 0, "MES:ASSET_QR:".length())) value = value.substring("MES:ASSET_QR:".length()).trim();
		if (value.isBlank()) return java.util.Optional.empty();
		String normalized = value.toUpperCase(Locale.ROOT);
		return jdbc.query("""
			select a.* from resource_asset a left join asset_qr_label q on q.asset_id = a.id
			where upper(a.asset_code) = ? or upper(q.qr_token) = ? or upper(q.label_no) = ?
			order by a.updated_at desc limit 1
			""", ResourceApplication::mapAsset, normalized, normalized, normalized).stream().findFirst();
	}

	@Transactional(readOnly = true)
	public PageResult<AssetView> searchAssets(AssetSearchCommand command) {
		PageRequest page = pageRequest(command.page(), command.size());
		List<Object> parameters = new ArrayList<>();
		String where = "1 = 1";
		if (command.assetType() != null && !command.assetType().isBlank() && !"ALL".equalsIgnoreCase(command.assetType())) {
			where += " and asset_type = ?";
			parameters.add(normalize(command.assetType()));
		}
		if (command.keyword() != null && !command.keyword().isBlank()) {
			String term = searchTerm(command.keyword());
			where += " and (asset_code like ? or upper(asset_name) like ? or upper(coalesce(owner_name, '')) like ? or upper(coalesce(location_code, '')) like ?)";
			parameters.add(term); parameters.add(term); parameters.add(term); parameters.add(term);
		}
		where = appendFilter(where, parameters, "mold_custody_status", command.custodyStatus());
		where = appendFilter(where, parameters, "ownership_type", command.ownershipType());
		where = appendFilter(where, parameters, "status", command.status());
		if (command.locationCode() != null && !command.locationCode().isBlank() && !"ALL".equalsIgnoreCase(command.locationCode())) {
			where += " and location_code = ?";
			parameters.add(normalize(command.locationCode()));
		}
		long total = jdbc.queryForObject("select count(*) from resource_asset where " + where, Long.class, parameters.toArray());
		List<Object> pageParameters = new ArrayList<>(parameters);
		pageParameters.add(page.size()); pageParameters.add(page.offset());
		List<AssetView> items = jdbc.query("select * from resource_asset where %s order by updated_at desc, asset_code asc limit ? offset ?".formatted(where),
			ResourceApplication::mapAsset, pageParameters.toArray());
		return PageResult.of(items, page.page(), page.size(), total);
	}

	private static String searchTerm(String keyword) {
		String normalized = keyword.trim().toUpperCase(Locale.ROOT);
		return normalized.matches("[A-Z0-9_-]+") ? normalized + "%" : "%" + normalized + "%";
	}

	private static String appendFilter(String where, List<Object> parameters, String column, String value) {
		if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) return where;
		parameters.add(normalize(value));
		return where + " and " + column + " = ?";
	}

	@Transactional
	public AssetView updateMoldImage(UUID assetId, String imageUrl) {
		AssetView asset = requireAsset(assetId, true);
		if (!"MOLD".equals(asset.assetType())) throw DomainException.badRequest("RESOURCE_NOT_MOLD", "仅模具可维护模具图");
		jdbc.update("update resource_asset set mold_image_url = ?, updated_at = ? where id = ?", blankToNull(imageUrl), Timestamp.from(Instant.now()), assetId);
		return requireAsset(assetId, false);
	}

	@Transactional(readOnly = true)
	public List<OccupationView> listOccupations() {
		return jdbc.query("select * from resource_occupation order by occupied_at desc",
			ResourceApplication::mapOccupation);
	}

	@Transactional
	public QueueView updateManualRank(UUID taskId, int manualRank) {
		access.requireManageTask(taskId);
		QueueView item = requireQueueItem(taskId, true);
		if (isLocked(item.taskStatus())) {
			throw DomainException.conflict("SCHEDULE_ITEM_LOCKED", "已开工或已完成的任务不能调整排产顺序");
		}
		jdbc.update("""
			update schedule_queue_item set manual_rank = ?, updated_at = ? where task_id = ?
			""", manualRank, Timestamp.from(Instant.now()), taskId);
		return requireQueueItem(taskId, false);
	}

	@Transactional
	public DispatchView dispatch(UUID taskId, DispatchCommand command) {
		access.requireManageTask(taskId);
		QueueView item = requireQueueItem(taskId, true);
		if (isLocked(item.taskStatus())) {
			throw DomainException.conflict("SCHEDULE_ITEM_LOCKED", "已开工或已完成的任务不能再次派工");
		}
		TaskSnapshot task = tasks.assignAndStart(taskId, command.workerCode(), command.supervisorCode());
		AssetView resource = occupy(command.resourceAssetId(), new OccupyCommand(
			taskId.toString(), command.workerCode(), "调度派工占用：" + task.taskNo()));
		return new DispatchView(task, resource);
	}

	@Transactional
	public List<QueueView> listQueue(String lineCode, String operationCode) {
		ensureQueueItems();
		StringBuilder sql = new StringBuilder("""
			select q.id as queue_id, q.task_id, q.line_code, q.manual_rank, q.created_at as queue_created_at,
			       t.task_no, t.operation_code, t.operation_name, t.status as task_status,
			       t.planned_quantity, t.assigned_to, t.started_at,
			       wo.id as work_order_id, o.id as order_id, o.order_no, o.priority,
			       o.requested_delivery_date
			from schedule_queue_item q
			join planning_task t on t.id = q.task_id
			join planning_batch b on b.id = t.batch_id
			join planning_work_order wo on wo.id = b.work_order_id
			join customer_order_header o on o.id = wo.order_id
			where t.status in ('READY', 'ASSIGNED')
			""");
		List<Object> arguments = new ArrayList<>();
		var scope = access.taskScope("t.id");
		sql.append(" and ").append(scope.clause());
		arguments.addAll(scope.parameters());
		if (lineCode != null && !lineCode.isBlank()) {
			sql.append(" and q.line_code = ?");
			arguments.add(normalize(lineCode));
		}
		if (operationCode != null && !operationCode.isBlank()) {
			sql.append(" and t.operation_code = ?");
			arguments.add(normalize(operationCode));
		}
		sql.append("""
			 order by case o.priority when 'SAMPLE' then 3 when 'URGENT' then 2 else 1 end desc,
			          case when o.requested_delivery_date is null then 1 else 0 end,
			          o.requested_delivery_date asc, q.created_at asc, q.manual_rank desc, t.task_no asc
			""");
		return jdbc.query(sql.toString(), ResourceApplication::mapQueue, arguments.toArray());
	}

	private void ensureQueueItems() {
		List<QueueSeed> missing = jdbc.query("""
			select t.id as task_id, wo.route_type
			from planning_task t
			join planning_batch b on b.id = t.batch_id
			join planning_work_order wo on wo.id = b.work_order_id
			left join schedule_queue_item q on q.task_id = t.id
			where q.task_id is null and t.status in ('READY', 'ASSIGNED')
			""", (rs, rowNum) -> new QueueSeed(
			rs.getObject("task_id", UUID.class), rs.getString("route_type")));
		for (QueueSeed seed : missing) {
			Instant now = Instant.now();
			try {
				jdbc.update("""
					insert into schedule_queue_item (id, task_id, line_code, manual_rank, created_at, updated_at)
					values (?, ?, ?, 0, ?, ?)
					""", UUID.randomUUID(), seed.taskId(), lineFor(seed.routeType()),
					Timestamp.from(now), Timestamp.from(now));
			}
			catch (DuplicateKeyException ignored) {
				// Concurrent release or queue refresh has already registered the task.
			}
		}
	}

	private QueueView requireQueueItem(UUID taskId, boolean lock) {
		ensureQueueItems();
		String sql = queueSelect() + " where q.task_id = ?" + (lock ? " for update" : "");
		return jdbc.query(sql, ResourceApplication::mapQueue, taskId).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("SCHEDULE_TASK_NOT_FOUND", "任务尚未进入排产队列"));
	}

	private static String queueSelect() {
		return """
			select q.id as queue_id, q.task_id, q.line_code, q.manual_rank, q.created_at as queue_created_at,
			       t.task_no, t.operation_code, t.operation_name, t.status as task_status,
			       t.planned_quantity, t.assigned_to, t.started_at,
			       wo.id as work_order_id, o.id as order_id, o.order_no, o.priority,
			       o.requested_delivery_date
			from schedule_queue_item q
			join planning_task t on t.id = q.task_id
			join planning_batch b on b.id = t.batch_id
			join planning_work_order wo on wo.id = b.work_order_id
			join customer_order_header o on o.id = wo.order_id
			""";
	}

	private static QueueView mapQueue(ResultSet rs, int rowNum) throws SQLException {
		Timestamp startedAt = rs.getTimestamp("started_at");
		return new QueueView(rs.getObject("queue_id", UUID.class), rs.getObject("task_id", UUID.class),
			rs.getString("line_code"), rs.getString("task_no"), rs.getObject("work_order_id", UUID.class),
			rs.getObject("order_id", UUID.class), rs.getString("order_no"), rs.getString("priority"),
			rs.getDate("requested_delivery_date") == null ? null : rs.getDate("requested_delivery_date").toLocalDate(),
			rs.getString("operation_code"), rs.getString("operation_name"), rs.getString("task_status"),
			rs.getBigDecimal("planned_quantity"), rs.getString("assigned_to"),
			startedAt == null ? null : startedAt.toInstant(), rs.getInt("manual_rank"),
			rs.getTimestamp("queue_created_at").toInstant());
	}

	private static boolean isLocked(String taskStatus) {
		return "IN_PROGRESS".equals(taskStatus) || "COMPLETED".equals(taskStatus);
	}

	private static String lineFor(String routeType) {
		return switch (routeType) {
			case "MID_TEMP_WAX" -> "MID_WAX";
			case "LOW_TEMP_WAX" -> "LOW_WAX";
			case "SAND_OUTSOURCE" -> "SAND_OUTSOURCE";
			default -> throw DomainException.badRequest("ROUTE_TYPE_INVALID", "不支持的生产路线");
		};
	}

	private AssetView requireAsset(UUID id, boolean lock) {
		String sql = "select * from resource_asset where id = ?" + (lock ? " for update" : "");
		return jdbc.query(sql, ResourceApplication::mapAsset, id).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("RESOURCE_NOT_FOUND", "资源不存在"));
	}

	private static AssetView mapAsset(ResultSet rs, int rowNum) throws SQLException {
		Integer lifeLimit = (Integer) rs.getObject("life_limit");
		return new AssetView(rs.getObject("id", UUID.class), rs.getString("asset_code"),
			rs.getString("asset_name"), rs.getString("asset_type"), rs.getString("status"),
			rs.getString("ownership_type"), rs.getString("owner_name"), rs.getString("mold_custody_status"), rs.getString("location_code"), rs.getString("mold_image_url"),
			lifeLimit, rs.getInt("life_used"), rs.getLong("version"),
			rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
	}

	private static OccupationView mapOccupation(ResultSet rs, int rowNum) throws SQLException {
		Timestamp releasedAt = rs.getTimestamp("released_at");
		return new OccupationView(rs.getObject("id", UUID.class), rs.getObject("asset_id", UUID.class),
			rs.getString("business_key"), rs.getString("status"), rs.getString("occupied_by"),
			rs.getTimestamp("occupied_at").toInstant(), rs.getString("released_by"),
			releasedAt == null ? null : releasedAt.toInstant(), rs.getString("note"));
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	public record RegisterCommand(String assetCode, String assetName, String assetType,
			String locationCode, Integer lifeLimit, String ownershipType, String ownerName) {
	}

	public record AssetSearchCommand(String assetType, String keyword, String custodyStatus, String ownershipType,
		String status, String locationCode, int page, int size) { }

	private static PageRequest pageRequest(int page, int size) {
		if (page < 0 || size < 1 || size > 100) throw DomainException.badRequest("INVALID_PAGE_REQUEST", "页码必须从 0 开始，单页数量为 1 至 100");
		return new PageRequest(page, size);
	}

	private record PageRequest(int page, int size) {
		private long offset() { return (long) page * size; }
	}

	public record OccupyCommand(String businessKey, String operatorCode, String note) {
	}

	public record ReleaseCommand(String operatorCode, boolean consumeLife) {
	}

	public record AssetView(UUID id, String assetCode, String assetName, String assetType,
			String status, String ownershipType, String ownerName, String moldCustodyStatus, String locationCode, String moldImageUrl,
			Integer lifeLimit, int lifeUsed, long version,
			Instant createdAt, Instant updatedAt) {
	}

	public record OccupationView(UUID id, UUID assetId, String businessKey, String status,
			String occupiedBy, Instant occupiedAt, String releasedBy, Instant releasedAt, String note) {
	}

	public record QueueView(UUID id, UUID taskId, String lineCode, String taskNo, UUID workOrderId,
			UUID orderId, String orderNo, String priority, LocalDate requestedDeliveryDate,
			String operationCode, String operationName, String taskStatus,
			java.math.BigDecimal plannedQuantity, String assignedTo, Instant startedAt, int manualRank,
			Instant createdAt) {
	}

	public record DispatchCommand(String workerCode, UUID resourceAssetId, String supervisorCode) {
	}

	public record DispatchView(TaskSnapshot task, AssetView resource) {
	}

	private record QueueSeed(UUID taskId, String routeType) {
	}
}

@RestController
@RequestMapping("/api/resources")
class ResourceController {

	private final ResourceApplication resources;

	ResourceController(ResourceApplication resources) {
		this.resources = resources;
	}

	@GetMapping
	List<ResourceApplication.AssetView> assets() {
		return resources.listAssets();
	}

	@GetMapping("/query")
	PageResult<ResourceApplication.AssetView> searchAssets(@RequestParam(required = false) String assetType,
			@RequestParam(required = false) String keyword, @RequestParam(required = false) String custodyStatus,
			@RequestParam(required = false) String ownershipType, @RequestParam(required = false) String status,
			@RequestParam(required = false) String locationCode, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return resources.searchAssets(new ResourceApplication.AssetSearchCommand(assetType, keyword, custodyStatus,
			ownershipType, status, locationCode, page, size));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ResourceApplication.AssetView register(@Valid @RequestBody RegisterRequest request) {
		return resources.register(new ResourceApplication.RegisterCommand(
			request.assetCode(), request.assetName(), request.assetType(),
			request.locationCode(), request.lifeLimit(), request.ownershipType(), request.ownerName()));
	}

	@GetMapping("/occupations")
	List<ResourceApplication.OccupationView> occupations() {
		return resources.listOccupations();
	}

	@PostMapping("/{assetId}/occupations")
	ResourceApplication.AssetView occupy(@PathVariable UUID assetId,
			@Valid @RequestBody OccupyRequest request) {
		return resources.occupy(assetId, new ResourceApplication.OccupyCommand(
			request.businessKey(), request.operatorCode(), request.note()));
	}

	@PostMapping("/{assetId}/release")
	ResourceApplication.AssetView release(@PathVariable UUID assetId,
			@Valid @RequestBody ReleaseRequest request) {
		return resources.release(assetId,
			new ResourceApplication.ReleaseCommand(request.operatorCode(), request.consumeLife()));
	}

	@PostMapping("/{assetId}/mold-image")
	ResourceApplication.AssetView updateMoldImage(@PathVariable UUID assetId, @Valid @RequestBody MoldImageRequest request) {
		return resources.updateMoldImage(assetId, request.moldImageUrl());
	}

	record RegisterRequest(@NotBlank @Size(max = 64) String assetCode,
			@NotBlank @Size(max = 160) String assetName, @NotBlank String assetType,
			@Size(max = 64) String locationCode, @Min(1) Integer lifeLimit,
			@Size(max = 32) String ownershipType, @Size(max = 160) String ownerName) {
	}

	record OccupyRequest(@NotBlank @Size(max = 128) String businessKey,
			@NotBlank @Size(max = 64) String operatorCode, @Size(max = 500) String note) {
	}

	record ReleaseRequest(@NotBlank @Size(max = 64) String operatorCode,
			@NotNull Boolean consumeLife) {
	}

	record MoldImageRequest(@Size(max = 500) String moldImageUrl) { }

}

@RestController
@RequestMapping("/api/scheduling")
class SchedulingController {

	private final ResourceApplication resources;

	SchedulingController(ResourceApplication resources) {
		this.resources = resources;
	}

	@GetMapping("/queue")
	List<ResourceApplication.QueueView> queue(@RequestParam(required = false) String lineCode,
			@RequestParam(required = false) String operationCode) {
		return resources.listQueue(lineCode, operationCode);
	}

	@PostMapping("/queue/{taskId}/rank")
	ResourceApplication.QueueView updateRank(@PathVariable UUID taskId,
			@Valid @RequestBody RankRequest request) {
		return resources.updateManualRank(taskId, request.manualRank());
	}

	@PostMapping("/queue/{taskId}/dispatch")
	ResourceApplication.DispatchView dispatch(@PathVariable UUID taskId,
			@Valid @RequestBody DispatchRequest request) {
		return resources.dispatch(taskId,
			new ResourceApplication.DispatchCommand(request.workerCode(), request.resourceAssetId(), request.supervisorCode()));
	}

	record RankRequest(int manualRank) {
	}

	record DispatchRequest(@NotBlank @Size(max = 64) String workerCode,
			@NotNull UUID resourceAssetId,
			@NotBlank @Size(max = 64) String supervisorCode) {
	}
}
