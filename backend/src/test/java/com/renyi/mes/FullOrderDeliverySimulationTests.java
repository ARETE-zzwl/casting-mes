package com.renyi.mes;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:full-delivery-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FullOrderDeliverySimulationTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void providesSeededUsersWithConfigurableMultipleRolesAndPermissions() throws Exception {
		mvc.perform(get("/api/access/users"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.employeeCode == 'A001')].roles[0]").value("SYSTEM_ADMIN"))
			.andExpect(jsonPath("$[?(@.employeeCode == 'W002')].roles[0]").value("OPERATOR"))
			.andExpect(jsonPath("$[?(@.employeeCode == 'W002')].roles[1]").value("FINISHING_OPERATOR"));

		mvc.perform(post("/api/access/users/W001/roles")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"roleCodes":["OPERATOR","QUALITY_INSPECTOR"]}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roles", hasSize(2)))
			.andExpect(jsonPath("$.permissions", hasItem("TASK_EXECUTE")))
			.andExpect(jsonPath("$.permissions", hasItem("QUALITY_MANAGE")));

		mvc.perform(get("/api/access/roles"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.code == 'SYSTEM_ADMIN')].permissions[0]")
				.value("ACCESS_MANAGE"));
	}

	@Test
	void simulatesCustomerOrderThroughAllOperationsToDeliveredFinishedGoods() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String productId = postAndRead("/api/products", """
			{"code":"FP-%s","name":"完整流程泵体","routeType":"MID_TEMP_WAX","routeVersion":"V1"}
			""".formatted(suffix), "$.id");
		String customerId = postAndRead("/api/customers", """
			{"code":"FC-%s","name":"完整流程客户"}
			""".formatted(suffix), "$.id");
		String orderId = postAndRead("/api/orders", """
			{
			  "orderNo":"FD-%s",
			  "customerId":"%s",
			  "priority":"NORMAL",
			  "lines":[{"productId":"%s","quantity":10,"unit":"PCS"}]
			}
			""".formatted(suffix, customerId, productId), "$.id");
		OrderReviewTestSupport.releaseAndLaunchAfterRequiredReviews(mvc, orderId);

		MvcResult tasksResult = mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(11)))
			.andReturn();
		List<Map<String, Object>> tasks = JsonPath.read(
			tasksResult.getResponse().getContentAsString(), "$");
		tasks.sort(Comparator.comparingInt(task -> (Integer) task.get("sequenceNo")));
		String moldAssetId = postAndRead("/api/resources", """
			{"assetCode":"FD-MOLD-%s","assetName":"Full delivery mold","assetType":"MOLD","locationCode":"MOLD-01"}
			""".formatted(suffix), "$.id");

		for (Map<String, Object> task : tasks) {
			String taskId = task.get("id").toString();
			String worker = ((Integer) task.get("sequenceNo")) <= 4 ? "W001" : "W002";
			if ("OPTIONAL_FINISHING".equals(task.get("operationCode"))) continue;
			if ("WAX_INJECTION".equals(task.get("operationCode"))) {
				mvc.perform(post("/api/tasks/{id}/wax-dispatch", taskId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"moldAssetId\":\"" + moldAssetId + "\",\"warehouseCode\":\"MOLD-01\",\"warehouseOperatorCode\":\"M001\",\"workerCode\":\"" + worker
							+ "\",\"reportingMode\":\"SELF_REPORTED_QUANTITY\",\"settlementUnit\":\"PCS\",\"compensationMode\":\"PIECE_PCS\",\"supervisorCode\":\"S001\"}"))
					.andExpect(status().isOk());
			}
			else {
				if ("SHELL_BUILDING".equals(task.get("operationCode"))) {
					mvc.perform(post("/api/tasks/{id}/shell-line", taskId)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"shellLineMode\":\"AUTOMATED\",\"supervisorCode\":\"S002\"}"))
						.andExpect(status().isOk());
				}
				mvc.perform(post("/api/tasks/{id}/assignment", taskId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"workerCode\":\"" + worker + "\"}"))
					.andExpect(status().isOk());
			}
			mvc.perform(post("/api/tasks/{id}/start", taskId)
					.header("X-Operator-Code", worker))
				.andExpect(status().isOk());
			if ("TREE_ASSEMBLY".equals(task.get("operationCode"))) {
				mvc.perform(post("/api/tasks/{id}/tree-reports", taskId)
						.header("X-Operator-Code", worker)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
							{"operationId":"%s","treeCount":1,"piecesPerTree":10,"scrapQuantity":0}
							""".formatted(UUID.randomUUID())))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.report.taskStatus").value("COMPLETED"));
			}
			else {
				mvc.perform(post("/api/tasks/{id}/reports", taskId)
						.header("X-Operator-Code", worker)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
							{"operationId":"%s","goodQuantity":10,"scrapQuantity":0}
							""".formatted(UUID.randomUUID())))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.report.taskStatus").value("COMPLETED"));
			}
		}

		Map<String, Object> finalTask = tasks.getLast();
		String finalTaskId = finalTask.get("id").toString();
		mvc.perform(get("/api/sops/{operationCode}", finalTask.get("operationCode")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.steps.length()").value(4))
			.andExpect(jsonPath("$.qualityPoints.length()").value(3));

		mvc.perform(get("/api/fulfillment/receipts").param("viewerCode", "G001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.taskId == '%s')].orderNo".formatted(finalTaskId)).value(("FD-" + suffix).toUpperCase()))
			.andExpect(jsonPath("$[?(@.taskId == '%s')].receivableQuantity".formatted(finalTaskId)).value(10.0));
		mvc.perform(post("/api/quality/inspections")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "operationId":"%s",
					  "taskId":"%s",
					  "inspectedQuantity":10,
					  "acceptedQuantity":8,
					  "rejectedQuantity":2,
					  "inspectorCode":"Q001"
					}
					""".formatted(UUID.randomUUID(), finalTaskId)))
			.andExpect(status().isCreated());
		mvc.perform(get("/api/fulfillment/receipts").param("viewerCode", "G001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.taskId == '%s')].receivableQuantity".formatted(finalTaskId)).value(8.0));
		mvc.perform(post("/api/fulfillment/lots")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"operationId":"%s","taskId":"%s","quantity":9,"warehouseCode":"FG","registeredBy":"G001"}
					""".formatted(UUID.randomUUID(), finalTaskId)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("FINISHED_GOODS_RECEIPT_QUANTITY_EXCEEDED"));

		String lotId = postAndRead("/api/fulfillment/lots", """
			{
			  "operationId":"%s",
			  "taskId":"%s",
			  "quantity":8,
			  "warehouseCode":"FG",
			  "registeredBy":"G001"
			}
			""".formatted(UUID.randomUUID(), finalTaskId), "$.lot.id");
		String deliveryId = postAndRead("/api/fulfillment/deliveries", """
			{
			  "orderId":"%s",
			  "lotId":"%s",
			  "quantity":8,
			  "recipientName":"客户收货组",
			  "deliveryAddress":"客户一号仓",
			  "createdBy":"G001"
			}
			""".formatted(orderId, lotId), "$.id");

		transition(deliveryId, "PICKED", "G001", null, null);
		transition(deliveryId, "SHIPPED", "G001", "顺丰工业物流", "SF" + suffix);
		transition(deliveryId, "DELIVERED", "G001", null, null);

		mvc.perform(get("/api/fulfillment/deliveries/{id}", deliveryId).param("viewerCode", "G001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DELIVERED"))
			.andExpect(jsonPath("$.quantity").value(8));
		mvc.perform(get("/api/trace/orders/{id}", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.timeline[?(@.type == 'FINISHED_GOODS_REGISTERED')]",
				hasSize(1)))
			.andExpect(jsonPath("$.timeline[?(@.type == 'DELIVERY_DELIVERED')]",
				hasSize(1)));
	}

	private void transition(String deliveryId, String nextStatus, String operatorCode,
			String carrier, String trackingNo) throws Exception {
		String body = """
			{
			  "nextStatus":"%s",
			  "operatorCode":"%s",
			  "carrier":%s,
			  "trackingNo":%s
			}
			""".formatted(nextStatus, operatorCode, jsonString(carrier), jsonString(trackingNo));
		mvc.perform(post("/api/fulfillment/deliveries/{id}/transitions", deliveryId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(nextStatus));
	}

	private String postAndRead(String path, String json, String jsonPath) throws Exception {
		MvcResult result = mvc.perform(post(path)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json))
			.andExpect(status().isCreated())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
	}

	private static String jsonString(String value) {
		return value == null ? "null" : "\"" + value + "\"";
	}
}
