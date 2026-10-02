package com.renyi.mes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;
import com.renyi.mes.customerorder.CustomerOrderApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentUsageIntegrationTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private CustomerOrderApplication customerOrders;

	private final Map<String, String> waxMoldByTask = new HashMap<>();

	@Test
	void blocksOutOfSequenceWorkAndTransfersOnlyGoodQuantity() throws Exception {
		OrderFixture fixture = createReleasedOrder(10);
		TaskFixture first = fixture.tasks().get(0);
		TaskFixture second = fixture.tasks().get(1);

		mvc.perform(post("/api/tasks/{id}/assignment", second.id())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"workerCode\":\"W002\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("TASK_STATE_CONFLICT"));

		assign(first.id(), "W001");
		start(first.id(), "W001");
		report(first.id(), UUID.randomUUID(), 8, 2, "W001")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.report.taskStatus").value("COMPLETED"));

		assign(second.id(), "W002");
		mvc.perform(post("/api/tasks/{id}/start", second.id())
				.header("X-Operator-Code", "W002"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.plannedQuantity").value(8));
		report(second.id(), UUID.randomUUID(), 8, 0, "W002")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.report.taskStatus").value("COMPLETED"));
	}

	@Test
	void makesConcurrentReportRetriesExactlyOnce() throws Exception {
		OrderFixture fixture = createReleasedOrder(10);
		TaskFixture task = fixture.tasks().getFirst();
		assign(task.id(), "W001");
		start(task.id(), "W001");
		UUID operationId = UUID.randomUUID();

		List<Integer> statuses = runTogether(2,
			() -> reportStatus(task.id(), operationId, 10, 0, "W001"));

		assertThat(statuses).containsExactlyInAnyOrder(200, 201);
		mvc.perform(get("/api/tasks/{id}/reports", task.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].taskGoodTotal").value(10));
	}

	@Test
	void preventsConcurrentInspectionAndDispositionFromExceedingAvailableQuantity() throws Exception {
		OrderFixture fixture = createReleasedOrder(10);
		TaskFixture task = fixture.tasks().getFirst();
		assign(task.id(), "W001");
		start(task.id(), "W001");
		report(task.id(), UUID.randomUUID(), 10, 0, "W001")
			.andExpect(status().isCreated());

		List<Integer> inspectionStatuses = runTogether(2,
			() -> inspectionStatus(task.id(), UUID.randomUUID(), 7, 0, 7));
		assertThat(inspectionStatuses).containsExactlyInAnyOrder(201, 409);

		String inspectionId = findInspectionId(task.id());
		List<Integer> dispositionStatuses = runTogether(2,
			() -> dispositionStatus(inspectionId, 5));
		assertThat(dispositionStatuses).containsExactlyInAnyOrder(201, 409);
		mvc.perform(get("/api/quality/inspections/{id}/dispositions", inspectionId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void serializesInitialStockCreationIssuesAndIdempotentRetries() throws Exception {
		String itemCode = "LOAD-" + shortId();
		List<Integer> receiptStatuses = runTogether(2,
			() -> inventoryStatus(UUID.randomUUID(), itemCode, "RECEIPT", 50));
		assertThat(receiptStatuses).containsOnly(201);

		UUID retryOperation = UUID.randomUUID();
		List<Integer> retryStatuses = runTogether(2,
			() -> inventoryStatus(retryOperation, itemCode, "ISSUE", 10));
		assertThat(retryStatuses).containsExactlyInAnyOrder(200, 201);

		List<Integer> issueStatuses = runTogether(12,
			() -> inventoryStatus(UUID.randomUUID(), itemCode, "ISSUE", 10));
		assertThat(issueStatuses.stream().filter(status -> status == 201).count()).isEqualTo(9);
		assertThat(issueStatuses.stream().filter(status -> status == 409).count()).isEqualTo(3);
		assertThat(balance(itemCode)).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void makesConcurrentReleaseOfTheSameOrderIdempotent() throws Exception {
		String orderId = createApprovedOrder(10);

		List<Integer> statuses = runTogether(4, () -> releaseStatus(orderId));

		assertThat(statuses).containsOnly(200);
		mvc.perform(get("/api/work-orders").param("orderId", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)));
		mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(11)));
	}

	@Test
	void keepsMultipleOrdersAndOperatorsIsolatedUnderSimultaneousReporting() throws Exception {
		List<OrderFixture> fixtures = java.util.stream.IntStream.range(0, 4)
			.mapToObj(index -> {
				try {
					return createReleasedOrder(10);
				}
				catch (Exception exception) {
					throw new AssertionError(exception);
				}
			})
			.toList();
		List<String> workers = List.of("W001", "W002", "W001", "W002");
		for (int index = 0; index < fixtures.size(); index++) {
			assign(fixtures.get(index).tasks().getFirst().id(), workers.get(index));
		}
		AtomicInteger next = new AtomicInteger();

		List<Integer> statuses = runTogether(fixtures.size(), () -> {
			int index = next.getAndIncrement();
			TaskFixture task = fixtures.get(index).tasks().getFirst();
			String worker = workers.get(index);
			start(task.id(), worker);
			return reportStatus(task.id(), UUID.randomUUID(), 10, 0, worker);
		});

		assertThat(statuses).containsOnly(201);
		for (OrderFixture fixture : fixtures) {
			TaskFixture task = fixture.tasks().getFirst();
			mvc.perform(get("/api/tasks/{id}", task.id()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").value(fixture.orderId()))
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.goodQuantity").value(10));
		}
	}

	@Test
	void capsConcurrentPieceworkAndMakesRetriesIdempotent() throws Exception {
		OrderFixture fixture = createReleasedOrder(10);
		TaskFixture task = fixture.tasks().getFirst();
		assign(task.id(), "W001");
		start(task.id(), "W001");
		report(task.id(), UUID.randomUUID(), 10, 0, "W001")
			.andExpect(status().isCreated());
		mvc.perform(post("/api/piecework/rates")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "supervisorCode":"S001",
					  "operationCode":"%s",
					  "operationName":"%s",
					  "routeType":"MID_TEMP_WAX",
					  "version":"LOAD-%s",
					  "unitRate":1.5,
					  "effectiveFrom":"%s"
					}
					""".formatted(
						task.operationCode(), task.operationName(), shortId(),
						LocalDate.now().minusDays(1))))
			.andExpect(status().isCreated());

		List<Integer> capStatuses = runTogether(2,
			() -> pieceworkStatus(task.id(), UUID.randomUUID(), 7));
		assertThat(capStatuses).containsExactlyInAnyOrder(201, 409);

		UUID retryOperation = UUID.randomUUID();
		List<Integer> retryStatuses = runTogether(2,
			() -> pieceworkStatus(task.id(), retryOperation, 3));
		assertThat(retryStatuses).containsExactlyInAnyOrder(200, 201);
	}

	@Test
	void rejectsAnOperationIdReusedWithDifferentBusinessPayload() throws Exception {
		OrderFixture fixture = createReleasedOrder(10);
		TaskFixture task = fixture.tasks().getFirst();
		assign(task.id(), "W001");
		start(task.id(), "W001");
		UUID reportOperation = UUID.randomUUID();
		report(task.id(), reportOperation, 4, 0, "W001")
			.andExpect(status().isCreated());
		report(task.id(), reportOperation, 5, 0, "W001")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("OPERATION_ID_PAYLOAD_MISMATCH"));

		String itemCode = "PAYLOAD-" + shortId();
		UUID inventoryOperation = UUID.randomUUID();
		assertThat(inventoryStatus(inventoryOperation, itemCode, "RECEIPT", 10)).isEqualTo(201);
		MvcResult mismatch = inventory(
			inventoryOperation, itemCode, "RECEIPT", 20);
		assertThat(mismatch.getResponse().getStatus()).isEqualTo(409);
		assertThat(JsonPath.<String>read(
			mismatch.getResponse().getContentAsString(), "$.error.code"))
			.isEqualTo("INVENTORY_OPERATION_PAYLOAD_MISMATCH");
	}

	private OrderFixture createReleasedOrder(int quantity) throws Exception {
		String orderId = createApprovedOrder(quantity);
		mvc.perform(post("/api/orders/{id}/release", orderId)).andExpect(status().isOk());
		OrderReviewTestSupport.launchPendingBatches(mvc, orderId);
		String taskBody = mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> waxTasks = JsonPath.read(taskBody, "$[?(@.operationCode == 'WAX_INJECTION')].id");
		String moldAssetId = postAndRead("/api/resources", """
			{"assetCode":"CP-MOLD-%s","assetName":"Concurrent Mold","assetType":"MOLD"}
			""".formatted(shortId()), "$.id");
		waxMoldByTask.put(waxTasks.getFirst(), moldAssetId);
		MvcResult taskResult = mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk())
			.andReturn();
		List<Map<String, Object>> rows = JsonPath.read(
			taskResult.getResponse().getContentAsString(), "$");
		List<TaskFixture> tasks = rows.stream()
			.map(row -> new TaskFixture(
				row.get("id").toString(),
				(Integer) row.get("sequenceNo"),
				row.get("operationCode").toString(),
				row.get("operationName").toString()))
			.sorted(Comparator.comparingInt(TaskFixture::sequenceNo))
			.toList();
		return new OrderFixture(orderId, tasks);
	}

	private String createApprovedOrder(int quantity) throws Exception {
		String suffix = shortId();
		String productId = postAndRead("/api/products", """
			{"code":"CP-%s","name":"Concurrent Product","routeType":"MID_TEMP_WAX","routeVersion":"V1"}
			""".formatted(suffix), "$.id");
		String customerId = postAndRead("/api/customers", """
			{"code":"CC-%s","name":"Concurrent Customer"}
			""".formatted(suffix), "$.id");
		String orderId = postAndRead("/api/orders", """
			{
			  "orderNo":"CO-%s",
			  "customerId":"%s",
			  "priority":"NORMAL",
			  "lines":[{"productId":"%s","quantity":%d,"unit":"PCS"}]
			}
			""".formatted(suffix, customerId, productId, quantity), "$.id");
		OrderReviewTestSupport.submit(mvc, orderId);
		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"engineerCode\":\"E001\",\"processCardVersion\":\"V1\",\"engineeringParameters\":\"Concurrent order parameters\"}"))
			.andExpect(status().isOk());
		customerOrders.reviewByCustomerManager(UUID.fromString(orderId), "CM001", null);
		return orderId;
	}

	private void assign(String taskId, String worker) throws Exception {
		String moldAssetId = waxMoldByTask.get(taskId);
		if (moldAssetId != null) {
			mvc.perform(post("/api/tasks/{id}/wax-dispatch", taskId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"moldAssetId\":\"" + moldAssetId + "\",\"warehouseCode\":\"MOLD-01\",\"warehouseOperatorCode\":\"M001\",\"workerCode\":\"" + worker
						+ "\",\"reportingMode\":\"SELF_REPORTED_QUANTITY\",\"settlementUnit\":\"PCS\",\"compensationMode\":\"PIECE_PCS\",\"supervisorCode\":\"S001\"}"))
				.andExpect(status().isOk());
			return;
		}
		mvc.perform(post("/api/tasks/{id}/assignment", taskId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"workerCode\":\"" + worker + "\"}"))
			.andExpect(status().isOk());
	}

	private void start(String taskId, String worker) throws Exception {
		mvc.perform(post("/api/tasks/{id}/start", taskId)
				.header("X-Operator-Code", worker))
			.andExpect(status().isOk());
	}

	private org.springframework.test.web.servlet.ResultActions report(
			String taskId, UUID operationId, int good, int scrap, String worker) throws Exception {
		return mvc.perform(post("/api/tasks/{id}/reports", taskId)
			.header("X-Operator-Code", worker)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"operationId":"%s","goodQuantity":%d,"scrapQuantity":%d}
				""".formatted(operationId, good, scrap)));
	}

	private int reportStatus(String taskId, UUID operationId, int good, int scrap, String worker)
			throws Exception {
		return report(taskId, operationId, good, scrap, worker)
			.andReturn().getResponse().getStatus();
	}

	private int inspectionStatus(String taskId, UUID operationId, int inspected, int accepted,
			int rejected) throws Exception {
		return mvc.perform(post("/api/quality/inspections")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "operationId":"%s",
					  "taskId":"%s",
					  "inspectedQuantity":%d,
					  "acceptedQuantity":%d,
					  "rejectedQuantity":%d,
					  "defectCode":"LOAD_TEST",
					  "inspectorCode":"Q001"
					}
					""".formatted(operationId, taskId, inspected, accepted, rejected)))
			.andReturn().getResponse().getStatus();
	}

	private String findInspectionId(String taskId) throws Exception {
		MvcResult result = mvc.perform(get("/api/quality/inspections"))
			.andExpect(status().isOk())
			.andReturn();
		List<String> ids = JsonPath.read(result.getResponse().getContentAsString(),
			"$[?(@.taskId == '" + taskId + "')].id");
		return ids.getFirst();
	}

	private int dispositionStatus(String inspectionId, int quantity) throws Exception {
		return mvc.perform(post("/api/quality/inspections/{id}/dispositions", inspectionId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "decision":"SCRAP",
					  "quantity":%d,
					  "reason":"Concurrent disposition test",
					  "decidedBy":"Q900"
					}
					""".formatted(quantity)))
			.andReturn().getResponse().getStatus();
	}

	private int pieceworkStatus(String taskId, UUID operationId, int quantity) throws Exception {
		return mvc.perform(post("/api/piecework/entries")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "operationId":"%s",
					  "taskId":"%s",
					  "workerCode":"W001",
					  "quantity":%d
					}
					""".formatted(operationId, taskId, quantity)))
			.andReturn().getResponse().getStatus();
	}

	private int inventoryStatus(UUID operationId, String itemCode, String type, int quantity)
			throws Exception {
		return inventory(operationId, itemCode, type, quantity)
			.getResponse().getStatus();
	}

	private MvcResult inventory(UUID operationId, String itemCode, String type, int quantity)
			throws Exception {
		return mvc.perform(post("/api/inventory/movements")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "operationId":"%s",
					  "warehouseCode":"LOAD",
					  "itemCode":"%s",
					  "itemName":"Load Test Alloy",
					  "unit":"KG",
					  "movementType":"%s",
					  "quantity":%d,
					  "operatorCode":"K001"
					}
					""".formatted(operationId, itemCode, type, quantity)))
			.andReturn();
	}

	private BigDecimal balance(String itemCode) throws Exception {
		MvcResult result = mvc.perform(get("/api/inventory/balances").param("viewerCode", "K001"))
			.andExpect(status().isOk())
			.andReturn();
		List<Number> quantities = JsonPath.read(result.getResponse().getContentAsString(),
			"$[?(@.itemCode == '" + itemCode + "')].quantity");
		return new BigDecimal(quantities.getFirst().toString());
	}

	private int releaseStatus(String orderId) throws Exception {
		MvcResult result = mvc.perform(post("/api/orders/{id}/release", orderId)).andReturn();
		return result.getResponse().getStatus();
	}

	private <T> List<T> runTogether(int count, Callable<T> action) throws Exception {
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
			List<Future<T>> futures = java.util.stream.IntStream.range(0, count)
				.mapToObj(index -> executor.submit(() -> {
					ready.countDown();
					start.await();
					return action.call();
				}))
				.toList();
			ready.await();
			start.countDown();
			return futures.stream().map(this::futureValue).toList();
		}
	}

	private <T> T futureValue(Future<T> future) {
		try {
			return future.get();
		}
		catch (Exception exception) {
			throw new AssertionError("Concurrent request failed", exception);
		}
	}

	private String postAndRead(String path, String json, String jsonPath) throws Exception {
		MvcResult result = mvc.perform(post(path)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json))
			.andExpect(status().isCreated())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
	}

	private static String shortId() {
		return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
	}

	private record OrderFixture(String orderId, List<TaskFixture> tasks) {
	}

	private record TaskFixture(
		String id,
		int sequenceNo,
		String operationCode,
		String operationName
	) {
	}
}
