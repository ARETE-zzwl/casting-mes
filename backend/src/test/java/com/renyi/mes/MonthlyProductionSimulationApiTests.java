package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:monthly-simulation-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MonthlyProductionSimulationApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void seedsOneMonthOfProductionAndACompletedDeliveredOrderWithRoleLogs() throws Exception {
		mvc.perform(post("/api/simulations/monthly-production").param("month", "2026-05"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.simulationCode").value("MONTHLY-2026-05"))
			.andExpect(jsonPath("$.generatedOrderCount").value(25))
			.andExpect(jsonPath("$.completedOrderNo").value("MSO-202605-DELIVERY"))
			.andExpect(jsonPath("$.deliveryNo").exists())
			.andExpect(jsonPath("$.roleOperations.length()").value(greaterThanOrEqualTo(46)));

		mvc.perform(get("/api/simulations/monthly-production/MONTHLY-2026-05/role-operations"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.functionCode == 'LOGISTICS_SHIP_DELIVER')].employeeCode").value("G001"))
			.andExpect(jsonPath("$[?(@.functionCode == 'FINAL_INSPECTION')].employeeCode").value("Q001"))
			.andExpect(jsonPath("$[?(@.functionCode == 'SHELL_LAYER_RECORD')].employeeCode").value("SH01"))
			.andExpect(jsonPath("$[?(@.functionCode == 'TASK_REPORT' && @.roleCode == 'POURING_OPERATOR')].employeeCode").value("PO01"))
			.andExpect(jsonPath("$[?(@.functionCode == 'TASK_REPORT' && @.roleCode == 'KNOCKOUT_OPERATOR')].employeeCode").value("KO01"));

		mvc.perform(post("/api/simulations/monthly-production").param("month", "2026-05"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roleOperations.length()").value(greaterThanOrEqualTo(46)));

		mvc.perform(get("/api/fulfillment/deliveries").param("viewerCode", "G001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.orderNo == 'MSO-202605-DELIVERY')].status").value("DELIVERED"));

		mvc.perform(post("/api/simulations/monthly-production/reset").param("month", "2026-07"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.simulationCode").value("MONTHLY-2026-07"))
			.andExpect(jsonPath("$.generatedOrderCount").value(25))
			.andExpect(jsonPath("$.completedOrderNo").value("MSO-202607-DELIVERY"));

		mvc.perform(get("/api/orders"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.orderNo == 'MSO-202605-DELIVERY')]").isEmpty());
		mvc.perform(get("/api/products"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.code == 'VLV-CF8-DN50')].modelImageUrl").value("/product-models/valve-body.png"));
	}
}
