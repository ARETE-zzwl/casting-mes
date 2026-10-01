package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import com.renyi.mes.engineering.ProcessCardAiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class OperationalControlExtensionsApiTests {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private ProcessCardAiClient processCardAiClient;

	@Test
	void publishesProductTemplateAndLoadsOperationalControlBoards() throws Exception {
		String productId = create("/api/products", """
			{"name":"Control Extension Product","routeType":"MID_TEMP_WAX","routeVersion":"V1","specification":"DN25","material":"304"}
			""");
		String templateId = create("/api/process-card-templates", """
			{"productId":"%s","version":"V1","engineeringParameters":"304; controlled template",
			 "operationParameters":"{\\"DEWAX\\":\\"170C\\",\\"POURING\\":\\"1580C\\"}","createdBy":"E001"}
			""".formatted(productId));
		mvc.perform(post("/api/process-card-templates/{id}/publish", templateId)
				.contentType(MediaType.APPLICATION_JSON).content("{\"publishedBy\":\"E001\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
		mvc.perform(get("/api/process-card-templates").param("productId", productId).param("publishedOnly", "true"))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].version").value("V1"));
		mvc.perform(get("/api/handoff-exceptions")).andExpect(status().isOk());
		mvc.perform(get("/api/furnace-batches")).andExpect(status().isOk());
	}

	@Test
	void deniesTemplateMaintenanceToUsersWithoutEngineeringPermission() throws Exception {
		String productId = create("/api/products", """
			{"name":"Permission Product","routeType":"MID_TEMP_WAX","routeVersion":"V1","specification":"DN20","material":"304"}
			""");
		mvc.perform(post("/api/process-card-templates").contentType(MediaType.APPLICATION_JSON).content("""
			{"productId":"%s","version":"V1","engineeringParameters":"Denied",
			 "operationParameters":"{\\"WAX_INJECTION\\":\\"68C\\"}","createdBy":"FD01"}
			""".formatted(productId)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("PROCESS_TEMPLATE_MANAGE_DENIED"));
	}

	@Test
	void generatesEditableAiProcessSuggestionsForEngineersOnly() throws Exception {
		String productId = create("/api/products", """
			{"name":"AI Suggestion Product","routeType":"MID_TEMP_WAX","routeVersion":"V1","specification":"DN40","material":"CF8"}
			""");
		when(processCardAiClient.generate(any())).thenReturn(new ProcessCardAiClient.Suggestion(
			"CF8；首件确认；密封面防护", java.util.Map.of("WAX_INJECTION", "蜡料待工程确认"), "请结合首件结果复核温度与压力。"
		));
		mvc.perform(post("/api/process-card-templates/ai-suggestion").contentType(MediaType.APPLICATION_JSON).content("""
			{"productId":"%s","currentEngineeringParameters":"","currentOperationParameters":"{\\"WAX_INJECTION\\":\\"\\"}","operatorCode":"E001"}
			""".formatted(productId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.engineeringParameters").value("CF8；首件确认；密封面防护"))
			.andExpect(jsonPath("$.operationParameters.WAX_INJECTION").value("蜡料待工程确认"));
		mvc.perform(post("/api/process-card-templates/ai-suggestion").contentType(MediaType.APPLICATION_JSON).content("""
			{"productId":"%s","currentOperationParameters":"{}","operatorCode":"FD01"}
			""".formatted(productId)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("PROCESS_TEMPLATE_MANAGE_DENIED"));
	}

	@Test
	void returnsControlledAiSchedulingAdviceForAuthorizedPlannerOnly() throws Exception {
		when(processCardAiClient.completeJson(any(), any())).thenReturn("""
			{"summary":"当前产线待开工任务需先核对交期与人员负荷。","actions":["复核急单交期","确认下一可开工任务"],"caution":"不改变已开工任务与人工排产顺序。"}
			""");
		mvc.perform(post("/api/ai-assistant/schedule-risk").contentType(MediaType.APPLICATION_JSON).content("""
			{"lineCode":"MID_WAX","operatorCode":"P001"}
			"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("排产风险分析"))
			.andExpect(jsonPath("$.actions[0]").value("复核急单交期"));
		mvc.perform(post("/api/ai-assistant/schedule-risk").contentType(MediaType.APPLICATION_JSON).content("""
			{"lineCode":"MID_WAX","operatorCode":"FD01"}
			"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("AI_ASSISTANT_PERMISSION_DENIED"));
	}

	@Test
	void splitsAnUnstartedWorkOrderIntoIndependentProductionBatches() throws Exception {
		String productId = create("/api/products", """
			{"name":"Batch Split Product","routeType":"MID_TEMP_WAX","routeVersion":"V1","specification":"DN50","material":"CF8"}
			""");
		String customerId = create("/api/customers", """
			{"name":"Batch Split Customer"}
			""");
		String orderId = create("/api/orders", """
			{"customerId":"%s","priority":"NORMAL","lines":[{"productId":"%s","quantity":12,"unit":"PCS"}]}
			""".formatted(customerId, productId));
		OrderReviewTestSupport.submit(mvc, orderId);
		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId).contentType(MediaType.APPLICATION_JSON).content("""
			{"engineerCode":"E001","processCardVersion":"V1","engineeringParameters":"Batch split test"}
			"""))
			.andExpect(status().isOk());
		OrderReviewTestSupport.releaseAfterRequiredReviews(mvc, orderId);
		MvcResult workOrders = mvc.perform(get("/api/work-orders").param("orderId", orderId))
			.andExpect(status().isOk()).andReturn();
		String workOrderId = JsonPath.read(workOrders.getResponse().getContentAsString(), "$[0].id");
		mvc.perform(post("/api/work-orders/{id}/batches", workOrderId).contentType(MediaType.APPLICATION_JSON).content("""
			{"quantities":[5,7],"supervisorCode":"GM001"}
			"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].orderQuantity").value(12));
		mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(22));
		mvc.perform(post("/api/work-orders/{id}/batches", workOrderId).contentType(MediaType.APPLICATION_JSON).content("""
			{"quantities":[6,7],"supervisorCode":"GM001"}
			"""))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("BATCH_QUANTITY_TOTAL_INVALID"));
	}

	private String create(String path, String body) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
