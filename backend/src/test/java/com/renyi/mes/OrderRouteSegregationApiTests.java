package com.renyi.mes;

import static org.assertj.core.api.Assertions.assertThat;
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
class OrderRouteSegregationApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void keepsMidAndLowTemperatureWaxProductsInSeparateOrdersAndLists() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		String customerId = create("/api/customers", """
			{"code":"RS-C-%s","name":"Route Segregation Customer"}
			""".formatted(suffix));
		String midProduct = create("/api/products", """
			{"code":"RS-M-%s","name":"Mid Wax Part","routeType":"MID_TEMP_WAX","routeVersion":"V1"}
			""".formatted(suffix));
		String lowProduct = create("/api/products", """
			{"code":"RS-L-%s","name":"Low Wax Part","routeType":"LOW_TEMP_WAX","routeVersion":"V1"}
			""".formatted(suffix));

		mvc.perform(post("/api/orders")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"orderNo":"RS-MIX-%s","customerId":"%s","priority":"NORMAL","lines":[
					{"productId":"%s","quantity":3,"unit":"PCS"},
					{"productId":"%s","quantity":4,"unit":"PCS"}]}
					""".formatted(suffix, customerId, midProduct, lowProduct)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("ORDER_ROUTE_MIXED"));

		create("/api/orders", """
			{"orderNo":"RS-MID-%s","customerId":"%s","priority":"NORMAL","lines":[{"productId":"%s","quantity":3,"unit":"PCS"}]}
			""".formatted(suffix, customerId, midProduct));
		MvcResult filtered = mvc.perform(get("/api/orders").param("routeType", "MID_TEMP_WAX"))
			.andExpect(status().isOk())
			.andReturn();
		List<String> routeTypes = JsonPath.read(filtered.getResponse().getContentAsString(), "$[*].routeType");
		assertThat(routeTypes).isNotEmpty().containsOnly("MID_TEMP_WAX");
	}

	private String create(String path, String body) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
