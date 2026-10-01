package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class WarehouseLedgerApiTests {

    @Autowired
    private MockMvc mvc;

    @Test
	void pagesMoldLotsAndDeliveriesOnTheServer() throws Exception {
        mvc.perform(get("/api/resources/query").param("assetType", "MOLD").param("keyword", "SIM-MOLD").param("page", "0").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.items").isArray());
		mvc.perform(get("/api/resources/query").param("assetType", "MOLD").param("keyword", "SIM-MOLD")
				.param("custodyStatus", "IN_STOCK").param("ownershipType", "COMPANY_OWNED")
				.param("status", "AVAILABLE").param("locationCode", "MOLD-01").param("page", "0").param("size", "20"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.items[?(@.assetType != 'MOLD')]").isEmpty())
			.andExpect(jsonPath("$.items[?(@.moldCustodyStatus != 'IN_STOCK')]").isEmpty())
			.andExpect(jsonPath("$.items[?(@.ownershipType != 'COMPANY_OWNED')]").isEmpty())
			.andExpect(jsonPath("$.items[?(@.status != 'AVAILABLE')]").isEmpty())
			.andExpect(jsonPath("$.items[?(@.locationCode != 'MOLD-01')]").isEmpty());
        mvc.perform(get("/api/fulfillment/lots/query").param("viewerCode", "G001").param("page", "0").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/fulfillment/deliveries/query").param("viewerCode", "G001").param("status", "ALL").param("page", "0").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void createsAsyncInventoryExportAndDownloadsCsv() throws Exception {
        String itemCode = "EXPORT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        mvc.perform(post("/api/inventory/movements").contentType(MediaType.APPLICATION_JSON).content("""
            {"operationId":"%s","warehouseCode":"RM-01","itemCode":"%s","itemName":"Export test material","unit":"KG",
             "movementType":"RECEIPT","quantity":2,"referenceType":"TEST","referenceNo":"EXPORT-TEST","operatorCode":"K001","remark":"async export"}
            """.formatted(UUID.randomUUID(), itemCode))).andExpect(status().isCreated());

        MvcResult created = mvc.perform(post("/api/inventory/exports").contentType(MediaType.APPLICATION_JSON).content("""
            {"operationId":"%s","viewerCode":"K001","warehouseCode":"RM-01","keyword":"EXPORT-TEST"}
            """.formatted(UUID.randomUUID()))).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String statusValue = "PENDING";
        for (int attempt = 0; attempt < 30 && !"COMPLETED".equals(statusValue); attempt++) {
            Thread.sleep(100);
            MvcResult statusResult = mvc.perform(get("/api/inventory/exports/" + id).param("viewerCode", "K001")).andExpect(status().isOk()).andReturn();
            statusValue = JsonPath.read(statusResult.getResponse().getContentAsString(), "$.status");
        }
        if (!"COMPLETED".equals(statusValue)) throw new AssertionError("inventory export did not complete: " + statusValue);
        mvc.perform(get("/api/inventory/exports/" + id + "/download").param("viewerCode", "K001"))
            .andExpect(status().isOk()).andExpect(result -> {
                String csv = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                if (!csv.contains("流水号") || !csv.contains("EXPORT-TEST")) throw new AssertionError("CSV content missing expected fields");
            });
    }
}
