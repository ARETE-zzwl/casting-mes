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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
public class MoldLifecycleApplication {

	private final JdbcTemplate jdbc;

	public MoldLifecycleApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<MoldLifecycleView> list() {
		return jdbc.query(lifecycleSelect() + " where a.asset_type = 'MOLD' order by a.next_maintenance_date nulls last, a.asset_code", MoldLifecycleApplication::mapLifecycle);
	}

	@Transactional(readOnly = true)
	public List<MoldLifecycleView> due() {
		return jdbc.query(lifecycleSelect() + " where a.asset_type = 'MOLD' and (a.next_maintenance_date <= current_date or a.life_limit is not null and a.life_used >= a.life_limit) order by a.next_maintenance_date", MoldLifecycleApplication::mapLifecycle);
	}

	@Transactional(readOnly = true)
	public List<MaintenanceRecordView> history(UUID moldId) {
		requireMold(moldId, false);
		return jdbc.query("select * from mold_maintenance_record where mold_asset_id = ? order by occurred_at desc", MoldLifecycleApplication::mapRecord, moldId);
	}

	@Transactional
	public MoldLifecycleView configure(UUID moldId, ConfigureCommand command) {
		requireMold(moldId, true);
		LocalDate nextDue = command.nextMaintenanceDate();
		if (nextDue == null && command.maintenanceIntervalDays() != null) nextDue = LocalDate.now().plusDays(command.maintenanceIntervalDays());
		jdbc.update("update resource_asset set life_limit = coalesce(?, life_limit), maintenance_interval_days = ?, next_maintenance_date = ?, updated_at = ?, version = version + 1 where id = ?",
			command.lifeLimit(), command.maintenanceIntervalDays(), nextDue, Timestamp.from(Instant.now()), moldId);
		return requireMold(moldId, false);
	}

	@Transactional
	public MaintenanceRecordView record(UUID moldId, RecordCommand command) {
		MoldLifecycleView mold = requireMold(moldId, true);
		String type = normalizeRecordType(command.recordType());
		LocalDate nextDue = command.nextMaintenanceDate();
		if (nextDue == null && mold.maintenanceIntervalDays() != null && ("MAINTENANCE".equals(type) || "REPAIR".equals(type))) {
			nextDue = LocalDate.now().plusDays(mold.maintenanceIntervalDays());
		}
		Instant now = Instant.now();
		UUID id = UUID.randomUUID();
		jdbc.update("insert into mold_maintenance_record (id, mold_asset_id, record_no, record_type, description, service_provider, performed_by, occurred_at, next_maintenance_date) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			id, moldId, "MM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT), type, command.description().trim(),
			blankToNull(command.serviceProvider()), normalize(command.performedBy()), Timestamp.from(now), nextDue);
		if (nextDue != null) jdbc.update("update resource_asset set next_maintenance_date = ?, updated_at = ?, version = version + 1 where id = ?", nextDue, Timestamp.from(now), moldId);
		return jdbc.query("select * from mold_maintenance_record where id = ?", MoldLifecycleApplication::mapRecord, id).getFirst();
	}

	@Transactional
	public MoldLifecycleView lock(UUID moldId, LockCommand command) {
		requireMold(moldId, true);
		jdbc.update("update resource_asset set mold_lock_reason = ?, mold_locked_at = ?, updated_at = ?, version = version + 1 where id = ?",
			command.reason().trim(), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), moldId);
		return requireMold(moldId, false);
	}

	@Transactional
	public MoldLifecycleView unlock(UUID moldId, UnlockCommand command) {
		requireMold(moldId, true);
		if (command.resolutionNote().isBlank()) throw DomainException.badRequest("MOLD_UNLOCK_NOTE_REQUIRED", "解除异常锁定必须填写处理结论");
		Instant now = Instant.now();
		jdbc.update("insert into mold_maintenance_record (id, mold_asset_id, record_no, record_type, description, performed_by, occurred_at) values (?, ?, ?, 'UNLOCK', ?, ?, ?)",
			UUID.randomUUID(), moldId, "MM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT), command.resolutionNote().trim(), normalize(command.operatorCode()), Timestamp.from(now));
		jdbc.update("update resource_asset set mold_lock_reason = null, mold_locked_at = null, updated_at = ?, version = version + 1 where id = ?", Timestamp.from(now), moldId);
		return requireMold(moldId, false);
	}

	private MoldLifecycleView requireMold(UUID id, boolean lock) {
		return jdbc.query(lifecycleSelect() + " where a.asset_type = 'MOLD' and a.id = ?" + (lock ? " for update" : ""), MoldLifecycleApplication::mapLifecycle, id).stream()
			.findFirst().orElseThrow(() -> DomainException.notFound("MOLD_ASSET_NOT_FOUND", "模具资产不存在"));
	}

	private static String lifecycleSelect() {
		return "select a.id, a.asset_code, a.asset_name, a.status, a.location_code, a.life_limit, a.life_used, a.maintenance_interval_days, a.next_maintenance_date, a.mold_lock_reason, a.mold_locked_at from resource_asset a";
	}

	private static MoldLifecycleView mapLifecycle(ResultSet rs, int row) throws SQLException {
		Timestamp lockedAt = rs.getTimestamp("mold_locked_at");
		Integer interval = (Integer) rs.getObject("maintenance_interval_days");
		return new MoldLifecycleView(rs.getObject("id", UUID.class), rs.getString("asset_code"), rs.getString("asset_name"), rs.getString("status"),
			rs.getString("location_code"), (Integer) rs.getObject("life_limit"), rs.getInt("life_used"), interval,
			rs.getDate("next_maintenance_date") == null ? null : rs.getDate("next_maintenance_date").toLocalDate(), rs.getString("mold_lock_reason"), lockedAt == null ? null : lockedAt.toInstant());
	}

	private static MaintenanceRecordView mapRecord(ResultSet rs, int row) throws SQLException {
		return new MaintenanceRecordView(rs.getObject("id", UUID.class), rs.getObject("mold_asset_id", UUID.class), rs.getString("record_no"),
			rs.getString("record_type"), rs.getString("description"), rs.getString("service_provider"), rs.getString("performed_by"),
			rs.getTimestamp("occurred_at").toInstant(), rs.getDate("next_maintenance_date") == null ? null : rs.getDate("next_maintenance_date").toLocalDate());
	}

	private static String normalizeRecordType(String value) {
		String type = value.trim().toUpperCase(Locale.ROOT);
		if (!List.of("REPAIR", "MAINTENANCE", "INSPECTION").contains(type)) throw DomainException.badRequest("MOLD_MAINTENANCE_TYPE_INVALID", "维修记录类型不正确");
		return type;
	}
	private static String normalize(String value) { return value.trim().toUpperCase(Locale.ROOT); }
	private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

	public record ConfigureCommand(Integer lifeLimit, Integer maintenanceIntervalDays, LocalDate nextMaintenanceDate) { }
	public record RecordCommand(String recordType, String description, String serviceProvider, String performedBy, LocalDate nextMaintenanceDate) { }
	public record LockCommand(String reason, String operatorCode) { }
	public record UnlockCommand(String resolutionNote, String operatorCode) { }
	public record MoldLifecycleView(UUID id, String assetCode, String assetName, String status, String locationCode, Integer lifeLimit,
		int lifeUsed, Integer maintenanceIntervalDays, LocalDate nextMaintenanceDate, String lockReason, Instant lockedAt) { }
	public record MaintenanceRecordView(UUID id, UUID moldAssetId, String recordNo, String recordType, String description,
		String serviceProvider, String performedBy, Instant occurredAt, LocalDate nextMaintenanceDate) { }
}

