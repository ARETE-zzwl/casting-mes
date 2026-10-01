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

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:order-mold-availability-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@AutoConfigureMockMvc
class OrderMoldAvailabilityApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void plansCustomerDeliveryAndBindsTheReceivedMoldToTheOrderLine() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		String customerName = "Customer Mold " + suffix;
		String customerId = create("/api/customers", """
			{"code":"MOLD-C-%s","name":"%s"}
			""".formatted(suffix, customerName));
		String productId = create("/api/products", """
			{"code":"MOLD-P-%s","name":"Mold Plan Part","routeType":"MID_TEMP_WAX","routeVersion":"V1"}
			""".formatted(suffix));
		MvcResult orderResult = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
			{"orderNo":"MOLD-SO-%s","customerId":"%s","priority":"NORMAL","lines":[{"productId":"%s","quantity":10,"unit":"PCS"}]}
			""".formatted(suffix, customerId, productId)))
			.andExpect(status().isCreated()).andReturn();
		String orderId = JsonPath.read(orderResult.getResponse().getContentAsString(), "$.id");
		String orderLineId = JsonPath.read(orderResult.getResponse().getContentAsString(), "$.lines[0].id");

		mvc.perform(get("/api/customer-product-molds/history")
				.param("customerId", customerId).param("productId", productId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].orderCount").value(1));

		mvc.perform(post("/api/factory/mold-requests/order-mold-plans").contentType(MediaType.APPLICATION_JSON).content("""
			{"orderId":"%s","orderLineId":"%s","selectionStatus":"CUSTOMER_DELIVERY_PENDING","pendingReason":"Customer will deliver mold","selectedBy":"FD01"}
			""".formatted(orderId, orderLineId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.selectionStatus").value("CUSTOMER_DELIVERY_PENDING"))
			.andExpect(jsonPath("$.moldAssetId").doesNotExist());

		mvc.perform(post("/api/factory/mold-requests/receipts").contentType(MediaType.APPLICATION_JSON).content("""
			{"assetCode":"MOLD-R-%s","assetName":"Customer Delivered Mold","locationCode":"MOLD-A-01","ownershipType":"CUSTOMER_OWNED","ownerName":"%s","operatorCode":"M001","orderLineId":"%s"}
			""".formatted(suffix, customerName, orderLineId)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.assetCode").value("MOLD-R-" + suffix));

		mvc.perform(get("/api/factory/mold-requests/order-selections"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.orderLineId == '" + orderLineId + "')].selectionStatus").value("SELECTED"))
			.andExpect(jsonPath("$[?(@.orderLineId == '" + orderLineId + "')].moldAssetCode").value("MOLD-R-" + suffix));

		mvc.perform(get("/api/customer-product-molds")
				.param("customerId", customerId)
				.param("productId", productId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].customerId").value(customerId))
			.andExpect(jsonPath("$[0].productId").value(productId))
			.andExpect(jsonPath("$[0].moldAssetCode").value("MOLD-R-" + suffix));

		mvc.perform(get("/api/product-molds").param("productId", productId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].moldAssetCode").value("MOLD-R-" + suffix));
	}

	private String create(String path, String content) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(content))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
