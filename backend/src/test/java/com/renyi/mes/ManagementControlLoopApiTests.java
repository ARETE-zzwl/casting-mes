package com.renyi.mes;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.sql.Timestamp;
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

@SpringBootTest(properties = "test.context=management-control")
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ManagementControlLoopApiTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void prefersTheProductSpecificRateOverTheGenericOperationFallback() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		TaskFixture task = completedTask(suffix);
		createPieceworkRate(task, "GENERIC-" + suffix, 1.25, LocalDate.now().minusDays(1));
		String productCode = jdbc.queryForObject("""
			select work_order.product_code from planning_task task
			join planning_batch batch on batch.id = task.batch_id
			join planning_work_order work_order on work_order.id = batch.work_order_id
			where task.id = ?
			""", String.class, UUID.fromString(task.id()));

		mvc.perform(post("/api/piecework/rates")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "supervisorCode": "S001",
					  "operationCode": "%s",
					  "operationName": "%s",
					  "routeType": "MID_TEMP_WAX",
					  "productCode": "%s",
					  "productName": "产品专属测试件",
					  "version": "PRODUCT-%s",
					  "unitRate": 2.5,
					  "effectiveFrom": "%s"
					}
					""".formatted(task.operationCode(), task.operationName(), productCode, suffix, LocalDate.now().minusDays(1))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.productCode").value(productCode));

		mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content(pieceworkEntryJson(UUID.randomUUID(), task.id(), "S001", 5)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.unitRate").value(2.5));
	}

	@Test
	void keepsTheCurrentPieceworkRateUsableWhenANextMonthRateIsPublished() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		TaskFixture task = completedTask(suffix);
		createPieceworkRate(task, "RATE-" + suffix + "-CURRENT", 1.25, LocalDate.now().minusDays(1));
		createPieceworkRate(task, "RATE-" + suffix + "-NEXT", 9.99, LocalDate.now().plusDays(7));

		mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"operationId":"%s","taskId":"%s","workerCode":"W001","recordedBy":"S001","quantity":10}
					""".formatted(UUID.randomUUID(), task.id())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.unitRate").value(1.25));
	}

	@Test
	void keepsTheSupervisorBackfillTraceAndRestrictsConfirmationToTheResponsibleScope() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		TaskFixture task = completedTask(suffix);
		createPieceworkRate(task, "TRACE-" + suffix, 1.5, LocalDate.now().minusDays(1));
		MvcResult result = mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content(pieceworkEntryJson(UUID.randomUUID(), task.id(), "S001", 10)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.recordedBy").value("S001"))
			.andReturn();
		String entryId = JsonPath.read(result.getResponse().getContentAsString(), "$.entry.id");

		mvc.perform(post("/api/piecework/entries/{id}/confirmation", entryId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"supervisorCode\":\"M001\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("PIECEWORK_CONFIRMATION_SCOPE_DENIED"));
		mvc.perform(post("/api/piecework/entries/{id}/confirmation", entryId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"supervisorCode\":\"PM01\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	@Test
	void rejectsPieceworkWhenTheTaskUsesAnHourlyCompensationRule() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		TaskFixture task = completedTask(suffix);
		jdbc.update("update planning_task set compensation_mode = 'HOURLY' where id = ?", UUID.fromString(task.id()));
		createPieceworkRate(task, "HOURLY-" + suffix, 1.5, LocalDate.now().minusDays(1));

		mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content(pieceworkEntryJson(UUID.randomUUID(), task.id(), "S001", 10)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PIECEWORK_COMPENSATION_MODE_INVALID"));
	}

	@Test
	void rejectsAnIdempotentRetryWhenTheRecorderChanges() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		TaskFixture task = completedTask(suffix);
		createPieceworkRate(task, "RETRY-" + suffix, 1.5, LocalDate.now().minusDays(1));
		UUID operationId = UUID.randomUUID();
		mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content(pieceworkEntryJson(operationId, task.id(), "S001", 10)))
			.andExpect(status().isCreated());
		mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content(pieceworkEntryJson(operationId, task.id(), "A001", 10)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PIECEWORK_OPERATION_PAYLOAD_MISMATCH"));
	}

	@Test
	void allowsFinanceToConfirmButNotSelfApprovePiecework() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		TaskFixture task = completedTask(suffix);
		createPieceworkRate(task, "FINANCE-" + suffix, 1.5, LocalDate.now().minusDays(1));
		MvcResult entryResult = mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content(pieceworkEntryJson(UUID.randomUUID(), task.id(), "S001", 5)))
			.andExpect(status().isCreated())
			.andReturn();
		String entryId = JsonPath.read(entryResult.getResponse().getContentAsString(), "$.entry.id");

		mvc.perform(post("/api/piecework/entries/{id}/confirmation", entryId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PIECEWORK_SELF_APPROVAL"));
		mvc.perform(post("/api/piecework/entries/{id}/confirmation", entryId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"supervisorCode\":\"F001\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.confirmedBy").value("F001"));
	}

	@Test
	void assignsADelayedPieceworkEntryToTheTaskCompletionMonth() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		TaskFixture task = completedTask(suffix);
		LocalDate completedOn = LocalDate.now().minusMonths(1).withDayOfMonth(15);
		jdbc.update("update planning_task set completed_at = ? where id = ?", Timestamp.valueOf(completedOn.atStartOfDay()), UUID.fromString(task.id()));
		createPieceworkRate(task, "MONTH-" + suffix + "-OLD", 1.25, completedOn.withDayOfMonth(1));
		createPieceworkRate(task, "MONTH-" + suffix + "-CURRENT", 9.99, LocalDate.now().withDayOfMonth(1));

		mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content(pieceworkEntryJson(UUID.randomUUID(), task.id(), "S001", 10)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.unitRate").value(1.25))
			.andExpect(jsonPath("$.entry.settlementDate").value(completedOn.toString()));
	}

	@Test
	void exportsOnlyConfirmedPayrollEntriesForTheRequestedSettlementMonthAndAuditsTheExport() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		TaskFixture task = completedTask(suffix);
		createPieceworkRate(task, "EXPORT-" + suffix, 1.5, LocalDate.now().minusDays(1));
		MvcResult entryResult = mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content(pieceworkEntryJson(UUID.randomUUID(), task.id(), "S001", 5)))
			.andExpect(status().isCreated())
			.andReturn();
		String entryId = JsonPath.read(entryResult.getResponse().getContentAsString(), "$.entry.id");
		String entryNo = JsonPath.read(entryResult.getResponse().getContentAsString(), "$.entry.entryNo");
		mvc.perform(post("/api/piecework/entries/{id}/confirmation", entryId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"supervisorCode\":\"PM01\"}"))
			.andExpect(status().isOk());
		MvcResult candidateResult = mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content(pieceworkEntryJson(UUID.randomUUID(), task.id(), "S001", 5)))
			.andExpect(status().isCreated())
			.andReturn();
		String candidateNo = JsonPath.read(candidateResult.getResponse().getContentAsString(), "$.entry.entryNo");

		mvc.perform(get("/api/piecework/exports/payroll")
				.param("viewerCode", "S001")
				.param("month", LocalDate.now().toString().substring(0, 7)))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("text/csv"))
			.andExpect(header().string("Content-Disposition", containsString("attachment")))
			.andExpect(content().string(containsString(entryNo)))
			.andExpect(content().string(containsString("员工姓名")))
			.andExpect(content().string(containsString("订单号")))
			.andExpect(content().string(containsString("产品材质")))
			.andExpect(content().string(org.hamcrest.Matchers.not(containsString(candidateNo))));
		mvc.perform(get("/api/piecework/exports/worker-payroll")
				.param("viewerCode", "S001")
				.param("workerCode", "W001")
				.param("month", LocalDate.now().toString().substring(0, 7)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("W001")))
			.andExpect(content().string(containsString("EXPORT-")));
		mvc.perform(get("/api/piecework/worker-payroll")
				.param("viewerCode", "S001")
				.param("workerCode", "W001")
				.param("month", LocalDate.now().toString().substring(0, 7)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].workerCode").value("W001"))
			.andExpect(jsonPath("$[0].orderNo").exists());
		mvc.perform(get("/api/piecework/exports/worker-output")
				.param("viewerCode", "S001")
				.param("workerCode", "W001")
				.param("month", LocalDate.now().toString().substring(0, 7))
				.param("routeType", "MID_TEMP_WAX"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("MID_TEMP_WAX")))
			.andExpect(content().string(containsString(task.taskNo())));
		mvc.perform(get("/api/piecework/worker-output")
				.param("viewerCode", "S001")
				.param("workerCode", "W001")
				.param("month", LocalDate.now().toString().substring(0, 7))
				.param("routeType", "MID_TEMP_WAX"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].workerCode").value("W001"))
			.andExpect(jsonPath("$[0].orderNo").exists());
		mvc.perform(get("/api/piecework/exports/worker-output")
				.param("viewerCode", "S001")
				.param("workerCode", "W001")
				.param("month", LocalDate.now().toString().substring(0, 7)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(task.taskNo())));
		mvc.perform(get("/api/piecework/exports/payroll")
				.param("viewerCode", "M001")
				.param("month", LocalDate.now().toString().substring(0, 7)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("PIECEWORK_EXPORT_SCOPE_DENIED"));

		Integer auditCount = jdbc.queryForObject("select count(*) from operation_audit_event where event_type = 'PIECEWORK_PAYROLL_EXPORT' and operator_code = 'S001'", Integer.class);
		org.junit.jupiter.api.Assertions.assertEquals(1, auditCount);
	}

	@Test
	void closesQualityInventoryPieceworkOutsourcingAndReportingFlow() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		TaskFixture task = completedTask(suffix);

		String inspectionOperationId = UUID.randomUUID().toString();
		String inspectionJson = """
			{
			  "operationId": "%s",
			  "taskId": "%s",
			  "inspectedQuantity": 10,
			  "acceptedQuantity": 8,
			  "rejectedQuantity": 2,
			  "defectCode": "POROSITY",
			  "inspectorCode": "Q001"
			}
			""".formatted(inspectionOperationId, task.id());
		MvcResult inspectionResult = mvc.perform(post("/api/quality/inspections")
				.contentType(MediaType.APPLICATION_JSON)
				.content(inspectionJson))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.duplicate").value(false))
			.andExpect(jsonPath("$.inspection.result").value("REJECTED"))
			.andReturn();
		String inspectionId = JsonPath.read(
			inspectionResult.getResponse().getContentAsString(),
			"$.inspection.id"
		);

		mvc.perform(post("/api/quality/inspections")
				.contentType(MediaType.APPLICATION_JSON)
				.content(inspectionJson))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.duplicate").value(true));

		mvc.perform(post("/api/quality/inspections/{id}/dispositions", inspectionId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision": "SCRAP",
					  "quantity": 2,
					  "reason": "气孔超出让步范围",
					  "decidedBy": "Q900"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.decision").value("SCRAP"));

		String receiptOperationId = UUID.randomUUID().toString();
		String receiptJson = inventoryJson(receiptOperationId, "RECEIPT", 100);
		mvc.perform(post("/api/inventory/movements")
				.contentType(MediaType.APPLICATION_JSON)
				.content(receiptJson))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.movement.balanceAfter").value(100));
		mvc.perform(post("/api/inventory/movements")
				.contentType(MediaType.APPLICATION_JSON)
				.content(receiptJson))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.duplicate").value(true));
		mvc.perform(post("/api/inventory/movements")
				.contentType(MediaType.APPLICATION_JSON)
				.content(inventoryJson(UUID.randomUUID().toString(), "ISSUE", 30)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.movement.balanceAfter").value(70));
		mvc.perform(post("/api/inventory/movements")
				.contentType(MediaType.APPLICATION_JSON)
				.content(inventoryJson(UUID.randomUUID().toString(), "ISSUE", 80)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_STOCK"));

		mvc.perform(post("/api/piecework/rates")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "supervisorCode": "S001",
					  "operationCode": "%s",
					  "operationName": "%s",
					  "routeType": "MID_TEMP_WAX",
					  "version": "V1",
					  "unitRate": 2.5,
					  "effectiveFrom": "%s"
					}
					""".formatted(task.operationCode(), task.operationName(), LocalDate.now().minusDays(1))))
			.andExpect(status().isCreated());
		MvcResult entryResult = mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "operationId": "%s",
					  "taskId": "%s",
					  "workerCode": "W001",
					  "quantity": 10
					}
					""".formatted(UUID.randomUUID(), task.id())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entry.amount").value(25.0))
			.andReturn();
		String entryId = JsonPath.read(entryResult.getResponse().getContentAsString(), "$.entry.id");
		mvc.perform(post("/api/piecework/entries/{id}/confirmation", entryId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"supervisorCode\":\"W001\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PIECEWORK_SELF_APPROVAL"));
		mvc.perform(post("/api/piecework/entries/{id}/confirmation", entryId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"));

		String supplierId = postAndRead("/api/outsourcing/suppliers", """
			{
			  "code": "SUP-%s",
			  "name": "协作铸造厂"
			}
			""".formatted(suffix), "$.id");
		String outsourceId = postAndRead("/api/outsourcing/orders", """
			{
			  "orderNo": "OS-%s",
			  "supplierId": "%s",
			  "itemCode": "SAND-001",
			  "itemName": "砂型毛坯",
			  "quantity": 10,
			  "unit": "PCS",
			  "dueDate": "%s"
			}
			""".formatted(suffix, supplierId, LocalDate.now().plusDays(7)), "$.id");
		transition(outsourceId, "SENT", null, "S001", "SENT");
		transition(outsourceId, "IN_PROGRESS", null, "S001", "IN_PROGRESS");
		transition(outsourceId, "RECEIVED", 4, "W001", "IN_PROGRESS");
		transition(outsourceId, "RECEIVED", 6, "W001", "RECEIVED");
		transition(outsourceId, "CLOSED", null, "S001", "CLOSED");

		mvc.perform(get("/api/reporting/dashboard"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.overview.orders").value(greaterThanOrEqualTo(1)))
			.andExpect(jsonPath("$.overview.confirmedPieceworkAmount").value(greaterThanOrEqualTo(25.0)))
			.andExpect(jsonPath("$.quality.acceptedQuantity").value(greaterThanOrEqualTo(8.0)))
			.andExpect(jsonPath("$.warehouseSkus[*].name").value(hasItem("RAW")))
			.andExpect(jsonPath("$.generatedAt").exists());
	}

	@Test
	void enforcesWorkflowSeparationAndVersionedConfigurationPublication() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String workflowId = postAndRead("/api/workflows", """
			{
			  "workflowType":"SCRAP_APPROVAL",
			  "businessKey":"QI-%s",
			  "title":"报废审批",
			  "requesterCode":"Q001",
			  "requiredApprovals":2
			}
			""".formatted(suffix), "$.id");
		mvc.perform(post("/api/workflows/{id}/actions", workflowId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"action\":\"APPROVE\",\"actorCode\":\"Q001\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("WORKFLOW_SELF_APPROVAL"));
		mvc.perform(post("/api/workflows/{id}/actions", workflowId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"action\":\"APPROVE\",\"actorCode\":\"Q900\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.approvalCount").value(1));
		mvc.perform(post("/api/workflows/{id}/actions", workflowId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"action\":\"APPROVE\",\"actorCode\":\"P900\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.approvalCount").value(2));

		String configV1 = postAndRead("/api/configurations", """
			{
			  "configType":"FORM",
			  "name":"质量检验表",
			  "version":"V1",
			  "content":"{\\"required\\":[\\"inspectedQuantity\\"]}",
			  "createdBy":"E001"
			}
			""", "$.id");
		mvc.perform(post("/api/configurations/{id}/publication", configV1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"operatorCode\":\"E900\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PUBLISHED"));
		String configV2 = postAndRead("/api/configurations", """
			{
			  "configType":"FORM",
			  "name":"质量检验表",
			  "version":"V2",
			  "content":"{\\"required\\":[\\"inspectedQuantity\\",\\"defectCode\\"]}",
			  "createdBy":"E001"
			}
			""", "$.id");
		mvc.perform(post("/api/configurations/{id}/publication", configV2)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"operatorCode\":\"E900\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PUBLISHED"));
		mvc.perform(get("/api/configurations"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.version == 'V1')].status").value("ROLLED_BACK"))
			.andExpect(jsonPath("$[?(@.version == 'V2')].status").value("PUBLISHED"));
	}

	private TaskFixture completedTask(String suffix) throws Exception {
		String productId = postAndRead("/api/products", """
			{"code":"MP-%s","name":"管理闭环件","routeType":"MID_TEMP_WAX","routeVersion":"V1"}
			""".formatted(suffix), "$.id");
		String customerId = postAndRead("/api/customers", """
			{"code":"MC-%s","name":"管理闭环客户"}
			""".formatted(suffix), "$.id");
		String orderId = postAndRead("/api/orders", """
			{
			  "orderNo":"MO-%s",
			  "customerId":"%s",
			  "priority":"NORMAL",
			  "lines":[{"productId":"%s","quantity":10,"unit":"PCS"}]
			}
			""".formatted(suffix, customerId, productId), "$.id");
		OrderReviewTestSupport.releaseAndLaunchAfterRequiredReviews(mvc, orderId);
		MvcResult taskResult = mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk())
			.andReturn();
		String body = taskResult.getResponse().getContentAsString();
		String taskId = JsonPath.read(body, "$[0].id");
		String taskNo = JsonPath.read(body, "$[0].taskNo");
		String operationCode = JsonPath.read(body, "$[0].operationCode");
		String operationName = JsonPath.read(body, "$[0].operationName");
		String moldAssetId = postAndRead("/api/resources", """
			{"assetCode":"MC-MOLD-%s","assetName":"Management test mold","assetType":"MOLD"}
			""".formatted(suffix), "$.id");
		mvc.perform(post("/api/tasks/{id}/wax-dispatch", taskId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"moldAssetId\":\"" + moldAssetId + "\",\"warehouseCode\":\"MOLD-01\",\"warehouseOperatorCode\":\"M001\",\"workerCode\":\"W001\",\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/start", taskId).header("X-Operator-Code", "W001"))
			.andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/reports", taskId)
				.header("X-Operator-Code", "W001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"operationId":"%s","goodQuantity":10,"scrapQuantity":0}
					""".formatted(UUID.randomUUID())))
			.andExpect(status().isCreated());
		return new TaskFixture(taskId, taskNo, operationCode, operationName);
	}

	private void createPieceworkRate(TaskFixture task, String version, double unitRate, LocalDate effectiveFrom) throws Exception {
		mvc.perform(post("/api/piecework/rates")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "supervisorCode": "S001",
					  "operationCode": "%s",
					  "operationName": "%s",
					  "routeType": "MID_TEMP_WAX",
					  "version": "%s",
					  "settlementUnit": "PCS",
					  "unitRate": %s,
					  "effectiveFrom": "%s"
					}
					""".formatted(task.operationCode(), task.operationName(), version, unitRate, effectiveFrom)))
			.andExpect(status().isCreated());
	}

	private String pieceworkEntryJson(UUID operationId, String taskId, String recordedBy, int quantity) {
		return """
			{"operationId":"%s","taskId":"%s","workerCode":"W001","recordedBy":"%s","quantity":%s}
			""".formatted(operationId, taskId, recordedBy, quantity);
	}

	private String inventoryJson(String operationId, String type, int quantity) {
		return """
			{
			  "operationId": "%s",
			  "warehouseCode": "RAW",
			  "itemCode": "ALLOY-001",
			  "itemName": "合金原料",
			  "unit": "KG",
			  "movementType": "%s",
			  "quantity": %d,
			  "operatorCode": "K001"
			}
			""".formatted(operationId, type, quantity);
	}

	private void transition(
		String orderId,
		String statusValue,
		Integer received,
		String operator,
		String expected
	) throws Exception {
		String quantity = received == null ? "null" : received.toString();
		mvc.perform(post("/api/outsourcing/orders/{id}/milestones", orderId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "nextStatus":"%s",
					  "receivedQuantity":%s,
					  "operatorCode":"%s"
					}
					""".formatted(statusValue, quantity, operator)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(expected));
	}

	private String postAndRead(String path, String json, String jsonPath) throws Exception {
		MvcResult result = mvc.perform(post(path)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json))
			.andExpect(status().isCreated())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
	}

	private record TaskFixture(String id, String taskNo, String operationCode, String operationName) {
	}
}
