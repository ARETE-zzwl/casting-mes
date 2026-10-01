package com.renyi.mes.execution;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.BusinessAccess;
import com.renyi.mes.planning.PlanningApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FurnaceBatchApplication {
	private final JdbcTemplate jdbc;
	private final PlanningApplication planning;
	private final BusinessAccess access;
	public FurnaceBatchApplication(JdbcTemplate jdbc, PlanningApplication planning, BusinessAccess access) { this.jdbc = jdbc; this.planning = planning; this.access = access; }

	@Transactional(readOnly = true)
	public List<FurnaceBatchView> list() { return jdbc.query(select() + " order by created_at desc", FurnaceBatchApplication::map).stream().filter(batch -> access.canReadFurnace(batch.id())).toList(); }

	@Transactional
	public FurnaceBatchView create(CreateCommand command) {
		String operation = normalizeOperation(command.operationCode());
		if (command.taskIds() == null || command.taskIds().isEmpty()) {
			throw DomainException.badRequest("FURNACE_BATCH_TASKS_REQUIRED", "炉次至少需要关联一条任务");
		}
		for (UUID taskId : command.taskIds()) {
			access.requireManageTask(taskId);
			PlanningApplication.TaskView task = planning.getTask(taskId);
			if (!operation.equals(task.operationCode())) {
				throw DomainException.conflict("FURNACE_BATCH_OPERATION_MISMATCH", "炉次只能关联相同工序的任务");
			}
		}
		if (command.furnaceAssetId() != null) {
			Integer furnaces = jdbc.queryForObject("select count(*) from resource_asset where id = ? and asset_type = 'FURNACE'", Integer.class, command.furnaceAssetId());
			if (furnaces == null || furnaces == 0) throw DomainException.notFound("FURNACE_ASSET_NOT_FOUND", "炉/釜资产不存在");
		}
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			insert into furnace_batch (id, furnace_batch_no, operation_code, furnace_asset_id, material_batch, charge_quantity,
				target_temperature, actual_temperature, pressure_mpa, status, note, created_by, created_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)
			""", id, identifier(operation), operation, command.furnaceAssetId(), blankToNull(command.materialBatch()), command.chargeQuantity(),
			command.targetTemperature(), command.actualTemperature(), command.pressureMpa(), blankToNull(command.note()), normalize(command.createdBy()), Timestamp.from(now));
		for (UUID taskId : command.taskIds().stream().distinct().toList()) {
			jdbc.update("insert into furnace_batch_task (furnace_batch_id, task_id) values (?, ?)", id, taskId);
		}
		return require(id);
	}

	@Transactional
	public FurnaceBatchView complete(UUID id, CompleteCommand command) {
		access.requireFurnace(id);
		int changed = jdbc.update("""
			update furnace_batch set status = 'COMPLETED', actual_temperature = coalesce(?, actual_temperature),
			pressure_mpa = coalesce(?, pressure_mpa), note = coalesce(?, note), completed_by = ?, completed_at = ?
			where id = ? and status = 'OPEN'
			""", command.actualTemperature(), command.pressureMpa(), blankToNull(command.note()), normalize(command.completedBy()), Timestamp.from(Instant.now()), id);
		if (changed == 0) throw DomainException.conflict("FURNACE_BATCH_STATE_CONFLICT", "炉次不存在或已完成");
		return require(id);
	}

	private FurnaceBatchView require(UUID id) {
		return jdbc.query(select() + " where id = ?", FurnaceBatchApplication::map, id).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("FURNACE_BATCH_NOT_FOUND", "炉次不存在"));
	}

	private static String select() {
		return """
			select * from (
			select f.*, a.asset_code as furnace_code, a.asset_name as furnace_name,
				string_agg(t.task_no || ' / ' || o.order_no, ', ' order by t.task_no) as task_refs
			from furnace_batch f
			left join resource_asset a on a.id = f.furnace_asset_id
			join furnace_batch_task ft on ft.furnace_batch_id = f.id
			join planning_task t on t.id = ft.task_id
			join planning_batch b on b.id = t.batch_id
			join planning_work_order w on w.id = b.work_order_id
			join customer_order_header o on o.id = w.order_id
			group by f.id, a.asset_code, a.asset_name
			) furnace_rows
			""";
	}

	private static FurnaceBatchView map(ResultSet rs, int rowNum) throws SQLException {
		Timestamp completedAt = rs.getTimestamp("completed_at");
		return new FurnaceBatchView(rs.getObject("id", UUID.class), rs.getString("furnace_batch_no"), rs.getString("operation_code"),
			rs.getObject("furnace_asset_id", UUID.class), rs.getString("furnace_code"), rs.getString("furnace_name"), rs.getString("material_batch"),
			rs.getBigDecimal("charge_quantity"), rs.getBigDecimal("target_temperature"), rs.getBigDecimal("actual_temperature"),
			rs.getBigDecimal("pressure_mpa"), rs.getString("status"), rs.getString("note"), rs.getString("created_by"),
			rs.getTimestamp("created_at").toInstant(), rs.getString("completed_by"), completedAt == null ? null : completedAt.toInstant(), rs.getString("task_refs"));
	}

	private static String normalizeOperation(String value) {
		String operation = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		if (!operation.equals("DEWAX") && !operation.equals("POURING")) throw DomainException.badRequest("FURNACE_BATCH_OPERATION_INVALID", "炉次仅支持脱蜡或浇筑");
		return operation;
	}
	private static String normalize(String value) { if (value == null || value.isBlank()) throw DomainException.badRequest("FURNACE_BATCH_OPERATOR_REQUIRED", "操作人不能为空"); return value.trim().toUpperCase(Locale.ROOT); }
	private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
	private static String identifier(String operation) { return (operation.equals("DEWAX") ? "DW" : "PO") + "-FB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT); }

	public record CreateCommand(String operationCode, UUID furnaceAssetId, String materialBatch, BigDecimal chargeQuantity,
			BigDecimal targetTemperature, BigDecimal actualTemperature, BigDecimal pressureMpa, List<UUID> taskIds, String note, String createdBy) { }
	public record CompleteCommand(BigDecimal actualTemperature, BigDecimal pressureMpa, String note, String completedBy) { }
	public record FurnaceBatchView(UUID id, String furnaceBatchNo, String operationCode, UUID furnaceAssetId, String furnaceCode,
			String furnaceName, String materialBatch, BigDecimal chargeQuantity, BigDecimal targetTemperature, BigDecimal actualTemperature,
			BigDecimal pressureMpa, String status, String note, String createdBy, Instant createdAt, String completedBy, Instant completedAt, String taskReferences) { }
}
