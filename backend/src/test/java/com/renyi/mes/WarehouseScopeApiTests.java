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
class WarehouseScopeApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void separatesWarehouseLedgersAndEnforcesOperatorScope() throws Exception {
		mvc.perform(post("/api/inventory/movements")
				.contentType(MediaType.APPLICATION_JSON)
				.content(inventoryMovement("RM-01", "K001", "RECEIPT")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.movement.warehouseCode").value("RM-01"));

		mvc.perform(post("/api/inventory/movements")
				.contentType(MediaType.APPLICATION_JSON)
				.content(inventoryMovement("RM-01", "G001", "RECEIPT")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("WAREHOUSE_SCOPE_FORBIDDEN"));

		mvc.perform(post("/api/inventory/movements")
				.contentType(MediaType.APPLICATION_JSON)
				.content(inventoryMovement("FG-01", "G001", "RECEIPT")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("FINISHED_GOODS_LOT_LEDGER_REQUIRED"));

		mvc.perform(post("/api/inventory/movements")
				.contentType(MediaType.APPLICATION_JSON)
				.content(inventoryMovement("MOLD-01", "M001", "RECEIPT")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MOLD_CUSTODY_LEDGER_REQUIRED"));

		mvc.perform(get("/api/inventory/balances").param("viewerCode", "K001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.warehouseCode == 'RM-01')]").isNotEmpty());
		mvc.perform(get("/api/inventory/balances").param("viewerCode", "G001"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isEmpty());

		mvc.perform(get("/api/fulfillment/lots").param("viewerCode", "K001"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("WAREHOUSE_SCOPE_FORBIDDEN"));
		mvc.perform(get("/api/fulfillment/lots").param("viewerCode", "G001"))
			.andExpect(status().isOk());
	}

	private static String inventoryMovement(String warehouseCode, String operatorCode, String movementType) {
		return """
			{
			  "operationId":"%s",
			  "warehouseCode":"%s",
			  "itemCode":"AUDIT-RAW-%s",
			  "itemName":"Warehouse audit material",
			  "unit":"KG",
			  "movementType":"%s",
			  "quantity":10,
			  "referenceType":"采购送货单",
			  "referenceNo":"PO-AUDIT-%s",
			  "operatorCode":"%s",
			  "remark":"scope audit"
			}
			""".formatted(UUID.randomUUID(), warehouseCode, UUID.randomUUID().toString().substring(0, 8),
			movementType, UUID.randomUUID().toString().substring(0, 8), operatorCode);
	}
}
