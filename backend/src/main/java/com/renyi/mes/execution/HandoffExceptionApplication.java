package com.renyi.mes.execution;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandoffExceptionApplication {

	private final JdbcTemplate jdbc;
	private final com.renyi.mes.common.BusinessAccess access;
	public HandoffExceptionApplication(JdbcTemplate jdbc, com.renyi.mes.common.BusinessAccess access) { this.jdbc = jdbc; this.access = access; }

	@Transactional(readOnly = true)
	public List<ExceptionView> list(boolean includeResolved) {
		var scope = access.taskScope("exception_rows.task_id");
		String sql = select() + " where " + scope.clause() + (includeResolved ? "" : " and resolution_status <> 'RESOLVED'") + " order by occurred_at desc";
		return jdbc.query(sql, HandoffExceptionApplication::map, scope.parameters().toArray());
	}

	@Transactional(readOnly = true)
	public PageResult<ExceptionView> search(boolean includeResolved, String keyword, String resolutionStatus, int page, int size) {
		if (page < 0 || size < 1 || size > 100) throw DomainException.badRequest("HANDOFF_EXCEPTION_PAGE_INVALID", "分页参数无效");
		Filter filter = filter(includeResolved, keyword, resolutionStatus);
		var scope = access.taskScope("filtered_exceptions.task_id");
		String rows = "select * from (" + select() + ") filtered_exceptions" + filter.where() + (filter.where().isEmpty() ? " where " : " and ") + scope.clause();
		List<Object> parameters = new ArrayList<>(filter.parameters()); parameters.addAll(scope.parameters());
		Long total = jdbc.queryForObject("select count(*) from (" + rows + ") count_rows", Long.class, parameters.toArray());
		List<Object> pageParameters = new ArrayList<>(parameters);
		pageParameters.add(size);
		pageParameters.add(page * size);
		List<ExceptionView> items = jdbc.query(rows + " order by occurred_at desc limit ? offset ?", HandoffExceptionApplication::map, pageParameters.toArray());
		return PageResult.of(items, page, size, total == null ? 0 : total);
	}

	@Transactional
	public ExceptionView assign(String sourceType, UUID id, String ownerCode, String assignedBy) {
		access.requireManageTask(find(sourceType, id).taskId());
		String table = table(sourceType);
		int changed = jdbc.update("update " + table + " set owner_code = ?, resolution_status = 'IN_PROGRESS' where id = ? and resolution_status <> 'RESOLVED'",
			normalize(ownerCode), id);
		if (changed == 0) throw DomainException.conflict("HANDOFF_EXCEPTION_ASSIGNMENT_CONFLICT", "交接异常不存在或已关闭");
		return find(sourceType, id);
	}

	@Transactional
	public ExceptionView resolve(String sourceType, UUID id, String resolutionNote, String resolvedBy) {
		access.requireManageTask(find(sourceType, id).taskId());
		String table = table(sourceType);
		int changed = jdbc.update("""
			update %s set resolution_status = 'RESOLVED', resolution_note = ?, resolved_by = ?, resolved_at = ?
			where id = ? and resolution_status <> 'RESOLVED'
			""".formatted(table), require(resolutionNote), normalize(resolvedBy), Timestamp.from(Instant.now()), id);
		if (changed == 0) throw DomainException.conflict("HANDOFF_EXCEPTION_RESOLUTION_CONFLICT", "交接异常不存在或已关闭");
		return find(sourceType, id);
	}

	private ExceptionView find(String sourceType, UUID id) {
		return jdbc.query(select() + " where source_type = ? and id = ?", HandoffExceptionApplication::map, normalizeSource(sourceType), id)
			.stream().findFirst().orElseThrow(() -> DomainException.notFound("HANDOFF_EXCEPTION_NOT_FOUND", "交接异常不存在"));
	}

	private static String select() {
		return """
			select * from (
			select 'HANDOFF' as source_type, h.id, h.handoff_no as reference_no, h.task_id, t.task_no, t.operation_name,
			       o.order_no, w.product_name, h.expected_quantity, h.received_quantity, h.exception_reason,
			       h.photo_url as evidence_url, h.handed_over_by, h.received_by, h.occurred_at,
			       h.resolution_status, h.owner_code, h.resolution_note, h.resolved_by, h.resolved_at
			from production_handoff_event h
			join planning_task t on t.id = h.task_id
			join planning_batch b on b.id = t.batch_id
			join planning_work_order w on w.id = b.work_order_id
			join customer_order_header o on o.id = w.order_id
			where h.status = 'EXCEPTION'
			union all
			select 'CART' as source_type, c.id, c.transfer_no as reference_no, c.source_task_id as task_id, t.task_no, t.operation_name,
			       o.order_no, w.product_name, c.loaded_quantity as expected_quantity, c.received_quantity, c.exception_reason,
			       coalesce(c.receive_photo_url, c.load_photo_url) as evidence_url, c.loaded_by as handed_over_by, c.received_by, c.received_at as occurred_at,
			       c.resolution_status, c.owner_code, c.resolution_note, c.resolved_by, c.resolved_at
			from production_cart_transfer c
			join planning_task t on t.id = c.source_task_id
			join planning_batch b on b.id = t.batch_id
			join planning_work_order w on w.id = b.work_order_id
			join customer_order_header o on o.id = w.order_id
			where c.status = 'EXCEPTION'
			) exception_rows
			""";
	}

	private static Filter filter(boolean includeResolved, String keyword, String resolutionStatus) {
		List<String> clauses = new ArrayList<>();
		List<Object> parameters = new ArrayList<>();
		if (!includeResolved) clauses.add("resolution_status <> 'RESOLVED'");
		if (resolutionStatus != null && !resolutionStatus.isBlank()) {
			String status = resolutionStatus.trim().toUpperCase(Locale.ROOT);
			if (!List.of("OPEN", "IN_PROGRESS", "RESOLVED").contains(status)) throw DomainException.badRequest("HANDOFF_EXCEPTION_STATUS_INVALID", "异常状态无效");
			clauses.add("resolution_status = ?");
			parameters.add(status);
		}
		if (keyword != null && !keyword.isBlank()) {
			clauses.add("(upper(reference_no) like ? or upper(task_no) like ? or upper(order_no) like ? or upper(product_name) like ? or upper(coalesce(owner_code, '')) like ?)");
			String value = "%" + keyword.trim().toUpperCase(Locale.ROOT) + "%";
			for (int index = 0; index < 5; index++) parameters.add(value);
		}
		return new Filter(clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses), parameters);
	}

	private static ExceptionView map(ResultSet rs, int rowNum) throws SQLException {
		Timestamp resolvedAt = rs.getTimestamp("resolved_at");
		return new ExceptionView(rs.getString("source_type"), rs.getObject("id", UUID.class), rs.getString("reference_no"),
			rs.getObject("task_id", UUID.class), rs.getString("task_no"), rs.getString("operation_name"), rs.getString("order_no"),
			rs.getString("product_name"), rs.getBigDecimal("expected_quantity"), rs.getBigDecimal("received_quantity"),
			rs.getString("exception_reason"), rs.getString("evidence_url"), rs.getString("handed_over_by"), rs.getString("received_by"),
			rs.getTimestamp("occurred_at").toInstant(), rs.getString("resolution_status"), rs.getString("owner_code"),
			rs.getString("resolution_note"), rs.getString("resolved_by"), resolvedAt == null ? null : resolvedAt.toInstant());
	}

	private static String table(String sourceType) { return "HANDOFF".equals(normalizeSource(sourceType)) ? "production_handoff_event" : "production_cart_transfer"; }
	private static String normalizeSource(String value) {
		String source = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		if (!source.equals("HANDOFF") && !source.equals("CART")) throw DomainException.badRequest("HANDOFF_EXCEPTION_SOURCE_INVALID", "交接异常来源不支持");
		return source;
	}
	private static String normalize(String value) { return require(value).toUpperCase(Locale.ROOT); }
	private static String require(String value) {
		if (value == null || value.isBlank()) throw DomainException.badRequest("HANDOFF_EXCEPTION_VALUE_REQUIRED", "异常处理信息不能为空");
		return value.trim();
	}

	private record Filter(String where, List<Object> parameters) { }

	public record ExceptionView(String sourceType, UUID id, String referenceNo, UUID taskId, String taskNo, String operationName,
			String orderNo, String productName, BigDecimal expectedQuantity, BigDecimal receivedQuantity, String reason, String evidenceUrl,
			String handedOverBy, String receivedBy, Instant occurredAt, String resolutionStatus, String ownerCode, String resolutionNote,
			String resolvedBy, Instant resolvedAt) { }
}
