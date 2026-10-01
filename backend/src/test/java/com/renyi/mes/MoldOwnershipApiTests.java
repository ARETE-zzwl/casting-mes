package com.renyi.mes;

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
class MoldOwnershipApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void recordsCustomerCustodySeparatelyFromCompanyOwnedMolds() throws Exception {
		mvc.perform(post("/api/resources")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "assetCode":"CUSTOMER-MOLD-%s",
					  "assetName":"客户寄存叶轮模具",
					  "assetType":"MOLD",
					  "ownershipType":"CUSTOMER_OWNED",
					  "ownerName":"华东精铸客户"
					}
					""".formatted(UUID.randomUUID().toString().substring(0, 8))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.ownershipType").value("CUSTOMER_OWNED"))
			.andExpect(jsonPath("$.ownerName").value("华东精铸客户"));
	}
}
