package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;
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
class CartTransferApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void recordsCartLoadingAndQuantityExceptionWithoutBlockingTheNextOperation() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String productId = create("/api/products", "{\"code\":\"CART-P-" + suffix + "\",\"name\":\"Cart transfer casting\",\"routeType\":\"MID_TEMP_WAX\",\"routeVersion\":\"V1\"}");
		String customerId = create("/api/customers", "{\"code\":\"CART-C-" + suffix + "\",\"name\":\"Cart transfer customer\"}");
		String orderId = create("/api/orders", "{\"orderNo\":\"CART-SO-" + suffix + "\",\"customerId\":\"" + customerId + "\",\"priority\":\"NORMAL\",\"lines\":[{\"productId\":\"" + productId + "\",\"quantity\":12,\"unit\":\"PCS\"}]}");
		OrderReviewTestSupport.submit(mvc, orderId);
		mvc.perform(post("/api/orders/{id}/engineering-confirmation", orderId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"engineerCode\":\"E001\",\"processCardVersion\":\"CART-V1\",\"engineeringParameters\":\"测试周转车流转\"}"))
			.andExpect(status().isOk());
		OrderReviewTestSupport.releaseAndLaunchAfterRequiredReviews(mvc, orderId);

		String injection = task(orderId, "WAX_INJECTION");
		String moldId = create("/api/resources", "{\"assetCode\":\"CART-MOLD-" + suffix + "\",\"assetName\":\"Cart test mold\",\"assetType\":\"MOLD\",\"locationCode\":\"MOLD-01\"}");
		mvc.perform(post("/api/tasks/{id}/wax-dispatch", injection).contentType(MediaType.APPLICATION_JSON)
				.content("{\"moldAssetId\":\"" + moldId + "\",\"warehouseCode\":\"MOLD-01\",\"warehouseOperatorCode\":\"M001\",\"workerCode\":\"W001\",\"reportingMode\":\"SELF_REPORTED_QUANTITY\",\"settlementUnit\":\"PCS\",\"compensationMode\":\"PIECE_PCS\",\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/start", injection).header("X-Operator-Code", "W001")).andExpect(status().isOk());
		mvc.perform(post("/api/tasks/{id}/reports", injection).header("X-Operator-Code", "W001").contentType(MediaType.APPLICATION_JSON)
				.content("{\"operationId\":\"" + UUID.randomUUID() + "\",\"goodQuantity\":12,\"scrapQuantity\":0}"))
			.andExpect(status().isCreated());

		create("/api/resources", "{\"assetCode\":\"CART-TEST-" + suffix + "\",\"assetName\":\"Cart test carrier\",\"assetType\":\"CARRIER\",\"locationCode\":\"MID-WAX\"}");
		MvcResult loaded = mvc.perform(post("/api/cart-transfers/load").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sourceTaskId\":\"" + injection + "\",\"cartCode\":\"CART-TEST-" + suffix + "\",\"quantity\":12,\"loadedBy\":\"W001\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("LOADED"))
			.andExpect(jsonPath("$.targetOperationName").value("修蜡"))
			.andReturn();
		String transferId = JsonPath.read(loaded.getResponse().getContentAsString(), "$.id");

		mvc.perform(post("/api/cart-transfers/{id}/receive", transferId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"receivedQuantity\":11,\"exceptionReason\":\"现场复点少一件，待质量复核\",\"receivedBy\":\"R001\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("EXCEPTION"))
			.andExpect(jsonPath("$.exceptionReason").value("现场复点少一件，待质量复核"));

		mvc.perform(get("/api/resources"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.assetCode == 'CART-TEST-" + suffix.toUpperCase(Locale.ROOT) + "')].status").value("AVAILABLE"));
		mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.operationCode == 'WAX_REPAIR')].status").value("READY"));
	}

	private String task(String orderId, String code) throws Exception {
		String body = mvc.perform(get("/api/tasks").param("orderId", orderId)).andReturn().getResponse().getContentAsString();
		List<String> ids = JsonPath.read(body, "$[?(@.operationCode == '" + code + "')].id");
		return ids.getFirst();
	}

	private String create(String path, String json) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
