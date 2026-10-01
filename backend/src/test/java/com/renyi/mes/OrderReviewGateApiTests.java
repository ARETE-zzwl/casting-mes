package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class OrderReviewGateApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void requiresEitherCustomerManagerOrGeneralManagerReviewBeforeCreatingProductionTasks() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String customerId = create("/api/customers", "{\"code\":\"RG-C-" + suffix + "\",\"name\":\"Review Gate Customer\"}");
		String productId = create("/api/products", "{\"code\":\"RG-P-" + suffix + "\",\"name\":\"Review Gate Product\",\"routeType\":\"MID_TEMP_WAX\",\"routeVersion\":\"V1\"}");
		String orderId = create("/api/orders", "{\"customerId\":\"" + customerId + "\",\"priority\":\"NORMAL\",\"lines\":[{\"productId\":\"" + productId + "\",\"quantity\":10,\"unit\":\"PCS\"}]}");
		OrderReviewTestSupport.submit(mvc, orderId);

		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"engineerCode\":\"E001\",\"processCardVersion\":\"RG-V1\",\"engineeringParameters\":\"review gate test\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
		mvc.perform(post("/api/orders/{id}/release", orderId))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORDER_NOT_APPROVED"));

		mvc.perform(post("/api/orders/{id}/customer-manager-review", orderId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"managerCode\":\"CM001\",\"note\":\"客户需求、交期和商务备注已核对\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"))
			.andExpect(jsonPath("$.customerManagerCode").value("CM001"));
		mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(11))
			.andExpect(jsonPath("$[0].status").value("BLOCKED"));
		MvcResult workOrders = mvc.perform(get("/api/work-orders").param("orderId", orderId))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].batchStatus").value("PENDING_LAUNCH"))
			.andReturn();
		String batchId = JsonPath.read(workOrders.getResponse().getContentAsString(), "$[0].batchId");
		mvc.perform(post("/api/batches/{batchId}/launch", batchId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"productionQuantity\":10,\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.batchStatus").value("READY"))
			.andExpect(jsonPath("$.currentTaskStatus").value("READY"));
		mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("READY"));
	}

	private String create(String path, String json) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
