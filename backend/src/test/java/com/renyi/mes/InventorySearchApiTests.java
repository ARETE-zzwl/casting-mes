package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class InventorySearchApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void filtersRawMaterialBalancesAndMovementsOnTheServerWithPageMetadata() throws Exception {
		postMovement("SEARCH-304", "304 Stainless Steel Bar", "PO-SEARCH-304", "receipt 304");
		postMovement("SEARCH-WAX", "Mid-temp wax", "PO-SEARCH-WAX", "receipt wax");

		mvc.perform(get("/api/inventory/balances/query")
				.param("viewerCode", "K001").param("warehouseCode", "RM-01")
				.param("keyword", "304").param("unit", "KG").param("stockStatus", "POSITIVE")
				.param("page", "0").param("size", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.totalPages").value(1))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].itemCode").value("SEARCH-304"));

		mvc.perform(get("/api/inventory/movements/query")
				.param("viewerCode", "K001").param("warehouseCode", "RM-01")
				.param("keyword", "PO-SEARCH-304").param("movementType", "RECEIPT")
				.param("operatorCode", "K001").param("fromDate", LocalDate.now().toString())
				.param("toDate", LocalDate.now().toString()).param("page", "0").param("size", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.items[0].referenceNo").value("PO-SEARCH-304"));
	}

	@Test
	void rejectsInvalidSearchPagingAndDateRanges() throws Exception {
		mvc.perform(get("/api/inventory/balances/query")
				.param("viewerCode", "K001").param("page", "-1"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_PAGE_REQUEST"));
		mvc.perform(get("/api/inventory/movements/query")
				.param("viewerCode", "K001").param("fromDate", "2026-08-09").param("toDate", "2026-08-08"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_MOVEMENT_DATE_RANGE"));
	}

	private void postMovement(String itemCode, String itemName, String referenceNo, String remark) throws Exception {
		mvc.perform(post("/api/inventory/movements")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"operationId":"%s","warehouseCode":"RM-01","itemCode":"%s","itemName":"%s","unit":"KG",
					 "movementType":"RECEIPT","quantity":10,"referenceType":"采购送货单","referenceNo":"%s","operatorCode":"K001","remark":"%s"}
					""".formatted(UUID.randomUUID(), itemCode, itemName, referenceNo, remark)))
			.andExpect(status().isCreated());
	}
}
