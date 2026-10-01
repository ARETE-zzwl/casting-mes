package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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
class MoldCustodyLifecycleApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void tracksMaintenanceCheckoutOverdueReminderAndReturnToStock() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String assetId = postId("/api/resources", """
			{"assetCode":"CUSTODY-%s","assetName":"Maintenance Mold","assetType":"MOLD","locationCode":"MOLD-A-01"}
			""".formatted(suffix));

		MvcResult checkout = mvc.perform(post("/api/factory/mold-requests/external-movements")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"moldAssetId":"%s","reasonCode":"MAINTENANCE","reasonNote":"Crack repair","counterpartyName":"Tooling Service","expectedReturnDate":"%s","operatorCode":"M001"}
					""".formatted(assetId, LocalDate.now().minusDays(1))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("OPEN"))
			.andExpect(jsonPath("$.reasonCode").value("MAINTENANCE"))
			.andReturn();
		String movementId = JsonPath.read(checkout.getResponse().getContentAsString(), "$.id");

		mvc.perform(get("/api/factory/mold-requests/maintenance-overdue"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + movementId + "')]").isNotEmpty());

		mvc.perform(post("/api/factory/mold-requests/external-movements/{id}/return", movementId)
				.contentType(MediaType.APPLICATION_JSON).content("{\"operatorCode\":\"M001\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RETURNED"));
		MvcResult assets = mvc.perform(get("/api/resources")).andExpect(status().isOk()).andReturn();
		List<String> custodyStatuses = JsonPath.read(assets.getResponse().getContentAsString(),
			"$[?(@.id == '" + assetId + "')].moldCustodyStatus");
		assertThat(custodyStatuses).containsExactly("IN_STOCK");
	}

	private String postId(String path, String content) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(content))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
