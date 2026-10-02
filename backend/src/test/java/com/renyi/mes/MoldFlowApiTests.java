package com.renyi.mes;

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
class MoldFlowApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void requiresEngineeringApprovalBeforeWarehouseIssuesAndReturnsAMold() throws Exception {
		String assetId = postAndRead("/api/resources", """
			{"assetCode":"MOLD-%s","assetName":"泵体蜡模","assetType":"MOLD"}
			""".formatted(shortId()), "$.id");
		String requestId = postAndRead("/api/factory/mold-requests", """
			{"moldAssetId":"%s","productCode":"PUMP-01","requestedBy":"S001"}
			""".formatted(assetId), "$.id");

		mvc.perform(post("/api/factory/mold-requests/{id}/approval", requestId)
				.contentType(MediaType.APPLICATION_JSON).content("{\"engineerCode\":\"E001\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
		mvc.perform(post("/api/factory/mold-requests/{id}/issue", requestId)
				.contentType(MediaType.APPLICATION_JSON).content("{\"warehouseCode\":\"MOLD_WH\",\"operatorCode\":\"K001\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ISSUED"));
		mvc.perform(post("/api/factory/mold-requests/{id}/return", requestId)
				.contentType(MediaType.APPLICATION_JSON).content("{\"operatorCode\":\"K001\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RETURNED"));
	}

	@Test
	void marksARequestWithoutAnExistingMoldAsCustomMoldRequired() throws Exception {
		mvc.perform(post("/api/factory/mold-requests")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"productCode\":\"NEW-PART\",\"requestedBy\":\"S001\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("CUSTOM_MOLD_REQUIRED"));
	}

	private String postAndRead(String path, String json, String jsonPath) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
	}

	private static String shortId() {
		return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
	}
}
