package com.renyi.mes;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class OrderEntryConvenienceApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void createsCustomerProductAndOrderWithAutomaticCodesAndStructuredProcessParameters() throws Exception {
		String customerId = create("/api/customers", """
			{"name":"Order Entry Customer","contactName":"Li","contactPhone":"13800000000"}
			""", "$.id");
		String productId = create("/api/products", """
			{"name":"Order Entry Product","routeType":"MID_TEMP_WAX","routeVersion":"V1",
			 "specification":"DN25","material":"304"}
			""", "$.id");
		MvcResult orderResult = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
			{"customerId":"%s","priority":"NORMAL","contractAttachmentUrl":"/uploads/order_contract/demo.pdf",
			 "lines":[{"productId":"%s","quantity":20,"unit":"PCS"}]}
			""".formatted(customerId, productId)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.contractAttachmentUrl").value("/uploads/order_contract/demo.pdf"))
			.andReturn();
		String orderId = JsonPath.read(orderResult.getResponse().getContentAsString(), "$.id");
		OrderReviewTestSupport.submit(mvc, orderId);

		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"engineerCode":"E001","engineeringParameters":"Overall requirement",
					 "engineeringOperationParameters":"{\\"WAX_INJECTION\\":\\"68C, 0.55MPa\\",\\"POURING\\":\\"1580C, 304\\"}"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.orderNo", startsWith("SO-")))
			.andExpect(jsonPath("$.processCardVersion", startsWith("PC-")))
			.andExpect(jsonPath("$.engineeringOperationParameters").value(org.hamcrest.Matchers.containsString("WAX_INJECTION")));
	}

	@Test
	void rejectsFractionalOrderQuantity() throws Exception {
		String customerId = create("/api/customers", """
			{"name":"Integer Quantity Customer","contactName":"Wang","contactPhone":"13900000000"}
			""", "$.id");
		String productId = create("/api/products", """
			{"name":"Integer Quantity Product","routeType":"MID_TEMP_WAX","routeVersion":"V1",
			 "specification":"DN20","material":"304"}
			""", "$.id");

		mvc.perform(post("/api/orders")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"customerId":"%s","priority":"NORMAL","lines":[{"productId":"%s","quantity":1.5,"unit":"PCS"}]}
					""".formatted(customerId, productId)))
			.andExpect(status().is(422));
	}

	private String create(String path, String payload, String jsonPath) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
	}
}
