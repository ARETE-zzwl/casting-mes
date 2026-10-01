package com.renyi.mes;

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
class TreeAssemblyReportingApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void defersBulkRepairCountingUntilTreeAssemblyAndSettlesByTree() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String productId = postAndReadId("/api/products", """
			{"code":"TREE-%s","name":"Small Bulk Part","routeType":"MID_TEMP_WAX","routeVersion":"V1"}
			""".formatted(suffix));
		String customerId = postAndReadId("/api/customers", """
			{"code":"TREE-C-%s","name":"Tree Customer"}
			""".formatted(suffix));
		String orderId = postAndReadId("/api/orders", """
			{"orderNo":"TREE-SO-%s","customerId":"%s","priority":"NORMAL","lines":[{"productId":"%s","quantity":12,"unit":"PCS"}]}
		""".formatted(suffix, customerId, productId));
		OrderReviewTestSupport.releaseAfterRequiredReviews(mvc, orderId);
		launchBatch(orderId, 12);

		String injection = taskId(orderId, "WAX_INJECTION");
		completeAnyPredecessors(orderId, injection);
		assignWaxAndReport(injection, suffix, 12, 0);

		String repair = taskId(orderId, "WAX_REPAIR");
		assignAndStart(repair, "HANDOFF_TO_TREE", "");
		mvc.perform(post("/api/tasks/{id}/handoff-without-count", repair)
				.header("X-Operator-Code", "W001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.countingDeferred").value(true));

		String tree = taskId(orderId, "TREE_ASSEMBLY");
		assignAndStart(tree, "TREE_COUNT", "TREE");
		mvc.perform(post("/api/tasks/{id}/tree-reports", tree)
				.header("X-Operator-Code", "W001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"operationId\":\"%s\",\"treeCount\":3,\"piecesPerTree\":4,\"scrapQuantity\":0}".formatted(UUID.randomUUID())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.report.taskGoodTotal").value(12))
			.andExpect(jsonPath("$.treeCount").value(3))
			.andExpect(jsonPath("$.piecesPerTree").value(4));

		mvc.perform(post("/api/piecework/rates")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"supervisorCode\":\"S001\",\"operationCode\":\"TREE_ASSEMBLY\",\"operationName\":\"Tree Assembly\",\"routeType\":\"MID_TEMP_WAX\",\"version\":\"TREE-V1\",\"settlementUnit\":\"TREE\",\"unitRate\":2.5,\"effectiveFrom\":\"2026-01-01\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.settlementUnit").value("TREE"));

		mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"operationId\":\"%s\",\"taskId\":\"%s\",\"workerCode\":\"W001\",\"quantity\":3}".formatted(UUID.randomUUID(), tree)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.settlementUnit").value("TREE"))
			.andExpect(jsonPath("$.entry.amount").value(7.5));
	}

	private void assignStartAndReport(String taskId, String reportingMode, String settlementUnit, int good, int scrap) throws Exception {
		assignAndStart(taskId, reportingMode, settlementUnit);
		mvc.perform(post("/api/tasks/{id}/reports", taskId)
				.header("X-Operator-Code", "W001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"operationId\":\"%s\",\"goodQuantity\":%s,\"scrapQuantity\":%s}".formatted(UUID.randomUUID(), good, scrap)))
			.andExpect(status().isCreated());
	}

	private void assignWaxAndReport(String taskId, String suffix, int good, int scrap) throws Exception {
		String moldAssetId = postAndReadId("/api/resources", """
			{"assetCode":"TREE-MOLD-%s","assetName":"Tree test mold","assetType":"MOLD","locationCode":"MOLD-01"}
			""".formatted(suffix));
		mvc.perform(post("/api/tasks/{id}/wax-dispatch", taskId).contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"moldAssetId":"%s","warehouseCode":"MOLD-01","warehouseOperatorCode":"M001","workerCode":"W001","supervisorCode":"S001"}
					""".formatted(moldAssetId)))
			.andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/start", taskId).header("X-Operator-Code", "W001"))
			.andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/reports", taskId)
				.header("X-Operator-Code", "W001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"operationId\":\"%s\",\"goodQuantity\":%s,\"scrapQuantity\":%s}".formatted(UUID.randomUUID(), good, scrap)))
			.andExpect(status().isCreated());
	}

	private void assignAndStart(String taskId, String reportingMode, String settlementUnit) throws Exception {
		String body = settlementUnit.isBlank()
			? "{\"workerCode\":\"W001\",\"reportingMode\":\"%s\"}".formatted(reportingMode)
			: "{\"workerCode\":\"W001\",\"reportingMode\":\"%s\",\"settlementUnit\":\"%s\"}".formatted(reportingMode, settlementUnit);
		mvc.perform(post("/api/tasks/{id}/assignment", taskId).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/start", taskId).header("X-Operator-Code", "W001"))
			.andExpect(status().isOk());
	}

	private void completeAnyPredecessors(String orderId, String taskId) throws Exception {
		String target = getTask(taskId).read("$.sequenceNo").toString();
		List<String> predecessors = JsonPath.read(tasks(orderId), "$[?(@.sequenceNo < " + target + ")].id");
		for (String candidate : predecessors) {
			assignStartAndReport(candidate.toString(), "SELF_REPORTED_QUANTITY", "", 12, 0);
		}
	}

	private String taskId(String orderId, String operationCode) throws Exception {
		List<String> ids = JsonPath.read(tasks(orderId), "$[?(@.operationCode == '" + operationCode + "')].id");
		return ids.getFirst();
	}

	private void launchBatch(String orderId, int productionQuantity) throws Exception {
		MvcResult workOrders = mvc.perform(get("/api/work-orders").param("orderId", orderId))
			.andExpect(status().isOk()).andReturn();
		String batchId = JsonPath.read(workOrders.getResponse().getContentAsString(), "$[0].batchId");
		mvc.perform(post("/api/batches/{batchId}/launch", batchId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"productionQuantity\":%s,\"supervisorCode\":\"S001\"}".formatted(productionQuantity)))
			.andExpect(status().isOk());
	}

	private com.jayway.jsonpath.DocumentContext getTask(String taskId) throws Exception {
		MvcResult result = mvc.perform(get("/api/tasks/{id}", taskId)).andExpect(status().isOk()).andReturn();
		return JsonPath.parse(result.getResponse().getContentAsString());
	}

	private String tasks(String orderId) throws Exception {
		return mvc.perform(get("/api/tasks").param("orderId", orderId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private String postAndReadId(String path, String json) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
