package com.renyi.mes.engineering;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProcessCardAiApplication {

	private static final List<String> OPERATION_CODES = List.of(
		"WAX_INJECTION", "WAX_REPAIR", "TREE_ASSEMBLY", "SHELL_BUILDING",
		"DEWAX", "POURING", "KNOCKOUT", "CUTTING", "OPTIONAL_FINISHING"
	);

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final ProcessCardAiClient client;

	public ProcessCardAiApplication(JdbcTemplate jdbc, ObjectMapper objectMapper, ProcessCardAiClient client) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.client = client;
	}

	public SuggestionView suggest(SuggestionCommand command) {
		requireManagePermission(command.operatorCode());
		ProductContext product = jdbc.query("""
			select code, name, route_type, coalesce(specification, '') as specification, coalesce(material, '') as material
			from engineering_product where id = ?
			""", ProcessCardAiApplication::mapProduct, command.productId()).stream().findFirst()
			.orElseThrow(() -> DomainException.notFound("PRODUCT_NOT_FOUND", "产品不存在"));
		TemplateContext source = command.sourceTemplateId() == null ? TemplateContext.empty() : source(command.sourceTemplateId(), command.productId());
		Map<String, String> currentOperations = normalizeOperations(command.currentOperationParameters());
		ProcessCardAiClient.Suggestion suggestion = client.generate(new ProcessCardAiClient.Prompt(
			product.code(), product.name(), product.routeType(), product.specification(), product.material(),
			trim(command.currentEngineeringParameters()), currentOperations, source.engineeringParameters(), source.operationParameters()
		));
		return new SuggestionView(trim(suggestion.engineeringParameters()), normalizeOperations(suggestion.operationParameters()), trim(suggestion.note()));
	}

	private TemplateContext source(UUID sourceId, UUID productId) {
		return jdbc.query("""
			select engineering_parameters, operation_parameters from product_process_card_template
			where id = ? and product_id = ?
			""", this::mapTemplate, sourceId, productId).stream().findFirst()
			.orElseThrow(() -> DomainException.badRequest("PROCESS_TEMPLATE_SOURCE_INVALID", "所选历史工艺不属于当前产品"));
	}

	private void requireManagePermission(String employeeCode) {
		Integer permitted = jdbc.queryForObject("""
			select count(*) from organization_member_role memberRole
			join access_role_permission rolePermission on rolePermission.role_code = memberRole.role_code
			where memberRole.employee_code = ? and rolePermission.permission_code = 'PROCESS_CARD_TEMPLATE_MANAGE'
			""", Integer.class, normalize(employeeCode));
		if (permitted == null || permitted == 0) {
			throw DomainException.forbidden("PROCESS_TEMPLATE_MANAGE_DENIED", "当前账户无权维护产品工艺模板");
		}
	}

	private Map<String, String> normalizeOperations(String value) {
		try {
			return normalizeOperations(objectMapper.readValue(value == null || value.isBlank() ? "{}" : value, new TypeReference<Map<String, String>>() { }));
		} catch (Exception exception) {
			throw DomainException.badRequest("AI_OPERATION_PARAMETERS_INVALID", "工序参数格式无效");
		}
	}

	private Map<String, String> normalizeOperations(Map<String, String> values) {
		Map<String, String> normalized = new LinkedHashMap<>();
		for (String operationCode : OPERATION_CODES) normalized.put(operationCode, trim(values == null ? "" : values.get(operationCode)));
		return normalized;
	}

	private static ProductContext mapProduct(ResultSet rs, int rowNum) throws SQLException {
		return new ProductContext(rs.getString("code"), rs.getString("name"), rs.getString("route_type"), rs.getString("specification"), rs.getString("material"));
	}

	private TemplateContext mapTemplate(ResultSet rs, int rowNum) throws SQLException {
		return new TemplateContext(rs.getString("engineering_parameters"), normalizeOperations(rs.getString("operation_parameters")));
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) throw DomainException.badRequest("AI_OPERATOR_REQUIRED", "必须指定操作人");
		return value.trim().toUpperCase();
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private record ProductContext(String code, String name, String routeType, String specification, String material) { }
	private record TemplateContext(String engineeringParameters, Map<String, String> operationParameters) {
		static TemplateContext empty() { return new TemplateContext("", new LinkedHashMap<>()); }
	}

	public record SuggestionCommand(UUID productId, UUID sourceTemplateId, String currentEngineeringParameters,
			String currentOperationParameters, String operatorCode) { }
	public record SuggestionView(String engineeringParameters, Map<String, String> operationParameters, String note) { }
}
