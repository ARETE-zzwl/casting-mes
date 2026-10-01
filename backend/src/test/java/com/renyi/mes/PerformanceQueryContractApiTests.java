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
class PerformanceQueryContractApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void pagesOperationalLedgersAndReturnsFulfillmentSummary() throws Exception {
		mvc.perform(get("/api/execution/reports/query").param("viewerCode", "GM001").param("page", "0").param("size", "1"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.items").isArray());
		mvc.perform(get("/api/execution/reports/query").param("viewerCode", "GM001").param("page", "-1"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_PAGE_REQUEST"));
		mvc.perform(get("/api/handoff-exceptions/query").param("keyword", "NO-MATCH").param("page", "0").param("size", "1"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.items").isArray());
		mvc.perform(get("/api/fulfillment/summary").param("viewerCode", "G001"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.availableQuantity").exists())
			.andExpect(jsonPath("$.pendingCount").exists()).andExpect(jsonPath("$.inTransitCount").exists())
			.andExpect(jsonPath("$.deliveredCount").exists());
	}

	@Test
	void resolvesAssetCodeOnTheServerWithoutLoadingAssetLists() throws Exception {
		String code = "SCAN-PERF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		mvc.perform(post("/api/resources").contentType(MediaType.APPLICATION_JSON)
				.content("{\"assetCode\":\"" + code + "\",\"assetName\":\"Scan contract carrier\",\"assetType\":\"CARRIER\"}"))
			.andExpect(status().isCreated());
		mvc.perform(get("/api/scans/resolve").param("value", code))
			.andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("asset"))
			.andExpect(jsonPath("$.asset.assetCode").value(code));
	}
}
