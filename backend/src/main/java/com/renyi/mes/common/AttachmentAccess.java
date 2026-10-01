package com.renyi.mes.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Service
public class AttachmentAccess {
    private final JdbcTemplate jdbc;
    private final TrustedIdentity identity;
    private final BusinessAccess business;
    private final ObjectMapper json;

    public AttachmentAccess(JdbcTemplate jdbc, TrustedIdentity identity, BusinessAccess business, ObjectMapper json) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.business = business;
        this.json = json;
    }

    public void register(String path, String contentType) {
        jdbc.update("insert into stored_attachment(file_path, owner_code, content_type, created_at) values (?, ?, ?, ?)",
            path, identity.employeeCode(), contentType, Timestamp.from(Instant.now()));
    }

    public void requireUpload(String category) {
        if (!business.secured()) return;
        switch (category) {
            case "ORDER_CONTRACT" -> { if (!business.commercial()) throw BusinessAccess.forbidden(); }
            case "ORDER_DRAWING" -> business.requirePermission("ORDER_MANAGE");
            case "PRODUCT_MODEL" -> business.requirePermission("MASTERDATA_MANAGE");
            case "MOLD_IMAGE" -> business.requirePermission("MOLD_RECEIVE", "MOLD_WAREHOUSE_MANAGE");
            case "PROCESS_CARD_IMAGE" -> business.requirePermission("PROCESS_CARD_TEMPLATE_MANAGE");
            case "EXECUTION_PHOTO" -> business.requirePermission("TASK_EXECUTE", "TASK_DISPATCH", "MANUAL_REPORT_ENTRY");
            default -> throw BusinessAccess.forbidden();
        }
    }

    public String requireRead(String path) {
        String actor = identity.employeeCode();
        // Legacy files without metadata are not made public by guessing their URL.
        var owned = jdbc.query("""
            select content_type from stored_attachment a where file_path = ?
              and (owner_code = ? or exists (select 1 from attachment_reader r
                    where r.file_path = a.file_path and r.employee_code = ?))
            """, (rs, row) -> rs.getString(1), path, actor, actor).stream().findFirst();
        if (owned.isPresent()) return owned.get();
        var metadata = jdbc.query("select content_type from stored_attachment where file_path = ?", (rs, row) -> rs.getString(1), path);
        if (!metadata.isEmpty() && businessRead(path)) return metadata.getFirst();
        throw DomainException.notFound("ATTACHMENT_NOT_FOUND", "附件不存在或无权访问");
    }

    private boolean businessRead(String path) {
        if (path.startsWith("/uploads/order_contract/")) {
            return business.commercial() && business.exists("select count(*) from customer_order_header where contract_attachment_url = ?", path);
        }
        if (path.startsWith("/uploads/order_drawing/")) {
            return ids("select id from customer_order_header where order_drawing_url = ?", path).stream()
                .anyMatch(id -> business.canReadOrder(id) || business.assignedOrder(id));
        }
        if (path.startsWith("/uploads/product_model/")) {
            if (business.permission("MASTERDATA_MANAGE", "ORDER_MANAGE", "TASK_DISPATCH")
                    && business.exists("select count(*) from engineering_product where model_image_url = ?", path)) return true;
            return ids("""
                select t.id from planning_task t join planning_batch b on b.id = t.batch_id
                join planning_work_order w on w.id = b.work_order_id join customer_order_line l on l.id = w.order_line_id
                join engineering_product p on p.id = w.product_id where p.model_image_url = ? or l.model_image_url = ?
                """, path, path).stream().anyMatch(business::canReadTask);
        }
        if (path.startsWith("/uploads/mold_image/")) {
            return business.permission("MOLD_WAREHOUSE_MANAGE", "MOLD_RECEIVE", "ORDER_MOLD_SELECT", "TASK_DISPATCH")
                && business.exists("select count(*) from resource_asset where mold_image_url = ?", path);
        }
        if (path.startsWith("/uploads/process_card_image/") || path.startsWith("/uploads/execution_photo/")) {
            if (business.role("PROCESS_ENGINEER", "SYSTEM_ADMIN", "GENERAL_MANAGER")) {
                for (String value : jdbc.queryForList("select operation_parameters from product_process_card_template where operation_parameters like ?", String.class, "%" + path + "%")) {
                    if (contains(parse(value), path)) return true;
                }
            }
            var cards = jdbc.query("""
                select t.id, t.operation_code, w.engineering_operation_parameters from planning_task t
                join planning_batch b on b.id = t.batch_id join planning_work_order w on w.id = b.work_order_id
                where w.engineering_operation_parameters like ?
                """, (rs, row) -> new Card(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), "%" + path + "%");
            for (Card card : cards) {
                JsonNode parameters = parse(card.parameters());
                if (parameters != null && business.canReadTask(card.taskId())
                        && (contains(parameters.get(card.operation()), path)
                            || contains(parameters.path("_operationImages").path(card.operation()), path))) return true;
            }
            for (UUID order : ids("select order_id from customer_order_line where engineering_operation_parameters like ?", "%" + path + "%")) {
                if (business.canReadOrder(order)) {
                    for (String parameters : jdbc.queryForList("select engineering_operation_parameters from customer_order_line where order_id = ?", String.class, order)) {
                        if (contains(parse(parameters), path)) return true;
                    }
                }
            }
        }
        if (path.startsWith("/uploads/execution_photo/")) {
            for (UUID task : ids("""
                    select task_id from execution_report where photo_url = ?
                    union select task_id from shell_building_record where photo_url = ?
                    union select task_id from production_handoff_event where photo_url = ?
                    union select source_task_id from partial_flow_release where handoff_photo_url = ?
                    union select target_task_id from production_cart_transfer where load_photo_url = ? or receive_photo_url = ?
                    union select task_id from manual_report_sheet where paper_image_url = ?
                    """, path, path, path, path, path, path, path)) {
                if (business.canReadTask(task)) return true;
                if (ids("""
                    select next.id from planning_task source join planning_task next
                      on next.batch_id = source.batch_id and next.sequence_no = source.sequence_no + 1 where source.id = ?
                    union select next.id from partial_flow_release f join planning_task next on next.batch_id = f.target_batch_id
                      where f.source_task_id = ? and next.sequence_no = 1
                    """, task, task).stream().anyMatch(business::canReadTask)) return true;
            }
        }
        return false;
    }

    // Validate references before the business write. Knowing a private URL is not authority to attach it.
    public void validateReferences(Object body) {
        validate(json.valueToTree(body), null);
    }
    private void validate(JsonNode node, String category) {
        if (node == null) return;
        if (node.isObject()) {
            for (var field : node.properties()) {
                String expected = switch (field.getKey()) {
                    case "orderDrawingUrl" -> "order_drawing";
                    case "contractAttachmentUrl" -> "order_contract";
                    case "modelImageUrl" -> "product_model";
                    case "moldImageUrl" -> "mold_image";
                    case "photoUrl", "handoffPhotoUrl", "loadPhotoUrl", "receivePhotoUrl", "paperImageUrl" -> "execution_photo";
                    case "engineeringOperationParameters", "operationParameters" -> "process";
                    default -> category;
                };
                validate(field.getValue(), expected);
            }
        } else if (node.isArray()) node.forEach(child -> validate(child, category));
        else if (category != null && node.isTextual()) {
            String value = node.asText();
            if (category.equals("process") && value.stripLeading().startsWith("{")) { validate(parse(value), category); return; }
            if (!value.startsWith("/uploads/")) return;
            boolean allowed = category.equals("process")
                ? value.startsWith("/uploads/process_card_image/") || value.startsWith("/uploads/execution_photo/")
                : value.startsWith("/uploads/" + category + "/");
            if (!allowed) throw DomainException.badRequest("ATTACHMENT_CATEGORY_MISMATCH", "附件类型与业务字段不一致");
            requireRead(value);
        }
    }
    private JsonNode parse(String value) {
        if (value == null || value.isBlank()) return null;
        try { return json.readTree(value); } catch (RuntimeException ignored) { return null; }
    }
    private boolean contains(JsonNode node, String path) {
        if (node == null) return false;
        if (node.isTextual()) return node.asText().equals(path);
        for (JsonNode child : node) if (contains(child, path)) return true;
        return false;
    }
    private List<UUID> ids(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> rs.getObject(1, UUID.class), args);
    }
    private record Card(UUID taskId, String operation, String parameters) { }
}

@RestController
@Profile("prod | secure")
class ProtectedAttachmentController {
    private final Path root;
    private final AttachmentAccess access;

    ProtectedAttachmentController(@Value("${mes.storage.upload-dir:./data/uploads}") String uploadDir, AttachmentAccess access) {
        root = Path.of(uploadDir).toAbsolutePath().normalize();
        this.access = access;
    }

    @GetMapping("/uploads/{category}/{name}")
    ResponseEntity<Resource> download(@PathVariable String category, @PathVariable String name) throws IOException {
        String type = access.requireRead("/uploads/" + category + "/" + name);
        Path path = root.resolve(category).resolve(name).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path) || !path.toRealPath().startsWith(root.toRealPath())) {
            throw DomainException.notFound("ATTACHMENT_NOT_FOUND", "附件不存在或无权访问");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(type))
            .header("X-Content-Type-Options", "nosniff").body(new FileSystemResource(path));
    }
}
