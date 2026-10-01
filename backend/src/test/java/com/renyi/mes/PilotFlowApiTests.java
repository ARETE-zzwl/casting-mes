package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasSize;

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
class PilotFlowApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void completesOrderToProductionReportFlowWithIdempotencyAndQuantityProtection() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String productId = postAndReadId("/api/products", """
			{
			  "code": "P-%s",
			  "name": "泵体",
			  "routeType": "MID_TEMP_WAX",
			  "routeVersion": "V1"
			}
			""".formatted(suffix));
		String customerId = postAndReadId("/api/customers", """
			{
			  "code": "C-%s",
			  "name": "试点客户"
			}
			""".formatted(suffix));
		String orderId = postAndReadId("/api/orders", """
			{
			  "orderNo": "SO-%s",
			  "customerId": "%s",
			  "priority": "NORMAL",
			  "lines": [
			    {
			      "productId": "%s",
			      "quantity": 10,
			      "unit": "PCS"
			    }
			  ]
			}
			""".formatted(suffix, customerId, productId));

		OrderReviewTestSupport.releaseAndLaunchAfterRequiredReviews(mvc, orderId);
		mvc.perform(get("/api/work-orders").param("orderId", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].taskCount").value(11));

		MvcResult taskList = mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value("READY"))
			.andReturn();
		String taskId = JsonPath.read(taskList.getResponse().getContentAsString(), "$[0].id");

		String moldAssetId = postAndReadId("/api/resources", """
			{"assetCode":"PILOT-MOLD-%s","assetName":"Pilot mold","assetType":"MOLD","locationCode":"MOLD-01"}
			""".formatted(suffix));
		mvc.perform(post("/api/tasks/{taskId}/wax-dispatch", taskId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"moldAssetId":"%s","warehouseCode":"MOLD-01","warehouseOperatorCode":"M001","workerCode":"W001","supervisorCode":"S001"}
					""".formatted(moldAssetId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ASSIGNED"))
			.andExpect(jsonPath("$.assignedTo").value("W001"));

		mvc.perform(post("/api/tasks/{taskId}/start", taskId)
				.header("X-Operator-Code", "W001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("IN_PROGRESS"));

		String firstOperationId = UUID.randomUUID().toString();
		String firstReport = """
			{
			  "operationId": "%s",
			  "goodQuantity": 6,
			  "scrapQuantity": 0
			}
			""".formatted(firstOperationId);

		mvc.perform(post("/api/tasks/{taskId}/reports", taskId)
				.header("X-Operator-Code", "W001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(firstReport))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.duplicate").value(false))
			.andExpect(jsonPath("$.report.taskGoodTotal").value(6));

		mvc.perform(post("/api/tasks/{taskId}/reports", taskId)
				.header("X-Operator-Code", "W001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(firstReport))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.duplicate").value(true))
			.andExpect(jsonPath("$.report.taskGoodTotal").value(6));

		mvc.perform(post("/api/tasks/{taskId}/reports", taskId)
				.header("X-Operator-Code", "W001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "operationId": "%s",
					  "goodQuantity": 5,
					  "scrapQuantity": 0
					}
					""".formatted(UUID.randomUUID())))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("REPORT_QUANTITY_EXCEEDED"));

		mvc.perform(post("/api/tasks/{taskId}/reports", taskId)
				.header("X-Operator-Code", "W001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "operationId": "%s",
					  "goodQuantity": 3,
					  "scrapQuantity": 1
					}
					""".formatted(UUID.randomUUID())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.report.taskGoodTotal").value(9))
			.andExpect(jsonPath("$.report.taskScrapTotal").value(1))
			.andExpect(jsonPath("$.report.taskStatus").value("COMPLETED"));

		mvc.perform(get("/api/tasks/{taskId}/reports", taskId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2));

		mvc.perform(get("/api/trace/orders/{orderId}", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.order.orderNo").value("SO-" + suffix.toUpperCase()))
			.andExpect(jsonPath("$.workOrders[0].tasks.length()").value(11))
			.andExpect(jsonPath("$.timeline[?(@.type == 'PRODUCTION_REPORTED')]").value(hasSize(2)));
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
