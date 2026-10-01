package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

final class OrderReviewTestSupport {

	private OrderReviewTestSupport() {
	}

	static void submit(MockMvc mvc, String orderId) throws Exception {
		mvc.perform(post("/api/orders/{id}/submit", orderId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"editorCode\":\"FD01\"}"))
			.andExpect(status().isOk());
	}

	static void releaseAfterRequiredReviews(MockMvc mvc, String orderId) throws Exception {
		submitIfDraft(mvc, orderId);
		MvcResult order = mvc.perform(get("/api/orders/{id}", orderId))
			.andExpect(status().isOk())
			.andReturn();
		if (JsonPath.read(order.getResponse().getContentAsString(), "$.engineeringConfirmedAt") == null) {
			mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"engineerCode\":\"E001\",\"processCardVersion\":\"TEST-V1\",\"engineeringParameters\":\"Regression process parameters\"}"))
				.andExpect(status().isOk());
		}
		mvc.perform(post("/api/orders/{id}/customer-manager-review", orderId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"managerCode\":\"CM001\"}"))
			.andExpect(status().isOk());
	}

	private static void submitIfDraft(MockMvc mvc, String orderId) throws Exception {
		MvcResult order = mvc.perform(get("/api/orders/{id}", orderId))
			.andExpect(status().isOk())
			.andReturn();
		if ("DRAFT".equals(JsonPath.read(order.getResponse().getContentAsString(), "$.status"))) {
			submit(mvc, orderId);
		}
	}

	static void releaseAndLaunchAfterRequiredReviews(MockMvc mvc, String orderId) throws Exception {
		releaseAfterRequiredReviews(mvc, orderId);
		launchPendingBatches(mvc, orderId);
	}

	static void launchPendingBatches(MockMvc mvc, String orderId) throws Exception {
		MvcResult workOrders = mvc.perform(get("/api/work-orders").param("orderId", orderId))
			.andExpect(status().isOk())
			.andReturn();
		List<Map<String, Object>> rows = JsonPath.read(workOrders.getResponse().getContentAsString(), "$[?(@.batchStatus == 'PENDING_LAUNCH')]");
		for (Map<String, Object> row : rows) {
			mvc.perform(post("/api/batches/{batchId}/launch", row.get("batchId"))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"productionQuantity\":" + row.get("plannedQuantity") + ",\"supervisorCode\":\"GM001\"}"))
				.andExpect(status().isOk());
		}
	}
}
