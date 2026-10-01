package com.renyi.mes;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:factory-acceptance-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FactoryAcceptanceSimulationApiTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void resetsToASmallThreeLineFactoryAcceptanceScenario() throws Exception {
		mvc.perform(post("/api/simulations/factory-acceptance/reset").param("month", "2026-07"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.simulationCode").value("FACTORY-ACCEPTANCE-2026-07"))
			.andExpect(jsonPath("$.generatedOrderCount").value(4))
			.andExpect(jsonPath("$.deliveredOrderCount").value(4))
			.andExpect(jsonPath("$.moldCases", hasSize(3)))
			.andExpect(jsonPath("$.moldCases[*].ownershipType", hasItem("CUSTOMER_OWNED")))
			.andExpect(jsonPath("$.moldCases[*].ownershipType", hasItem("COMPANY_OWNED")))
			.andExpect(jsonPath("$.furnaceBatchCount").value(2))
			.andExpect(jsonPath("$.resolvedHandoffExceptionCount").value(1));

		mvc.perform(get("/api/orders"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(4)));
		mvc.perform(get("/api/furnace-batches"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[?(@.operationCode == 'DEWAX')].status").value("COMPLETED"))
			.andExpect(jsonPath("$[?(@.operationCode == 'POURING')].status").value("COMPLETED"));
		mvc.perform(get("/api/handoff-exceptions").param("includeResolved", "true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].resolutionStatus").value("RESOLVED"));
	}

	@Test
	void completesAllThreeLinesWithTheirOwnExecutionControls() throws Exception {
		mvc.perform(post("/api/simulations/factory-acceptance/reset").param("month", "2026-07"))
			.andExpect(status().isOk());

		assertCount(22, "select count(*) from planning_task t join planning_batch b on b.id = t.batch_id join planning_work_order w on w.id = b.work_order_id where w.route_type = 'MID_TEMP_WAX'");
		assertCount(11, "select count(*) from planning_task t join planning_batch b on b.id = t.batch_id join planning_work_order w on w.id = b.work_order_id where w.route_type = 'LOW_TEMP_WAX'");
		assertCount(3, "select count(*) from planning_task t join planning_batch b on b.id = t.batch_id join planning_work_order w on w.id = b.work_order_id where w.route_type = 'SAND_OUTSOURCE'");
		assertCount(0, "select count(*) from planning_task where status <> 'COMPLETED'");
		assertCount(6, "select count(*) from shell_building_record where method = 'MANUAL'");
		assertCount(1, "select count(*) from shell_building_record where method = 'MANUAL' and next_action = 'FLOW_TO_NEXT'");
		assertCount(2, "select count(*) from shell_building_record where method = 'AUTOMATED' and next_action = 'FLOW_TO_NEXT'");
		assertCount(6, "select count(*) from furnace_batch_task");
		assertCount(26, "select count(*) from production_cart_transfer where status = 'RECEIVED'");
		assertCount(1, "select count(*) from outsourcing_order where status = 'CLOSED'");
		assertCount(1, "select count(*) from production_shortage_alert where status = 'OPEN' and shortage_quantity = 1");
		assertAtLeast(1, "select count(*) from notification_item where business_link = '/production-alerts'");
	}

	private void assertCount(int expected, String sql) {
		org.junit.jupiter.api.Assertions.assertEquals(expected, jdbc.queryForObject(sql, Integer.class), sql);
	}

	private void assertAtLeast(int minimum, String sql) {
		org.junit.jupiter.api.Assertions.assertTrue(jdbc.queryForObject(sql, Integer.class) >= minimum, sql);
	}
}
