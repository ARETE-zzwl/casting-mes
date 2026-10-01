package com.renyi.mes;

import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SalesPerformanceApiTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void freezesSalesOwnerOnTheOrderAndLimitsSalesRepsToTheirOwnReviewedAmount() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		UUID customerId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		jdbc.update("""
			insert into customer_order_customer (id, code, name, active, created_at, sales_owner)
			values (?, ?, ?, true, current_timestamp, '销售专员01')
			""", customerId, "CUS-SALES-" + suffix, "销售测试客户" + suffix);
		jdbc.update("""
			insert into customer_order_header (id, order_no, customer_id, customer_code, customer_name, sales_owner,
				status, priority, version, created_at, customer_manager_reviewed_at)
			values (?, ?, ?, ?, ?, '销售专员01', 'APPROVED', 'NORMAL', 0, current_timestamp, current_timestamp)
			""", orderId, "SO-SALES-" + suffix, customerId, "CUS-SALES-" + suffix, "销售测试客户" + suffix);
		jdbc.update("""
			insert into customer_order_line (id, order_id, line_no, product_id, product_code, product_name, route_type,
				route_version, ordered_quantity, unit, sales_unit_price, sales_price_unit)
			values (?, ?, 1, ?, ?, '销售测试阀体', 'MID_TEMP_WAX', 'V1', 25, 'PCS', 10.00, 'PCS')
			""", UUID.randomUUID(), orderId, UUID.randomUUID(), "PRD-SALES-" + suffix);

		String period = YearMonth.now().toString();
		mvc.perform(get("/api/sales/performance")
				.param("viewerCode", "SAL01")
				.param("period", period))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scope").value("SELF"))
			.andExpect(jsonPath("$.overview.recognizedAmount", closeTo(250.0, 0.001)))
			.andExpect(jsonPath("$.recentOrders[0].salesOwner").value("销售专员01"));

		mvc.perform(get("/api/sales/performance")
				.param("viewerCode", "CM001")
				.param("period", period)
				.param("salesOwner", "销售专员01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scope").value("ALL"))
			.andExpect(jsonPath("$.overview.recognizedAmount", closeTo(250.0, 0.001)));

		mvc.perform(get("/api/sales/performance")
				.param("viewerCode", "W001")
				.param("period", period))
			.andExpect(status().isForbidden());
	}
}