@RestController
@RequestMapping("/api/molds")
class MoldLifecycleController {
	private final MoldLifecycleApplication lifecycle;
	MoldLifecycleController(MoldLifecycleApplication lifecycle) { this.lifecycle = lifecycle; }
	@GetMapping List<MoldLifecycleApplication.MoldLifecycleView> list() { return lifecycle.list(); }
	@GetMapping("/due") List<MoldLifecycleApplication.MoldLifecycleView> due() { return lifecycle.due(); }
	@GetMapping("/{moldId}/maintenance-records") List<MoldLifecycleApplication.MaintenanceRecordView> history(@PathVariable UUID moldId) { return lifecycle.history(moldId); }
	@PostMapping("/{moldId}/configuration") MoldLifecycleApplication.MoldLifecycleView configure(@PathVariable UUID moldId, @Valid @RequestBody ConfigureBody body) { return lifecycle.configure(moldId, new MoldLifecycleApplication.ConfigureCommand(body.lifeLimit(), body.maintenanceIntervalDays(), body.nextMaintenanceDate())); }
	@PostMapping("/{moldId}/maintenance-records") MoldLifecycleApplication.MaintenanceRecordView record(@PathVariable UUID moldId, @Valid @RequestBody RecordBody body) { return lifecycle.record(moldId, new MoldLifecycleApplication.RecordCommand(body.recordType(), body.description(), body.serviceProvider(), body.performedBy(), body.nextMaintenanceDate())); }
	@PostMapping("/{moldId}/lock") MoldLifecycleApplication.MoldLifecycleView lock(@PathVariable UUID moldId, @Valid @RequestBody LockBody body) { return lifecycle.lock(moldId, new MoldLifecycleApplication.LockCommand(body.reason(), body.operatorCode())); }
	@PostMapping("/{moldId}/unlock") MoldLifecycleApplication.MoldLifecycleView unlock(@PathVariable UUID moldId, @Valid @RequestBody UnlockBody body) { return lifecycle.unlock(moldId, new MoldLifecycleApplication.UnlockCommand(body.resolutionNote(), body.operatorCode())); }
	record ConfigureBody(@Min(1) Integer lifeLimit, @Min(1) Integer maintenanceIntervalDays, LocalDate nextMaintenanceDate) { }
	record RecordBody(@NotBlank @Size(max = 24) String recordType, @NotBlank @Size(max = 1000) String description, @Size(max = 160) String serviceProvider, @NotBlank @Size(max = 64) String performedBy, LocalDate nextMaintenanceDate) { }
	record LockBody(@NotBlank @Size(max = 500) String reason, @NotBlank @Size(max = 64) String operatorCode) { }
	record UnlockBody(@NotBlank @Size(max = 1000) String resolutionNote, @NotBlank @Size(max = 64) String operatorCode) { }
}
