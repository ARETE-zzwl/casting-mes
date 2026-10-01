package com.renyi.mes;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
class MoldBatchExclusivityApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void blocksOneMoldFromBeingDispatchedToTwoInProgressProductBatches() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		String customerId = create("/api/customers", "{\"code\":\"MX-C-" + suffix + "\",\"name\":\"Material Customer\"}");
		String firstProductId = create("/api/products", "{\"code\":\"MX-304-" + suffix + "\",\"name\":\"Valve Body\",\"routeType\":\"MID_TEMP_WAX\",\"routeVersion\":\"V1\",\"material\":\"304\"}");
		String secondProductId = create("/api/products", "{\"code\":\"MX-316-" + suffix + "\",\"name\":\"Valve Body\",\"routeType\":\"MID_TEMP_WAX\",\"routeVersion\":\"V1\",\"material\":\"316L\"}");
		String moldId = create("/api/resources", "{\"assetCode\":\"MX-MOLD-" + suffix + "\",\"assetName\":\"Shared Valve Mold\",\"assetType\":\"MOLD\"}");
		String orderId = create("/api/orders", """
			{"orderNo":"MX-SO-%s","customerId":"%s","priority":"NORMAL","lines":[
			 {"productId":"%s","quantity":10,"unit":"PCS"},{"productId":"%s","quantity":10,"unit":"PCS"}]}
			""".formatted(suffix, customerId, firstProductId, secondProductId));

		MvcResult order = mvc.perform(get("/api/orders/{id}", orderId)).andExpect(status().isOk()).andReturn();
		List<String> lineIds = JsonPath.read(order.getResponse().getContentAsString(), "$.lines[*].id");
		OrderReviewTestSupport.submit(mvc, orderId);
		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId).contentType(MediaType.APPLICATION_JSON).content("""
			{"engineerCode":"E001","lines":[
			 {"orderLineId":"%s","processCardVersion":"MX-304","engineeringParameters":"304 process"},
			 {"orderLineId":"%s","processCardVersion":"MX-316","engineeringParameters":"316L process"}]}
			""".formatted(lineIds.get(0), lineIds.get(1))))
			.andExpect(status().isOk());
		OrderReviewTestSupport.releaseAfterRequiredReviews(mvc, orderId);

		for (String lineId : lineIds) {
			mvc.perform(post("/api/factory/mold-requests/order-selections").contentType(MediaType.APPLICATION_JSON).content("""
				{"orderId":"%s","orderLineId":"%s","moldAssetId":"%s","selectedBy":"S001"}
				""".formatted(orderId, lineId, moldId))).andExpect(status().isOk());
		}
		launchAllBatches(orderId);
		MvcResult tasks = mvc.perform(get("/api/tasks").param("orderId", orderId)).andExpect(status().isOk()).andReturn();
		List<String> firstMaterialTasks = JsonPath.read(tasks.getResponse().getContentAsString(), "$[?(@.operationCode == 'WAX_INJECTION' && @.productMaterial == '304')].id");
		List<String> secondMaterialTasks = JsonPath.read(tasks.getResponse().getContentAsString(), "$[?(@.operationCode == 'WAX_INJECTION' && @.productMaterial == '316L')].id");

		mvc.perform(post("/api/tasks/{taskId}/wax-dispatch", firstMaterialTasks.getFirst()).contentType(MediaType.APPLICATION_JSON).content(dispatch(moldId, "WX01")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.productMaterial").value("304"));
		mvc.perform(post("/api/tasks/{taskId}/wax-dispatch", secondMaterialTasks.getFirst()).contentType(MediaType.APPLICATION_JSON).content(dispatch(moldId, "WX02")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("MOLD_ACTIVE_FOR_OTHER_BATCH"))
			.andExpect(jsonPath("$.error.message", containsString("304")));
	}

	private static String dispatch(String moldId, String workerCode) {
		return """
			{"moldAssetId":"%s","warehouseCode":"MOLD-01","warehouseOperatorCode":"M001","workerCode":"%s",
			"reportingMode":"SELF_REPORTED_QUANTITY","supervisorCode":"S001"}
			""".formatted(moldId, workerCode);
	}

	private void launchAllBatches(String orderId) throws Exception {
		MvcResult workOrders = mvc.perform(get("/api/work-orders").param("orderId", orderId))
			.andExpect(status().isOk()).andReturn();
		List<String> batchIds = JsonPath.read(workOrders.getResponse().getContentAsString(), "$[*].batchId");
		for (String batchId : batchIds) {
			mvc.perform(post("/api/batches/{batchId}/launch", batchId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"productionQuantity\":10,\"supervisorCode\":\"S001\"}"))
				.andExpect(status().isOk());
		}
	}

	private String create(String path, String content) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(content))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
