package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ConfigurableScopeAndReportFormApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void letsAdministratorsMaintainAnOperatorsLineScope() throws Exception {
		mvc.perform(post("/api/access/users/LWX01/scopes").contentType(MediaType.APPLICATION_JSON).content("""
			{"supervisorRoutes":[],"operatorRoutes":["LOW_TEMP_WAX"],"supervisorOperations":[]}
			"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.employeeCode").value("LWX01"))
			.andExpect(jsonPath("$.operatorRoutes[0]").value("LOW_TEMP_WAX"));

		mvc.perform(get("/api/access/users/LWX01/scopes"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.operatorRoutes[0]").value("LOW_TEMP_WAX"));
	}

	@Test
	void savesOperationSpecificReportFields() throws Exception {
		mvc.perform(post("/api/report-form-profiles").contentType(MediaType.APPLICATION_JSON).content("""
			{"operationCode":"POURING","showPhoto":true,"requirePhoto":true,
			 "showDevice":true,"requireDevice":true,"showWorkstation":true,
			 "requireWorkstation":false,"updatedBy":"ADMIN"}
			"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.operationCode").value("POURING"))
			.andExpect(jsonPath("$.requirePhoto").value(true))
			.andExpect(jsonPath("$.requireDevice").value(true));

		mvc.perform(get("/api/report-form-profiles/POURING"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.showWorkstation").value(true));
	}
}
