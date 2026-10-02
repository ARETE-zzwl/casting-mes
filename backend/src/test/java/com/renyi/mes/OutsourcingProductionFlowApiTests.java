package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// Isolate the fixture context without overriding the selected database profile.
@SpringBootTest(properties = "spring.application.name=outsourcing-production-test")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutsourcingProductionFlowApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void linkedSandOutsourcingAdvancesTasksAndRequiresIncomingInspectionBeforeClosing() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String productId = postAndRead("/api/products", """
			{"code":"SAND-%s","name":"砂型联动件","routeType":"SAND_OUTSOURCE","routeVersion":"V1","material":"HT250"}
			""".formatted(suffix), "$.id");
		String customerId = postAndRead("/api/customers", """
			{"code":"CUS-%s","name":"砂型联动客户"}
			""".formatted(suffix), "$.id");
		String orderId = postAndRead("/api/orders", """
			{"orderNo":"SO-%s","customerId":"%s","priority":"NORMAL","lines":[{"productId":"%s","quantity":8,"unit":"PCS"}]}
			""".formatted(suffix, customerId, productId), "$.id");
		OrderReviewTestSupport.releaseAndLaunchAfterRequiredReviews(mvc, orderId);

		String dispatchTaskId = taskId(orderId, "OUTSOURCE_DISPATCH");
		String supplierId = postAndRead("/api/outsourcing/suppliers", """
			{"code":"SUP-%s","name":"砂型协作厂"}
			""".formatted(suffix), "$.id");
		String outsourcingId = postAndRead("/api/outsourcing/orders", """
			{
			  "orderNo":"OS-%s","supplierId":"%s","planningTaskId":"%s",
			  "itemCode":"SAND-%s","itemName":"砂型联动件","quantity":8,"unit":"PCS"
			}
			""".formatted(suffix, supplierId, dispatchTaskId, suffix), "$.id");

		transition(outsourcingId, "SENT", null, "PM01", "SENT");
		assertTaskStatus(dispatchTaskId, "COMPLETED");
		transition(outsourcingId, "IN_PROGRESS", null, "PM01", "IN_PROGRESS");
		assertTaskStatus(taskId(orderId, "OUTSOURCE_PROGRESS"), "IN_PROGRESS");
		String inspectionTaskId = taskId(orderId, "INCOMING_INSPECTION");
		assertTaskStatus(inspectionTaskId, "BLOCKED");
		transition(outsourcingId, "RECEIVED", 4, "PM01", "IN_PROGRESS");
		assertTaskStatus(inspectionTaskId, "BLOCKED");
		transition(outsourcingId, "RECEIVED", 4, "PM01", "RECEIVED");

		assertTaskStatus(taskId(orderId, "OUTSOURCE_PROGRESS"), "COMPLETED");
		assertTaskStatus(inspectionTaskId, "READY");
		mvc.perform(post("/api/outsourcing/orders/{id}/milestones", outsourcingId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nextStatus\":\"CLOSED\",\"operatorCode\":\"PM01\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("OUTSOURCING_INSPECTION_REQUIRED"));

		mvc.perform(post("/api/tasks/{id}/assignment", inspectionTaskId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"workerCode\":\"PM01\",\"supervisorCode\":\"PM01\"}"))
			.andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/start", inspectionTaskId).header("X-Operator-Code", "PM01"))
			.andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/supervisor-reports", inspectionTaskId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"operationId":"%s","goodQuantity":8,"scrapQuantity":0,"supervisorCode":"PM01"}
					""".formatted(UUID.randomUUID())))
			.andExpect(status().isCreated());
		transition(outsourcingId, "CLOSED", null, "PM01", "CLOSED");

		mvc.perform(get("/api/trace/orders/{id}", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.workOrders[0].tasks[0].task.status").value("COMPLETED"))
			.andExpect(jsonPath("$.workOrders[0].tasks[1].task.status").value("COMPLETED"))
			.andExpect(jsonPath("$.workOrders[0].tasks[2].task.status").value("COMPLETED"));
	}

	private String taskId(String orderId, String operationCode) throws Exception {
		MvcResult result = mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk()).andReturn();
		List<String> ids = JsonPath.read(result.getResponse().getContentAsString(), "$[?(@.operationCode == '" + operationCode + "')].id");
		return ids.getFirst();
	}

	private void assertTaskStatus(String taskId, String expectedStatus) throws Exception {
		mvc.perform(get("/api/tasks/{id}", taskId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(expectedStatus));
	}

	private void transition(String orderId, String nextStatus, Integer receivedQuantity, String operatorCode, String expectedStatus) throws Exception {
		String quantity = receivedQuantity == null ? "null" : receivedQuantity.toString();
		mvc.perform(post("/api/outsourcing/orders/{id}/milestones", orderId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"nextStatus":"%s","receivedQuantity":%s,"operatorCode":"%s"}
					""".formatted(nextStatus, quantity, operatorCode)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(expectedStatus));
	}

	private String postAndRead(String path, String json, String jsonPath) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
	}
}
