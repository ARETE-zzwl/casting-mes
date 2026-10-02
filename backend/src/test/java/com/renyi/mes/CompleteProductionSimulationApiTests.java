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
class CompleteProductionSimulationApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void confirmsIntegerProductionQuantityWhenDispatchingFirstWaxTask() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String productId = create("/api/products", "{\"code\":\"MARGIN-" + suffix + "\",\"name\":\"Margin Casting\",\"routeType\":\"MID_TEMP_WAX\",\"routeVersion\":\"V1\"}");
		String customerId = create("/api/customers", "{\"code\":\"MARGIN-C-" + suffix + "\",\"name\":\"Margin Customer\"}");
		String orderId = create("/api/orders", "{\"orderNo\":\"MARGIN-SO-" + suffix + "\",\"customerId\":\"" + customerId + "\",\"priority\":\"NORMAL\",\"lines\":[{\"productId\":\"" + productId + "\",\"quantity\":12,\"unit\":\"PCS\"}]} ");
		OrderReviewTestSupport.submit(mvc, orderId);
		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"engineerCode\":\"E001\",\"processCardVersion\":\"MARGIN-V1\",\"engineeringParameters\":\"投产余量测试\"}"))
			.andExpect(status().isOk());
		reviewAndRelease(orderId);

		String injection = task(orderId, "WAX_INJECTION");
		String moldAssetId = create("/api/resources", "{\"assetCode\":\"MARGIN-MOLD-" + suffix + "\",\"assetName\":\"Margin Mold\",\"assetType\":\"MOLD\"}");
		String dispatch = "{\"moldAssetId\":\"" + moldAssetId + "\",\"warehouseCode\":\"MOLD-01\",\"warehouseOperatorCode\":\"M001\",\"workerCode\":\"W001\",\"reportingMode\":\"SELF_REPORTED_QUANTITY\",\"settlementUnit\":\"PCS\",\"compensationMode\":\"PIECE_PCS\",\"supervisorCode\":\"S001\"}";
		launchBatchForTask(injection, "13");
		mvc.perform(post("/api/tasks/{id}/wax-dispatch", injection).contentType(MediaType.APPLICATION_JSON).content(dispatch))
			.andExpect(status().isOk()).andExpect(jsonPath("$.plannedQuantity").value(13));

		String secondOrderId = create("/api/orders", "{\"orderNo\":\"MARGIN-SO2-" + suffix + "\",\"customerId\":\"" + customerId + "\",\"priority\":\"NORMAL\",\"lines\":[{\"productId\":\"" + productId + "\",\"quantity\":12,\"unit\":\"PCS\"}]} ");
		OrderReviewTestSupport.submit(mvc, secondOrderId);
		mvc.perform(post("/api/orders/{id}/engineering-confirmation", secondOrderId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"engineerCode\":\"E001\",\"processCardVersion\":\"MARGIN-V1\",\"engineeringParameters\":\"投产余量测试\"}"))
			.andExpect(status().isOk());
		reviewAndRelease(secondOrderId);
		String secondInjection = task(secondOrderId, "WAX_INJECTION");
		mvc.perform(post("/api/batches/{batchId}/launch", batchIdForTask(secondInjection)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"productionQuantity\":12.5,\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("PRODUCTION_QUANTITY_INTEGER_REQUIRED"));
	}

	@Test
	void reusesAnOrderLineMoldAcrossSequentialProductionBatches() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String productId = create("/api/products", "{\"code\":\"BATCH-MOLD-" + suffix + "\",\"name\":\"Batch Mold Casting\",\"routeType\":\"MID_TEMP_WAX\",\"routeVersion\":\"V1\"}");
		String customerId = create("/api/customers", "{\"code\":\"BATCH-MOLD-C-" + suffix + "\",\"name\":\"Batch Mold Customer\"}");
		String orderId = create("/api/orders", "{\"orderNo\":\"BATCH-MOLD-SO-" + suffix + "\",\"customerId\":\"" + customerId + "\",\"priority\":\"NORMAL\",\"lines\":[{\"productId\":\"" + productId + "\",\"quantity\":12,\"unit\":\"PCS\"}]}");
		OrderReviewTestSupport.submit(mvc, orderId);
		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"engineerCode\":\"E001\",\"processCardVersion\":\"BATCH-MOLD-V1\",\"engineeringParameters\":\"Batch mold reuse test\"}"))
			.andExpect(status().isOk());
		reviewAndRelease(orderId);

		String workOrderId = JsonPath.read(mvc.perform(get("/api/work-orders").param("orderId", orderId)).andReturn()
			.getResponse().getContentAsString(), "$[0].id");
		mvc.perform(post("/api/work-orders/{id}/batches", workOrderId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"quantities\":[6,6],\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

		List<String> injections = tasks(orderId, "WAX_INJECTION");
		String firstInjection = injections.getFirst();
		String secondInjection = injections.get(1);
		String moldAssetId = create("/api/resources", "{\"assetCode\":\"BATCH-MOLD-ASSET-" + suffix + "\",\"assetName\":\"Batch Mold\",\"assetType\":\"MOLD\"}");
		String dispatch = "{\"moldAssetId\":\"" + moldAssetId + "\",\"warehouseCode\":\"MOLD-01\",\"warehouseOperatorCode\":\"M001\",\"workerCode\":\"W001\",\"reportingMode\":\"SELF_REPORTED_QUANTITY\",\"settlementUnit\":\"PCS\",\"compensationMode\":\"PIECE_PCS\",\"supervisorCode\":\"S001\"}";

		launchBatchForTask(firstInjection, "6");
		launchBatchForTask(secondInjection, "6");
		mvc.perform(post("/api/tasks/{id}/wax-dispatch", firstInjection).contentType(MediaType.APPLICATION_JSON).content(dispatch))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSIGNED"));
		mvc.perform(post("/api/tasks/{id}/start", firstInjection).header("X-Operator-Code", "W001")).andExpect(status().isOk());
		report(firstInjection, "W001", 6, 0);
		mvc.perform(post("/api/tasks/{id}/mold-return", firstInjection).contentType(MediaType.APPLICATION_JSON)
				.content("{\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isOk());

		mvc.perform(post("/api/tasks/{id}/wax-dispatch", secondInjection).contentType(MediaType.APPLICATION_JSON).content(dispatch))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSIGNED"));
	}

	@Test
	void releasesQualifiedPartialOutputIntoALeadingBatchWithoutDuplicatingTheOriginalSuccessor() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String productId = create("/api/products", "{\"code\":\"LEAD-" + suffix + "\",\"name\":\"Leading Batch Casting\",\"routeType\":\"MID_TEMP_WAX\",\"routeVersion\":\"V1\"}");
		String customerId = create("/api/customers", "{\"code\":\"LEAD-C-" + suffix + "\",\"name\":\"Leading Batch Customer\"}");
		String orderId = create("/api/orders", "{\"orderNo\":\"LEAD-SO-" + suffix + "\",\"customerId\":\"" + customerId + "\",\"priority\":\"NORMAL\",\"lines\":[{\"productId\":\"" + productId + "\",\"quantity\":12,\"unit\":\"PCS\"}]}");
		OrderReviewTestSupport.submit(mvc, orderId);
		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"engineerCode\":\"E001\",\"processCardVersion\":\"LEAD-V1\",\"engineeringParameters\":\"Partial flow test\"}"))
			.andExpect(status().isOk());
		reviewAndRelease(orderId);

		String injection = task(orderId, "WAX_INJECTION");
		String moldAssetId = create("/api/resources", "{\"assetCode\":\"LEAD-MOLD-" + suffix + "\",\"assetName\":\"Leading Batch Mold\",\"assetType\":\"MOLD\"}");
		String dispatch = "{\"moldAssetId\":\"" + moldAssetId + "\",\"warehouseCode\":\"MOLD-01\",\"warehouseOperatorCode\":\"M001\",\"workerCode\":\"W001\",\"reportingMode\":\"SELF_REPORTED_QUANTITY\",\"settlementUnit\":\"PCS\",\"compensationMode\":\"PIECE_PCS\",\"supervisorCode\":\"S001\"}";
		launchBatchForTask(injection, "12");
		mvc.perform(post("/api/tasks/{id}/wax-dispatch", injection).contentType(MediaType.APPLICATION_JSON).content(dispatch))
			.andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/start", injection).header("X-Operator-Code", "W001")).andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/reports", injection).header("X-Operator-Code", "W001")
				.contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\"" + UUID.randomUUID()
					+ "\",\"goodQuantity\":5,\"scrapQuantity\":0,\"photoUrl\":\"/uploads/leading-wax.jpg\"}"))
			.andExpect(status().isCreated());

		MvcResult leadingFlow = mvc.perform(post("/api/tasks/{id}/partial-flow", injection).contentType(MediaType.APPLICATION_JSON)
				.content("{\"quantity\":5,\"releasedBy\":\"W001\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quantity").value(5))
			.andExpect(jsonPath("$.firstTask.operationCode").value("WAX_REPAIR"))
			.andExpect(jsonPath("$.firstTask.sequenceNo").value(1))
			.andExpect(jsonPath("$.firstTask.status").value("READY"))
			.andExpect(jsonPath("$.firstTask.plannedQuantity").value(5)).andReturn();
		String leadingRepair = JsonPath.read(leadingFlow.getResponse().getContentAsString(), "$.firstTask.id");
		mvc.perform(get("/api/tasks/{id}/reports/upstream", leadingRepair))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sourceTaskId").value(injection))
			.andExpect(jsonPath("$.sourceOperationCode").value("WAX_INJECTION"))
			.andExpect(jsonPath("$.reports[0].photoUrl").value("/uploads/leading-wax.jpg"));
		mvc.perform(post("/api/tasks/{id}/assignment", leadingRepair).contentType(MediaType.APPLICATION_JSON)
				.content("{\"workerCode\":\"WR01\",\"reportingMode\":\"SELF_REPORTED_QUANTITY\",\"compensationMode\":\"PIECE_PCS\",\"settlementUnit\":\"PCS\",\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/handoff-receipt", leadingRepair).header("X-Operator-Code", "WR01")
				.contentType(MediaType.APPLICATION_JSON).content("{\"receivedQuantity\":5}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MATCHED"));
		mvc.perform(post("/api/tasks/{id}/start", leadingRepair).header("X-Operator-Code", "WR01")).andExpect(status().isOk());
		report(leadingRepair, "WR01", 4, 0);
		mvc.perform(post("/api/tasks/{id}/partial-flow", leadingRepair).contentType(MediaType.APPLICATION_JSON)
				.content("{\"quantity\":4,\"releasedBy\":\"WR01\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.firstTask.operationCode").value("TREE_ASSEMBLY"))
			.andExpect(jsonPath("$.firstTask.sequenceNo").value(1))
			.andExpect(jsonPath("$.firstTask.status").value("READY"));
		mvc.perform(post("/api/tasks/{id}/partial-flow", injection).contentType(MediaType.APPLICATION_JSON)
				.content("{\"quantity\":1,\"releasedBy\":\"W001\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PARTIAL_FLOW_QUANTITY_EXCEEDED"));

		report(injection, "W001", 7, 0);
		String taskBody = mvc.perform(get("/api/tasks").param("orderId", orderId)).andReturn().getResponse().getContentAsString();
		List<Number> repairQuantities = JsonPath.read(taskBody, "$[?(@.operationCode == 'WAX_REPAIR')].plannedQuantity");
		org.junit.jupiter.api.Assertions.assertTrue(repairQuantities.stream().anyMatch(quantity -> quantity.doubleValue() == 5d));
		org.junit.jupiter.api.Assertions.assertTrue(repairQuantities.stream().anyMatch(quantity -> quantity.doubleValue() == 7d));
	}

	@Test
	void completesMidTemperatureWaxOrderAcrossSupervisorWorkersHandoffsAndPayrollModes() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String productId = create("/api/products", "{\"code\":\"SIM-" + suffix + "\",\"name\":\"Simulation Casting\",\"routeType\":\"MID_TEMP_WAX\",\"routeVersion\":\"V1\"}");
		String customerId = create("/api/customers", "{\"code\":\"SIM-C-" + suffix + "\",\"name\":\"Simulation Customer\"}");
		String orderId = create("/api/orders", "{\"orderNo\":\"SIM-SO-" + suffix + "\",\"customerId\":\"" + customerId + "\",\"priority\":\"NORMAL\",\"lines\":[{\"productId\":\"" + productId + "\",\"quantity\":12,\"unit\":\"PCS\"}]}");
		OrderReviewTestSupport.submit(mvc, orderId);
		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"engineerCode\":\"E001\",\"processCardVersion\":\"SIM-V1\",\"engineeringParameters\":\"射蜡温度68C；材质304；浇筑温度1580C\"}"))
			.andExpect(status().isOk());
		reviewAndRelease(orderId);

		String injection = task(orderId, "WAX_INJECTION");
		launchBatchForTask(injection, "12");
		mvc.perform(post("/api/tasks/{id}/assignment", injection).contentType(MediaType.APPLICATION_JSON)
				.content("{\"workerCode\":\"W001\",\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MOLD_NOT_ISSUED"));
		String moldAssetId = create("/api/resources", "{\"assetCode\":\"SIM-MOLD-" + suffix + "\",\"assetName\":\"Simulation Mold\",\"assetType\":\"MOLD\"}");
		mvc.perform(post("/api/tasks/{id}/wax-dispatch", injection).contentType(MediaType.APPLICATION_JSON)
				.content("{\"moldAssetId\":\"" + moldAssetId + "\",\"warehouseCode\":\"MOLD-01\",\"warehouseOperatorCode\":\"M001\",\"workerCode\":\"W001\",\"reportingMode\":\"SELF_REPORTED_QUANTITY\",\"settlementUnit\":\"PCS\",\"compensationMode\":\"PIECE_PCS\",\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSIGNED"));
		mvc.perform(post("/api/tasks/{id}/start", injection).header("X-Operator-Code", "W001")).andExpect(status().isOk());
		report(injection, "W001", 12, 0);

		String repair = task(orderId, "WAX_REPAIR");
		assignStart(repair, "WR01", "HANDOFF_TO_TREE", "PIECE_PCS", "PCS", "S001");
		mvc.perform(post("/api/tasks/{id}/handoff-without-count", repair).header("X-Operator-Code", "WR01")
				.contentType(MediaType.APPLICATION_JSON).content("{\"receivedQuantity\":11,\"receivedBy\":\"TA01\",\"exceptionReason\":\"现场清点少一件，待质量复核\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.handoffStatus").value("EXCEPTION"));
		mvc.perform(get("/api/production-alerts").param("recipientCode", "S001"))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].shortageQuantity").value(1));
		mvc.perform(get("/api/notifications").param("recipientCode", "S001"))
			.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.title == '工序交接异常待处理')]").isNotEmpty());
		mvc.perform(get("/api/production-alerts").param("recipientCode", "GM001"))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].routeType").value("MID_TEMP_WAX"));

		String tree = task(orderId, "TREE_ASSEMBLY");
		assignStart(tree, "TA01", "TREE_COUNT", "PIECE_TREE", "TREE", "S001");
		mvc.perform(post("/api/tasks/{id}/handoff-receipt", tree).header("X-Operator-Code", "TA01")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"receivedQuantity\":11,\"exceptionType\":\"MISSING_ITEMS\",\"exceptionReason\":\"接收时发现少一件，待质量复核\",\"photoUrl\":\"/uploads/handoff-receipt.jpg\",\"deviceCode\":\"MOBILE-T001\",\"workstationCode\":\"TREE-01\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXCEPTION"))
			.andExpect(jsonPath("$.exceptionType").value("MISSING_ITEMS"));
		mvc.perform(post("/api/tasks/{id}/tree-reports", tree).header("X-Operator-Code", "TA01").contentType(MediaType.APPLICATION_JSON)
				.content("{\"operationId\":\"" + UUID.randomUUID() + "\",\"treeCount\":1,\"piecesPerTree\":11,\"scrapQuantity\":0}"))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.report.taskGoodTotal").value(11));

		String shell = task(orderId, "SHELL_BUILDING");
		mvc.perform(post("/api/tasks/{id}/shell-line", shell).contentType(MediaType.APPLICATION_JSON)
				.content("{\"shellLineMode\":\"AUTOMATED\",\"supervisorCode\":\"S002\"}"))
			.andExpect(status().isOk());
		assignStart(shell, "SH01", "HANDOFF_TO_NEXT", "HOURLY", "PCS", "S002");
		mvc.perform(post("/api/labor/shell-records").contentType(MediaType.APPLICATION_JSON).content("{\"taskId\":\"" + shell + "\",\"operationId\":\"" + UUID.randomUUID() + "\",\"method\":\"AUTOMATED\",\"layerCount\":5,\"dryingMinutes\":180,\"quantity\":11,\"operatorCode\":\"SH01\",\"note\":\"手工两层后转自动线\"}"))
			.andExpect(status().isCreated());
		handoff(shell, "SH01", 10, "DW01");
		time(shell, "SH01", 7.5, "S002", "SUPERVISOR");

		String dewax = task(orderId, "DEWAX");
		assignStart(dewax, "DW01", "HANDOFF_TO_NEXT", "HOURLY", "PCS", "S003");
		handoff(dewax, "DW01", 10, "PO01");
		String sheetId = create("/api/labor/paper-sheets", "{\"taskId\":\"" + dewax + "\",\"workerCode\":\"DW01\",\"reportKind\":\"HOURS\",\"hours\":4.0,\"note\":\"纸质班组工时单\",\"enteredBy\":\"S003\"}");
		mvc.perform(post("/api/labor/paper-sheets/{id}/approval", sheetId).contentType(MediaType.APPLICATION_JSON).content("{\"reviewerCode\":\"S003\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

		for (String code : List.of("POURING", "KNOCKOUT", "CUTTING", "SEMI_FINISHED_COUNT")) {
			String id = task(orderId, code);
			String worker = switch (code) {
				case "POURING" -> "PO01";
				case "KNOCKOUT" -> "KO01";
				case "CUTTING" -> "CT01";
				default -> "FN01";
			};
			String supervisor = "SEMI_FINISHED_COUNT".equals(code) ? "FS001" : "S003";
			assignStart(id, worker, "HANDOFF_TO_NEXT", "HOURLY", "PCS", supervisor);
			if ("SEMI_FINISHED_COUNT".equals(code)) {
				create("/api/post-treatment/decisions", "{\"sourceTaskId\":\"" + id + "\",\"destination\":\"IN_HOUSE\",\"processSummary\":\"喷砂、打磨\",\"decidedBy\":\"FS001\"}");
			}
			handoff(id, worker, 10, worker);
			time(id, worker, 2.0, supervisor, "SUPERVISOR");
		}

		String finishing = task(orderId, "OPTIONAL_FINISHING");
		assignStart(finishing, "FN01", "SELF_REPORTED_QUANTITY", null, null, "FS001");
		report(finishing, "FN01", 10, 0);

		String finalCount = task(orderId, "FINAL_COUNT");
		assignStart(finalCount, "FN01", "SELF_REPORTED_QUANTITY", "HANDOFF_ONLY", "PCS", "S003");
		supervisorReport(finalCount, 10, 0);
		mvc.perform(get("/api/trace/orders/{id}", orderId)).andExpect(status().isOk()).andExpect(jsonPath("$.workOrders[0].tasks[10].task.status").value("COMPLETED"));
	}

	private void assignStart(String id, String worker, String mode, String compensation, String unit, String supervisorCode) throws Exception {
		String assignment = "{\"workerCode\":\"" + worker + "\",\"reportingMode\":\"" + mode + "\""
			+ (compensation == null ? "" : ",\"compensationMode\":\"" + compensation + "\"")
			+ (unit == null ? "" : ",\"settlementUnit\":\"" + unit + "\"")
			+ ",\"supervisorCode\":\"" + supervisorCode + "\"}";
		mvc.perform(post("/api/tasks/{id}/assignment", id).contentType(MediaType.APPLICATION_JSON).content(assignment))
			.andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/start", id).header("X-Operator-Code", worker)).andExpect(status().isOk());
	}

	private void reviewAndRelease(String orderId) throws Exception {
		mvc.perform(post("/api/orders/{id}/customer-manager-review", orderId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"managerCode\":\"CM001\"}"))
			.andExpect(status().isOk());
	}

	private void report(String id, String worker, int good, int scrap) throws Exception {
		mvc.perform(post("/api/tasks/{id}/reports", id).header("X-Operator-Code", worker).contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\"" + UUID.randomUUID() + "\",\"goodQuantity\":" + good + ",\"scrapQuantity\":" + scrap + "}"))
			.andExpect(status().isCreated());
	}

	private void supervisorReport(String id, int good, int scrap) throws Exception {
		mvc.perform(post("/api/tasks/{id}/supervisor-reports", id).contentType(MediaType.APPLICATION_JSON).content("{\"operationId\":\"" + UUID.randomUUID() + "\",\"goodQuantity\":" + good + ",\"scrapQuantity\":" + scrap + ",\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isCreated());
	}

	private void handoff(String id, String worker, int received, String receiver) throws Exception {
		mvc.perform(post("/api/tasks/{id}/handoff-without-count", id).header("X-Operator-Code", worker).contentType(MediaType.APPLICATION_JSON).content("{\"receivedQuantity\":" + received + ",\"receivedBy\":\"" + receiver + "\"}"))
			.andExpect(status().isOk());
	}

	private void time(String id, String worker, double hours, String recorder, String source) throws Exception {
		mvc.perform(post("/api/labor/time-entries").contentType(MediaType.APPLICATION_JSON).content("{\"taskId\":\"" + id + "\",\"workerCode\":\"" + worker + "\",\"hours\":" + hours + ",\"recordedBy\":\"" + recorder + "\",\"source\":\"" + source + "\"}"))
			.andExpect(status().isCreated());
	}

	private String task(String orderId, String code) throws Exception {
		return tasks(orderId, code).getFirst();
	}

	private List<String> tasks(String orderId, String code) throws Exception {
		String body = mvc.perform(get("/api/tasks").param("orderId", orderId)).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[?(@.operationCode == '" + code + "')].id");
	}

	private void launchBatchForTask(String taskId, String productionQuantity) throws Exception {
		mvc.perform(post("/api/batches/{batchId}/launch", batchIdForTask(taskId)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"productionQuantity\":" + productionQuantity + ",\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isOk());
	}

	private String batchIdForTask(String taskId) throws Exception {
		MvcResult result = mvc.perform(get("/api/tasks/{id}", taskId)).andExpect(status().isOk()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.batchId");
	}

	private String create(String path, String json) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
