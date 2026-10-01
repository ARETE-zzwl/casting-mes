package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DispatchAndWarehouseRolesApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void exposesOperationMatchedWorkersAndSeparatedWarehousePermissions() throws Exception {
		mvc.perform(get("/api/planning/dispatch-recommendations").param("operationCode", "OPTIONAL_FINISHING"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tasks").isArray())
			.andExpect(jsonPath("$.workers[?(@.employeeCode == 'W002')].recommended").value(true));

		mvc.perform(get("/api/access/users"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.employeeCode == 'G001')].permissions[*]").value(org.hamcrest.Matchers.hasItem("LOGISTICS_MANAGE")))
			.andExpect(jsonPath("$[?(@.employeeCode == 'W002')].permissions[*]").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("FULFILLMENT_MANAGE"))));
	}

	@Test
	void acceptsAnOptionalProductModelImage() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("""
			{"code":"IMG-%s","name":"Model Image Part","routeType":"MID_TEMP_WAX","routeVersion":"V1","modelImageUrl":"https://example.test/model.png"}
			""".formatted(suffix)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.modelImageUrl").value("https://example.test/model.png"));
	}
}
