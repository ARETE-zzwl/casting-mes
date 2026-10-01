package com.renyi.mes;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:monthly-piecework-payroll-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MonthlyPieceworkPayrollSimulationApiTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void simulatesThePreviousMonthPayrollFromCompletedProductionTasksAndExportsConfirmedWages() throws Exception {
		mvc.perform(post("/api/simulations/monthly-production/payroll-reset").param("month", "2026-07"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.production.simulationCode").value("MONTHLY-2026-07"))
			.andExpect(jsonPath("$.payrollMonth").value("2026-07"))
			.andExpect(jsonPath("$.confirmedEntryCount").value(4))
			.andExpect(jsonPath("$.confirmedAmount").isNumber());

		mvc.perform(get("/api/piecework/exports/payroll")
				.param("viewerCode", "PM01")
				.param("month", "2026-07"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", containsString("piecework-payroll-2026-07.csv")))
			.andExpect(content().string(containsString("TREE")))
			.andExpect(content().string(containsString("KG")));

		Integer lowWaxEntries = jdbc.queryForObject("""
			select count(*) from piecework_entry e
			join planning_task t on t.id = e.task_id
			join planning_batch b on b.id = t.batch_id
			join planning_work_order w on w.id = b.work_order_id
			where w.route_type = 'LOW_TEMP_WAX'
			""", Integer.class);
		Integer confirmedEntries = jdbc.queryForObject("select count(*) from piecework_entry where status = 'CONFIRMED' and settlement_date between date '2026-07-01' and date '2026-07-31'", Integer.class);
		Integer exportAudits = jdbc.queryForObject("select count(*) from operation_audit_event where event_type = 'PIECEWORK_PAYROLL_EXPORT' and operator_code = 'PM01'", Integer.class);
		Integer selfConfirmedEntries = jdbc.queryForObject("select count(*) from piecework_entry where status = 'CONFIRMED' and recorded_by = confirmed_by", Integer.class);
		org.junit.jupiter.api.Assertions.assertEquals(0, lowWaxEntries);
		org.junit.jupiter.api.Assertions.assertEquals(4, confirmedEntries);
		org.junit.jupiter.api.Assertions.assertEquals(1, exportAudits);
		org.junit.jupiter.api.Assertions.assertEquals(0, selfConfirmedEntries);
	}
}
