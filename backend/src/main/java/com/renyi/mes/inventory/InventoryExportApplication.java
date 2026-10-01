package com.renyi.mes.inventory;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.WarehouseAccessApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
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
public class InventoryExportApplication {

    private final JdbcTemplate jdbc;
    private final WarehouseAccessApplication warehouseAccess;
    private final InventoryExportWorker worker;

    public InventoryExportApplication(JdbcTemplate jdbc, WarehouseAccessApplication warehouseAccess, InventoryExportWorker worker) {
        this.jdbc = jdbc;
        this.warehouseAccess = warehouseAccess;
        this.worker = worker;
    }

    public ExportJobView create(ExportCommand command) {
        requireRawMaterialView(command.viewerCode());
        if (command.fromDate() != null && command.toDate() != null && command.fromDate().isAfter(command.toDate())) {
            throw DomainException.badRequest("INVALID_MOVEMENT_DATE_RANGE", "开始日期不能晚于结束日期");
        }
        ExportJobView existing = replay(command);
        if (existing != null) return existing;
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String jobNo = "INV-EXP-" + id.toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        try {
            jdbc.update("""
            insert into inventory_export_job (
                id, operation_id, job_no, requested_by, warehouse_code, keyword, movement_type,
                operator_code, from_date, to_date, status, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            """, id, command.operationId(), jobNo, normalize(command.viewerCode()), blankToNull(command.warehouseCode()),
            blankToNull(command.keyword()), blankToNull(command.movementType()), blankToNull(command.operatorCode()),
            command.fromDate(), command.toDate(), Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            existing = replay(command);
            if (existing != null) return existing;
            throw exception;
        }
        ExportJobView accepted = get(id, command.viewerCode());
        try {
            worker.generate(id);
        } catch (TaskRejectedException exception) {
            jdbc.update("update inventory_export_job set status = 'FAILED', error_message = ?, completed_at = ? where id = ? and status = 'PENDING'",
                "导出队列繁忙，请稍后新建导出任务", Timestamp.from(Instant.now()), id);
            return get(id, command.viewerCode());
        }
        return accepted;
    }

    private ExportJobView replay(ExportCommand command) {
        var matches = jdbc.query("select * from inventory_export_job where operation_id = ?", (rs, rowNum) -> {
            ExportCommand original = new ExportCommand(command.operationId(), rs.getString("requested_by"),
                rs.getString("warehouse_code"), rs.getString("keyword"), rs.getString("movement_type"),
                rs.getString("operator_code"), rs.getObject("from_date", LocalDate.class), rs.getObject("to_date", LocalDate.class));
            if (!canonical(original).equals(canonical(command))) {
                throw DomainException.conflict("EXPORT_OPERATION_CONFLICT", "本次请求编号已用于其他导出条件，请重新提交");
            }
            return rs.getObject("id", UUID.class);
        }, command.operationId());
        return matches.isEmpty() ? null : get(matches.getFirst(), command.viewerCode());
    }

    private static ExportCommand canonical(ExportCommand command) {
        return new ExportCommand(command.operationId(), normalize(command.viewerCode()),
            blankToNull(normalize(command.warehouseCode())), blankToNull(command.keyword()),
            blankToNull(normalize(command.movementType())), blankToNull(normalize(command.operatorCode())),
            command.fromDate(), command.toDate());
    }

    @Transactional(readOnly = true)
    public ExportJobView get(UUID id, String viewerCode) {
        requireRawMaterialView(viewerCode);
        return jdbc.query("select * from inventory_export_job where id = ?", (rs, rowNum) -> new ExportJobView(
            rs.getObject("id", UUID.class), rs.getString("job_no"), rs.getString("status"),
            rs.getString("file_name"), rs.getString("requested_by"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(), rs.getString("error_message")
        ), id).stream().findFirst().orElseThrow(() -> DomainException.notFound("EXPORT_NOT_FOUND", "导出任务不存在"));
    }

    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> download(UUID id, String viewerCode) {
        requireRawMaterialView(viewerCode);
        ExportFile file = jdbc.query("select status, file_name, file_content from inventory_export_job where id = ?", (rs, rowNum) -> new ExportFile(
            rs.getString("status"), rs.getString("file_name"), rs.getString("file_content")), id)
            .stream().findFirst().orElseThrow(() -> DomainException.notFound("EXPORT_NOT_FOUND", "导出任务不存在"));
        if (!"COMPLETED".equals(file.status())) throw DomainException.conflict("INVENTORY_EXPORT_NOT_READY", "导出文件尚未生成完成，请稍后重试");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(file.content().getBytes(StandardCharsets.UTF_8));
    }

    private void requireRawMaterialView(String viewerCode) {
        warehouseAccess.requireInventoryView(viewerCode);
        if (!warehouseAccess.canViewRawMaterials(viewerCode)) {
            throw DomainException.forbidden("RAW_MATERIAL_SCOPE_FORBIDDEN", "当前账号没有原材料仓库导出权限");
        }
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String blankToNull(String value) { return value == null || value.isBlank() || "ALL".equalsIgnoreCase(value) ? null : value.trim(); }

    public record ExportCommand(@NotNull UUID operationId, @NotBlank @Size(max = 64) String viewerCode,
            @Size(max = 64) String warehouseCode, @Size(max = 160) String keyword,
            @Size(max = 32) String movementType, @Size(max = 64) String operatorCode, LocalDate fromDate, LocalDate toDate) { }
    public record ExportJobView(UUID id, String jobNo, String status, String fileName, String requestedBy,
            Instant createdAt, Instant completedAt, String errorMessage) { }
    private record ExportFile(String status, String fileName, String content) { }
}

@Service
class InventoryExportWorker {

    private static final Logger log = LoggerFactory.getLogger(InventoryExportWorker.class);
    private final JdbcTemplate jdbc;
    private final int maxRows;

    InventoryExportWorker(JdbcTemplate jdbc, @Value("${mes.inventory.export-max-rows:50000}") int maxRows) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(120);
        if (maxRows < 1 || maxRows > 100000) throw new IllegalArgumentException("Export row limit must be between 1 and 100000");
        this.maxRows = maxRows;
    }

    @Async
    public void generate(UUID jobId) {
        UUID token = UUID.randomUUID();
        Instant now = Instant.now();
        int claimed = jdbc.update("""
            update inventory_export_job set status = 'RUNNING', execution_token = ?, lease_until = ?, attempts = attempts + 1
            where id = ? and attempts < 3
              and (status = 'PENDING' or (status = 'RUNNING' and (lease_until is null or lease_until < ?)))
            """, token, Timestamp.from(now.plusSeconds(600)), jobId, Timestamp.from(now));
        if (claimed == 0) return;
        try {
            ExportCriteria criteria = jdbc.queryForObject("select warehouse_code, keyword, movement_type, operator_code, from_date, to_date from inventory_export_job where id = ?", (rs, rowNum) -> new ExportCriteria(
                rs.getString("warehouse_code"), rs.getString("keyword"), rs.getString("movement_type"), rs.getString("operator_code"),
                rs.getObject("from_date", LocalDate.class), rs.getObject("to_date", LocalDate.class)), jobId);
            List<Object> parameters = new ArrayList<>();
            String where = "upper(b.warehouse_code) not like 'MOLD%' and upper(b.warehouse_code) not like 'FG%'";
            if (criteria.warehouseCode() != null) { where += " and b.warehouse_code = ?"; parameters.add(normalize(criteria.warehouseCode())); }
            if (criteria.keyword() != null && !criteria.keyword().isBlank()) {
                String term = searchTerm(criteria.keyword());
                where += " and (m.movement_no like ? or b.item_code like ? or upper(b.item_name) like ? or upper(coalesce(m.reference_no, '')) like ? or upper(coalesce(m.operator_code, '')) like ? or upper(coalesce(m.remark, '')) like ?)";
                for (int i = 0; i < 6; i++) parameters.add(term);
            }
            if (criteria.movementType() != null) { where += " and m.movement_type = ?"; parameters.add(normalize(criteria.movementType())); }
            if (criteria.operatorCode() != null) { where += " and m.operator_code = ?"; parameters.add(normalize(criteria.operatorCode())); }
            if (criteria.fromDate() != null) { where += " and m.occurred_at >= ?"; parameters.add(Timestamp.valueOf(criteria.fromDate().atStartOfDay())); }
            if (criteria.toDate() != null) { where += " and m.occurred_at < ?"; parameters.add(Timestamp.valueOf(criteria.toDate().plusDays(1).atStartOfDay())); }
            parameters.add(maxRows + 1);
            List<String[]> rows = jdbc.query("""
                select m.movement_no, b.warehouse_code, b.item_code, b.item_name, b.unit, m.movement_type,
                       m.quantity, m.balance_after, m.reference_type, m.reference_no, m.operator_code,
                       m.remark, m.occurred_at
                from inventory_movement m join inventory_balance b on b.id = m.balance_id
                where %s order by m.occurred_at desc, m.movement_no desc limit ?
                """.formatted(where), (rs, rowNum) -> new String[] {
                    rs.getString("movement_no"), rs.getString("warehouse_code"), rs.getString("item_code"),
                    rs.getString("item_name"), rs.getString("unit"), rs.getString("movement_type"),
                    rs.getBigDecimal("quantity").toPlainString(), rs.getBigDecimal("balance_after").toPlainString(),
                    rs.getString("reference_type"), rs.getString("reference_no"), rs.getString("operator_code"),
                    rs.getString("remark"), rs.getTimestamp("occurred_at").toInstant().toString()
                }, parameters.toArray());
            if (rows.size() > maxRows) {
                jdbc.update("update inventory_export_job set status = 'FAILED', error_message = ?, completed_at = ?, lease_until = null where id = ? and execution_token = ?",
                    "结果超过导出上限 " + maxRows + " 条，请缩小日期或仓库范围", Timestamp.from(Instant.now()), jobId, token);
                return;
            }
            StringBuilder csv = new StringBuilder("流水号,仓库,物料编码,物料名称,单位,业务类型,本次数量,结存,来源类型,来源单号,操作人,备注,发生时间\n");
            for (String[] row : rows) { for (int i = 0; i < row.length; i++) { if (i > 0) csv.append(','); csv.append(csv(row[i])); } csv.append('\n'); }
            jdbc.update("update inventory_export_job set status = 'COMPLETED', error_message = null, file_name = ?, file_content = ?, completed_at = ?, lease_until = null where id = ? and execution_token = ?",
                "inventory-movements-" + jobId.toString().substring(0, 8) + ".csv", "\uFEFF" + csv, Timestamp.from(Instant.now()), jobId, token);
        } catch (Exception exception) {
            log.error("Inventory export failed job={} type={}", jobId, exception.getClass().getName());
            jdbc.update("update inventory_export_job set status = 'FAILED', error_message = ?, completed_at = ?, lease_until = null where id = ? and execution_token = ?",
                "导出生成失败，请联系管理员并提供任务编号 " + jobId, Timestamp.from(Instant.now()), jobId, token);
        }
    }

    static String csv(String value) {
        if (value == null) return "";
        String leading = value.stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0
                || value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")) {
            value = "'" + value;
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String searchTerm(String keyword) { String normalized = keyword.trim().toUpperCase(Locale.ROOT); return normalized.matches("[A-Z0-9_-]+") ? normalized + "%" : "%" + normalized + "%"; }
    private record ExportCriteria(String warehouseCode, String keyword, String movementType, String operatorCode, LocalDate fromDate, LocalDate toDate) { }
}

@Service
class InventoryExportRecovery {
    private final JdbcTemplate jdbc;
    private final InventoryExportWorker worker;

    InventoryExportRecovery(JdbcTemplate jdbc, InventoryExportWorker worker) {
        this.jdbc = jdbc;
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${mes.inventory.export-recovery-delay-ms:30000}", initialDelay = 30000)
    public void recover() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            update inventory_export_job set status = 'FAILED', error_message = ?, completed_at = ?, lease_until = null
            where attempts >= 3 and (status = 'PENDING' or (status = 'RUNNING' and (lease_until is null or lease_until < ?)))
            """, "任务多次中断，请联系管理员检查后重新提交", now, now);
        var jobs = jdbc.query("""
            select id from inventory_export_job where attempts < 3
              and (status = 'PENDING' or (status = 'RUNNING' and (lease_until is null or lease_until < ?)))
            order by created_at limit 10
            """, (rs, row) -> rs.getObject(1, UUID.class), now);
        for (UUID job : jobs) {
            try { worker.generate(job); }
            catch (TaskRejectedException exception) { break; }
        }
    }
}

@RestController
@RequestMapping("/api/inventory/exports")
class InventoryExportController {
    private final InventoryExportApplication exports;
    InventoryExportController(InventoryExportApplication exports) { this.exports = exports; }

    @PostMapping
    ResponseEntity<InventoryExportApplication.ExportJobView> create(@Valid @RequestBody InventoryExportApplication.ExportCommand command) {
        return ResponseEntity.accepted().body(exports.create(command));
    }

    @GetMapping("/{id}")
    InventoryExportApplication.ExportJobView get(@PathVariable UUID id, @RequestParam String viewerCode) { return exports.get(id, viewerCode); }

    @GetMapping("/{id}/download")
    ResponseEntity<byte[]> download(@PathVariable UUID id, @RequestParam String viewerCode) { return exports.download(id, viewerCode); }
}
