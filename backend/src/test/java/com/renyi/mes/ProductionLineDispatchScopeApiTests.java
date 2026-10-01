package com.renyi.mes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class ProductionLineDispatchScopeApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void limitsSupervisorsToTheirLineAndLetsGlobalUsersSeeBothLines() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		String customerId = create("/api/customers", """
			{"code":"SCOPE-C-%s","name":"Scope Customer"}
			""".formatted(suffix));
		String midProductId = createProduct(suffix, "MID_TEMP_WAX");
		String lowProductId = createProduct(suffix, "LOW_TEMP_WAX");
		String midOrderId = createOrder(suffix, customerId, midProductId, "MID");
		String lowOrderId = createOrder(suffix, customerId, lowProductId, "LOW");
		OrderReviewTestSupport.releaseAfterRequiredReviews(mvc, midOrderId);
		OrderReviewTestSupport.releaseAfterRequiredReviews(mvc, lowOrderId);

		MvcResult midTasks = mvc.perform(get("/api/tasks").param("supervisorCode", "S001"))
			.andExpect(status().isOk()).andReturn();
		List<String> midRoutes = JsonPath.read(midTasks.getResponse().getContentAsString(), "$..routeType");
		assertFalse(midRoutes.isEmpty());
		assertTrue(midRoutes.stream().allMatch("MID_TEMP_WAX"::equals));

		MvcResult lowTasks = mvc.perform(get("/api/tasks").param("supervisorCode", "LW01"))
			.andExpect(status().isOk()).andReturn();
		List<String> lowRoutes = JsonPath.read(lowTasks.getResponse().getContentAsString(), "$..routeType");
		assertFalse(lowRoutes.isEmpty());
		assertTrue(lowRoutes.stream().allMatch("LOW_TEMP_WAX"::equals));

		MvcResult globalTasks = mvc.perform(get("/api/tasks").param("supervisorCode", "GM001"))
			.andExpect(status().isOk()).andReturn();
		List<String> globalRoutes = JsonPath.read(globalTasks.getResponse().getContentAsString(), "$..routeType");
		assertTrue(globalRoutes.contains("MID_TEMP_WAX"));
		assertTrue(globalRoutes.contains("LOW_TEMP_WAX"));

		List<String> lowTaskIds = JsonPath.read(globalTasks.getResponse().getContentAsString(),
			"$[?(@.orderId == '" + lowOrderId + "')].id");
		mvc.perform(post("/api/tasks/{taskId}/assignment", lowTaskIds.getFirst())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{" +
					"\"workerCode\":\"W001\",\"reportingMode\":\"SELF_REPORTED_QUANTITY\",\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isForbidden());

		mvc.perform(get("/api/planning/dispatch-recommendations").param("supervisorCode", "S001"))
			.andExpect(status().isOk())
			.andExpect(result -> {
				List<String> routes = JsonPath.read(result.getResponse().getContentAsString(), "$.tasks[*].routeType");
				assertTrue(routes.stream().allMatch("MID_TEMP_WAX"::equals));
			});
	}

	@Test
	void recommendsFinishedGoodsKeeperForFinalCountAcrossProductionLines() throws Exception {
		mvc.perform(get("/api/planning/dispatch-recommendations")
				.param("operationCode", "FINAL_COUNT")
				.param("routeType", "MID_TEMP_WAX"))
			.andExpect(status().isOk())
			.andExpect(result -> {
				List<String> workers = JsonPath.read(result.getResponse().getContentAsString(), "$.workers[*].employeeCode");
				assertTrue(workers.contains("G001"));
			});
	}

	@Test
	void recommendsProductionManagerForSandIncomingInspection() throws Exception {
		mvc.perform(get("/api/planning/dispatch-recommendations")
				.param("operationCode", "INCOMING_INSPECTION")
				.param("routeType", "SAND_OUTSOURCE"))
			.andExpect(status().isOk())
			.andExpect(result -> {
				List<String> workers = JsonPath.read(result.getResponse().getContentAsString(), "$.workers[*].employeeCode");
				assertTrue(workers.contains("PM01"));
			});
	}

	private String createProduct(String suffix, String routeType) throws Exception {
		return create("/api/products", """
			{"code":"SCOPE-P-%s-%s","name":"Scope Part %s","routeType":"%s","routeVersion":"V1"}
			""".formatted(suffix, routeType, routeType, routeType));
	}

	private String createOrder(String suffix, String customerId, String productId, String line) throws Exception {
		return create("/api/orders", """
			{"orderNo":"SCOPE-SO-%s-%s","customerId":"%s","priority":"NORMAL",
			"lines":[{"productId":"%s","quantity":5,"unit":"PCS"}]}
			""".formatted(suffix, line, customerId, productId));
	}

	private String create(String path, String json) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
