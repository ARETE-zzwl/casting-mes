package com.renyi.mes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
class MultiProductOrderEngineeringApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void givesEachOrderProductItsOwnProcessCardWorkOrderAndBatchChain() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		String customerId = create("/api/customers", "{\"code\":\"MP-C-" + suffix + "\",\"name\":\"Multi Product Customer\"}");
		String valveId = create("/api/products", "{\"code\":\"MP-V-" + suffix + "\",\"name\":\"Valve Body\",\"routeType\":\"MID_TEMP_WAX\",\"routeVersion\":\"V1\"}");
		String pumpId = create("/api/products", "{\"code\":\"MP-P-" + suffix + "\",\"name\":\"Pump Body\",\"routeType\":\"MID_TEMP_WAX\",\"routeVersion\":\"V1\"}");
		String orderId = create("/api/orders", """
			{"orderNo":"MP-SO-%s","customerId":"%s","priority":"NORMAL","lines":[
			 {"productId":"%s","quantity":20,"unit":"PCS"},{"productId":"%s","quantity":35,"unit":"PCS"}]}
			""".formatted(suffix, customerId, valveId, pumpId));

		MvcResult orderResult = mvc.perform(get("/api/orders/{id}", orderId)).andExpect(status().isOk()).andReturn();
		List<String> lineIds = JsonPath.read(orderResult.getResponse().getContentAsString(), "$.lines[*].id");
		OrderReviewTestSupport.submit(mvc, orderId);
		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId)
				.contentType(MediaType.APPLICATION_JSON).content("""
					{"engineerCode":"E001","lines":[
					 {"orderLineId":"%s","processCardVersion":"VALVE-%s","engineeringParameters":"Valve card","engineeringOperationParameters":"{\\"WAX_INJECTION\\":\\"Valve wax 68C\\"}"},
					 {"orderLineId":"%s","processCardVersion":"PUMP-%s","engineeringParameters":"Pump card","engineeringOperationParameters":"{\\"WAX_INJECTION\\":\\"Pump wax 72C\\"}"}]}
					""".formatted(lineIds.get(0), suffix, lineIds.get(1), suffix)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lines[0].processCardVersion").value("VALVE-" + suffix))
			.andExpect(jsonPath("$.lines[1].processCardVersion").value("PUMP-" + suffix));

		OrderReviewTestSupport.releaseAfterRequiredReviews(mvc, orderId);
		MvcResult workOrders = mvc.perform(get("/api/work-orders").param("orderId", orderId))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andReturn();
		List<String> workOrderProductCodes = JsonPath.read(workOrders.getResponse().getContentAsString(), "$[*].productCode");
		assertTrue(workOrderProductCodes.containsAll(List.of("MP-V-" + suffix, "MP-P-" + suffix)));
		MvcResult trace = mvc.perform(get("/api/trace/orders/{id}", orderId)).andExpect(status().isOk()).andReturn();
		List<String> traceOrderLineIds = JsonPath.read(trace.getResponse().getContentAsString(), "$.workOrders[*].workOrder.orderLineId");
		assertEquals(2, traceOrderLineIds.stream().distinct().count());

		MvcResult tasks = mvc.perform(get("/api/tasks").param("orderId", orderId)).andExpect(status().isOk()).andReturn();
		List<String> batchIds = JsonPath.read(tasks.getResponse().getContentAsString(), "$[?(@.operationCode == 'WAX_INJECTION')].batchId");
		assertEquals(2, batchIds.stream().distinct().count());
	}

	private String create(String path, String json) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
