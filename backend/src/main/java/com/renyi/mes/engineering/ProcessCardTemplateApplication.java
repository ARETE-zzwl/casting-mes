package com.renyi.mes.engineering;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessCardTemplateApplication {

	private final JdbcTemplate jdbc;

	public ProcessCardTemplateApplication(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<TemplateView> list(UUID productId, boolean publishedOnly) {
		String where = " where 1 = 1" + (productId == null ? "" : " and t.product_id = ?")
			+ (publishedOnly ? " and t.status = 'PUBLISHED'" : "");
		return productId == null
			? jdbc.query(select() + where + " order by t.created_at desc", ProcessCardTemplateApplication::map)
			: jdbc.query(select() + where + " order by t.created_at desc", ProcessCardTemplateApplication::map, productId);
	}

	@Transactional
	public TemplateView create(CreateCommand command) {
		requireManagePermission(command.createdBy());
		Integer products = jdbc.queryForObject("select count(*) from engineering_product where id = ?", Integer.class, command.productId());
		if (products == null || products == 0) {
			throw DomainException.notFound("PRODUCT_NOT_FOUND", "产品不存在");
		}
		Integer versions = jdbc.queryForObject("select count(*) from product_process_card_template where product_id = ? and upper(version) = upper(?)",
			Integer.class, command.productId(), require(command.version(), "PROCESS_TEMPLATE_VERSION_REQUIRED"));
		if (versions != null && versions > 0) {
			throw DomainException.conflict("PROCESS_TEMPLATE_VERSION_EXISTS", "该产品工艺版本已存在，请使用新的版本号");
		}
		UUID id = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			insert into product_process_card_template (
				id, template_no, product_id, version, status, engineering_parameters, operation_parameters, created_by, created_at
			) values (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)
			""", id, identifier(), command.productId(), require(command.version(), "PROCESS_TEMPLATE_VERSION_REQUIRED"),
			require(command.engineeringParameters(), "PROCESS_TEMPLATE_PARAMETERS_REQUIRED"),
			require(command.operationParameters(), "PROCESS_TEMPLATE_OPERATION_PARAMETERS_REQUIRED"),
			normalize(command.createdBy()), Timestamp.from(now));
		return require(id, false);
	}

	@Transactional
	public TemplateView publish(UUID id, String publishedBy) {
		requireManagePermission(publishedBy);
		TemplateRow template = requireRow(id, true);
		jdbc.update("update product_process_card_template set status = 'RETIRED' where product_id = ? and status = 'PUBLISHED' and id <> ?", template.productId(), id);
		jdbc.update("""
			update product_process_card_template
			set status = 'PUBLISHED', published_by = ?, published_at = ? where id = ?
			""", normalize(publishedBy), Timestamp.from(Instant.now()), id);
		return require(id, false);
	}

	private TemplateView require(UUID id, boolean lock) {
		return jdbc.query(select() + " where t.id = ?" + (lock ? " for update" : ""), ProcessCardTemplateApplication::map, id)
			.stream().findFirst().orElseThrow(() -> DomainException.notFound("PROCESS_TEMPLATE_NOT_FOUND", "产品工艺卡模板不存在"));
	}

	private TemplateRow requireRow(UUID id, boolean lock) {
		return jdbc.query("select id, product_id from product_process_card_template where id = ?" + (lock ? " for update" : ""),
			(rs, rowNum) -> new TemplateRow(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class)), id)
			.stream().findFirst().orElseThrow(() -> DomainException.notFound("PROCESS_TEMPLATE_NOT_FOUND", "产品工艺卡模板不存在"));
	}

	private static String select() {
		return """
			select t.*, p.code as product_code, p.name as product_name, p.route_type
			from product_process_card_template t
			join engineering_product p on p.id = t.product_id
			""";
	}

	private void requireManagePermission(String employeeCode) {
		String code = normalize(employeeCode);
		Integer permitted = jdbc.queryForObject("""
			select count(*) from organization_member_role memberRole
			join access_role_permission rolePermission on rolePermission.role_code = memberRole.role_code
			where memberRole.employee_code = ? and rolePermission.permission_code = 'PROCESS_CARD_TEMPLATE_MANAGE'
			""", Integer.class, code);
		if (permitted == null || permitted == 0) {
			throw DomainException.forbidden("PROCESS_TEMPLATE_MANAGE_DENIED", "当前账户无权维护产品工艺模板");
		}
	}

	private static TemplateView map(ResultSet rs, int rowNum) throws SQLException {
		Timestamp publishedAt = rs.getTimestamp("published_at");
		return new TemplateView(rs.getObject("id", UUID.class), rs.getString("template_no"), rs.getObject("product_id", UUID.class),
			rs.getString("product_code"), rs.getString("product_name"), RouteType.valueOf(rs.getString("route_type")), rs.getString("version"),
			rs.getString("status"), rs.getString("engineering_parameters"), rs.getString("operation_parameters"), rs.getString("created_by"),
			rs.getString("published_by"), rs.getTimestamp("created_at").toInstant(), publishedAt == null ? null : publishedAt.toInstant());
	}

	private static String identifier() { return "PCT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(); }
	private static String normalize(String value) { return require(value, "PROCESS_TEMPLATE_VALUE_REQUIRED").trim().toUpperCase(); }
	private static String require(String value, String code) {
		if (value == null || value.isBlank()) throw DomainException.badRequest(code, "工艺卡模板字段不能为空");
		return value.trim();
	}

	private record TemplateRow(UUID id, UUID productId) { }

	public record CreateCommand(UUID productId, String version, String engineeringParameters, String operationParameters, String createdBy) { }
	public record TemplateView(UUID id, String templateNo, UUID productId, String productCode, String productName, RouteType routeType,
			String version, String status, String engineeringParameters, String operationParameters, String createdBy, String publishedBy,
			Instant createdAt, Instant publishedAt) { }
}
