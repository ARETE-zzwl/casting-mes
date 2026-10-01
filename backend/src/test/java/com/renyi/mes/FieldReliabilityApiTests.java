package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class FieldReliabilityApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void storesControlledAttachmentsAndMaintainsLockedMoldLifecycle() throws Exception {
		MockMultipartFile drawing = new MockMultipartFile("file", "valve-drawing.pdf", "application/pdf", "%PDF-1.4 demo".getBytes());
		mvc.perform(multipart("/api/files/upload").file(drawing).param("category", "ORDER_DRAWING"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("/uploads/order_drawing/")))
			.andExpect(jsonPath("$.originalName").value("valve-drawing.pdf"));

		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String moldId = create("/api/resources", """
			{"assetCode":"LIFE-%s","assetName":"Lifecycle mold","assetType":"MOLD","locationCode":"MOLD-A","lifeLimit":100}
			""".formatted(suffix));
		mvc.perform(post("/api/molds/{id}/configuration", moldId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"maintenanceIntervalDays\":30}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.maintenanceIntervalDays").value(30));
		mvc.perform(post("/api/molds/{id}/maintenance-records", moldId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"recordType\":\"MAINTENANCE\",\"description\":\"Monthly inspection\",\"performedBy\":\"M001\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.recordType").value("MAINTENANCE"));
		mvc.perform(post("/api/molds/{id}/lock", moldId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Crack found\",\"operatorCode\":\"M001\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.lockReason").value("Crack found"));
		mvc.perform(get("/api/molds/{id}/maintenance-records", moldId))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].description").value("Monthly inspection"));
	}

	private String create(String path, String body) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
