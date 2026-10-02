package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentPrintApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void allowsWorkersToPrintMaskedProcessCardsButBlocksSensitiveOrderPrints() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String productId = postAndReadId("/api/products", """
			{"code":"DOC-%s","name":"Document Test Part","routeType":"MID_TEMP_WAX","routeVersion":"V1"}
			""".formatted(suffix));
		String customerId = postAndReadId("/api/customers", """
			{"code":"DOC-C-%s","name":"Sensitive Customer"}
			""".formatted(suffix));
		String orderId = postAndReadId("/api/orders", """
			{"orderNo":"DOC-SO-%s","customerId":"%s","priority":"NORMAL","lines":[{"productId":"%s","quantity":10,"unit":"PCS"}]}
			""".formatted(suffix, customerId, productId));
		OrderReviewTestSupport.submit(mvc, orderId);

		mvc.perform(post("/api/orders/{orderId}/engineering-confirmation", orderId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"engineerCode\":\"E001\",\"engineeringParameters\":\"Document test process requirement\"}"))
			.andExpect(status().isOk());
		OrderReviewTestSupport.releaseAndLaunchAfterRequiredReviews(mvc, orderId);
		MvcResult tasks = mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk()).andReturn();
		String tasksBody = tasks.getResponse().getContentAsString();
		java.util.List<String> injectionTasks = JsonPath.read(tasksBody, "$[?(@.operationCode == 'WAX_INJECTION')].id");
		String injectionTaskId = injectionTasks.getFirst();

		String moldAssetId = postAndReadId("/api/resources", """
			{"assetCode":"DOC-MOLD-%s","assetName":"Document Test Mold","assetType":"MOLD"}
			""".formatted(suffix));
		mvc.perform(post("/api/tasks/{taskId}/wax-dispatch", injectionTaskId)
				.contentType(MediaType.APPLICATION_JSON).content("""
					{"moldAssetId":"%s","warehouseCode":"MOLD-01","warehouseOperatorCode":"M001","workerCode":"W001",
					"reportingMode":"SELF_REPORTED_QUANTITY","settlementUnit":"PCS","compensationMode":"PIECE_PCS","supervisorCode":"S001"}
					""".formatted(moldAssetId)))
			.andExpect(status().isOk());
		mvc.perform(post("/api/documents/previews")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"documentType":"PROCESS_CARD","entityId":"%s","actorCode":"W001","selectedFields":["TASK_NO","PRODUCT","PROCESS_REQUIREMENTS","SOP"]}
					""".formatted(injectionTaskId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documentType").value("PROCESS_CARD"))
			.andExpect(jsonPath("$.fields[?(@.code == 'CUSTOMER_NAME')]").isEmpty());

		mvc.perform(post("/api/documents/previews")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"documentType":"PROCESS_CARD","entityId":"%s","actorCode":"P002","selectedFields":["TASK_NO","PROCESS_REQUIREMENTS"]}
					""".formatted(injectionTaskId)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("DOCUMENT_TASK_SCOPE_DENIED"));


		mvc.perform(post("/api/documents/previews")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"documentType":"FLOW_CARD","entityId":"%s","actorCode":"S001","selectedFields":["ORDER_NO","CUSTOMER_NAME","PRODUCT","SPECIFICATION","MATERIAL","FLOW_STEPS","FLOW_QR_PAYLOAD"]}
					""".formatted(injectionTaskId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documentType").value("FLOW_CARD"))
			.andExpect(jsonPath("$.sensitiveIncluded").value(true))
			.andExpect(jsonPath("$.fields[?(@.code == 'FLOW_STEPS')]").isNotEmpty());

		mvc.perform(post("/api/documents/previews")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"documentType":"ORDER_DETAIL","entityId":"%s","actorCode":"W001","selectedFields":["ORDER_NO","CUSTOMER_NAME"]}
					""".formatted(orderId)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("DOCUMENT_PERMISSION_DENIED"));

		mvc.perform(post("/api/documents/previews")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"documentType":"ORDER_DETAIL","entityId":"%s","actorCode":"GM001","selectedFields":["ORDER_NO","CUSTOMER_NAME","LINES"]}
					""".formatted(orderId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.fields[?(@.code == 'CUSTOMER_NAME')].value").value("Sensitive Customer"));

		mvc.perform(get("/api/documents/audits").param("actorCode", "GM001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].documentType").value("ORDER_DETAIL"))
			.andExpect(jsonPath("$[0].sensitiveIncluded").value(true));
	}

	private String postAndReadId(String path, String json) throws Exception {
		MvcResult result = mvc.perform(post(path)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json))
			.andExpect(status().isCreated())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
