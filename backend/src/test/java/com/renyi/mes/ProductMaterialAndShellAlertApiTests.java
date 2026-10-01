package com.renyi.mes;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ProductMaterialAndShellAlertApiTests {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;

	@Test
	void createsMaterialVariantAndPrintsOperationalOrderRemark() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		String productId = create("/api/products", """
			{"code":"VARIANT-%s","name":"材质变体阀体","routeType":"MID_TEMP_WAX","routeVersion":"V1","specification":"DN80","material":"CF8"}
			""".formatted(suffix));
		String customerId = create("/api/customers", "{\"name\":\"材质变体客户\"}");
		MvcResult created = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
			{"customerId":"%s","priority":"NORMAL","remark":"包装箱内放置材质标识卡","lines":[{"productId":"%s","productMaterial":"304","quantity":8,"unit":"PCS"}]}
			""".formatted(customerId, productId)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.lines[0].productMaterial").value("304")).andReturn();
		String orderId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
		String variantProductId = JsonPath.read(created.getResponse().getContentAsString(), "$.lines[0].productId");
		assertNotEquals(productId, variantProductId, "不同材质必须固化为独立产品");
		mvc.perform(get("/api/products"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '%s' && @.material == '304')]".formatted(variantProductId)).isNotEmpty());

		OrderReviewTestSupport.submit(mvc, orderId);
		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"engineerCode\":\"E001\",\"processCardVersion\":\"V1\",\"engineeringParameters\":\"304 工艺要求\"}"))
			.andExpect(status().isOk());
		OrderReviewTestSupport.releaseAfterRequiredReviews(mvc, orderId);
		String taskId = JsonPath.read(mvc.perform(get("/api/tasks").param("orderId", orderId)).andReturn().getResponse().getContentAsString(), "$[0].id");
		String preview = """
			{"documentType":"WORKSHOP_JOB_SHEET","entityId":"%s","actorCode":"S001","selectedFields":["ORDER_REMARK"],"outputType":"PREVIEW"}
			""".formatted(taskId);
		mvc.perform(post("/api/documents/previews").contentType(MediaType.APPLICATION_JSON).content(preview))
			.andExpect(status().isOk()).andExpect(jsonPath("$.fields[0].value").value("包装箱内放置材质标识卡"));
	}

	@Test
	void alertsSupervisorsManagementAndFrontDeskWhenManualShellWaitsOverTwoDays() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		String productId = create("/api/products", """
			{"code":"SHELL-ALERT-%s","name":"人工制壳预警件","routeType":"LOW_TEMP_WAX","routeVersion":"V1","specification":"DN50","material":"CF8"}
			""".formatted(suffix));
		String customerId = create("/api/customers", "{\"name\":\"人工制壳预警客户\"}");
		String orderId = create("/api/orders", """
			{"customerId":"%s","priority":"NORMAL","lines":[{"productId":"%s","quantity":6,"unit":"PCS"}]}
			""".formatted(customerId, productId));
		OrderReviewTestSupport.submit(mvc, orderId);
		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"engineerCode\":\"E001\",\"processCardVersion\":\"V1\",\"engineeringParameters\":\"人工制壳预警工艺\"}"))
			.andExpect(status().isOk());
		OrderReviewTestSupport.releaseAfterRequiredReviews(mvc, orderId);
		java.util.List<String> shellTaskIds = JsonPath.read(mvc.perform(get("/api/tasks").param("orderId", orderId)).andReturn().getResponse().getContentAsString(), "$[?(@.operationCode == 'MANUAL_SHELL_BUILDING')].id");
		String taskId = shellTaskIds.getFirst();
		Instant waitingSince = Instant.now().minus(49, ChronoUnit.HOURS);
		jdbc.update("update planning_task set status = 'IN_PROGRESS', assigned_to = 'LSH01', shell_line_mode = 'MANUAL', started_at = ? where id = ?", Timestamp.from(waitingSince), UUID.fromString(taskId));
		jdbc.update("""
			insert into shell_building_record (id, task_id, operation_id, method, layer_count, drying_minutes, quantity, operator_code, note, next_action, photo_url, occurred_at)
			values (?, ?, ?, 'MANUAL', 1, 120, 6, 'LSH01', '第一层后等待干燥', 'WAIT_NEXT_LAYER', null, ?)
			""", UUID.randomUUID(), UUID.fromString(taskId), UUID.randomUUID(), Timestamp.from(waitingSince));

		mvc.perform(get("/api/manual-shell-drying-alerts").param("recipientCode", "L001"))
			.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.taskId == '%s' && @.waitingHours >= 48)]".formatted(taskId)).isNotEmpty());
		for (String recipient : new String[] { "L001", "GM001", "FD01" }) {
			mvc.perform(get("/api/notifications").param("recipientCode", recipient))
				.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.title == '人工制壳干燥停留预警')]").isNotEmpty());
		}
		Instant latestLayer = Instant.now();
		jdbc.update("""
			insert into shell_building_record (id, task_id, operation_id, method, layer_count, drying_minutes, quantity, operator_code, note, next_action, photo_url, occurred_at)
			values (?, ?, ?, 'MANUAL', 2, 120, 6, 'LSH01', '第二层等待干燥', 'WAIT_NEXT_LAYER', null, ?)
			""", UUID.randomUUID(), UUID.fromString(taskId), UUID.randomUUID(), Timestamp.from(latestLayer));
		mvc.perform(get("/api/manual-shell-drying-alerts").param("recipientCode", "L001"))
			.andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
		jdbc.update("update shell_building_record set occurred_at = ? where task_id = ? and layer_count = 2",
			Timestamp.from(latestLayer.minus(49, ChronoUnit.HOURS)), UUID.fromString(taskId));
		mvc.perform(get("/api/manual-shell-drying-alerts").param("recipientCode", "L001"))
			.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.taskId == '%s' && @.layerCount == 2)]".formatted(taskId)).isNotEmpty());
	}

	private String create(String path, String body) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
