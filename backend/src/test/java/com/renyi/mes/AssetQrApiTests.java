package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class AssetQrApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void generatesBindsAndReprintsAssetLabelsWithoutChangingTheToken() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String moldId = createResource("QR-MOLD-" + suffix, "QR test mold", "MOLD");
		String cartId = createResource("QR-CART-" + suffix, "QR test cart", "CARRIER");

		MvcResult generated = mvc.perform(post("/api/asset-qr-codes/batch").contentType(MediaType.APPLICATION_JSON)
				.content("{\"intendedAssetType\":\"MOLD\",\"count\":4,\"createdBy\":\"A001\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.length()").value(4))
			.andExpect(jsonPath("$[0].status").value("UNBOUND"))
			.andReturn();
		String body = generated.getResponse().getContentAsString();
		String firstToken = JsonPath.read(body, "$[0].qrToken");
		String secondToken = JsonPath.read(body, "$[1].qrToken");
		String thirdToken = JsonPath.read(body, "$[2].qrToken");
		String firstLabelId = JsonPath.read(body, "$[0].id");
		String secondLabelId = JsonPath.read(body, "$[1].id");
		String thirdLabelId = JsonPath.read(body, "$[2].id");
		String fourthLabelId = JsonPath.read(body, "$[3].id");

		mvc.perform(post("/api/asset-qr-codes/exports/dxf").contentType(MediaType.APPLICATION_JSON)
				.content("{\"labelIds\":[\"" + fourthLabelId + "\"],\"actorCode\":\"A001\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(fourthLabelId))
			.andExpect(jsonPath("$[0].status").value("UNBOUND"))
			.andExpect(jsonPath("$[0].printCount").value(1));
		mvc.perform(post("/api/asset-qr-codes/exports/png").contentType(MediaType.APPLICATION_JSON)
				.content("{\"labelIds\":[\"" + thirdLabelId + "\"],\"actorCode\":\"A001\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(thirdLabelId))
			.andExpect(jsonPath("$[0].status").value("UNBOUND"))
			.andExpect(jsonPath("$[0].printCount").value(1));

		mvc.perform(get("/api/asset-qr-codes/exports/ezcad-variable-data")
				.param("labelIds", firstLabelId, secondLabelId).param("actorCode", "A001"))
			.andExpect(status().isOk())
			.andExpect(result -> {
				String content = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
				if (!content.equals("MES:ASSET_QR:" + firstToken + "\r\nMES:ASSET_QR:" + secondToken + "\r\n")) {
					throw new AssertionError("unexpected EZCAD variable data: " + content);
				}
				String disposition = result.getResponse().getHeader("Content-Disposition");
				if (disposition == null || !disposition.contains(".txt")) throw new AssertionError("missing EZCAD variable export filename");
			});

		mvc.perform(post("/api/asset-qr-codes/bind").contentType(MediaType.APPLICATION_JSON)
				.content("{\"scannedValue\":\"MES:ASSET_QR:" + firstToken + "\",\"assetId\":\"" + moldId + "\",\"boundBy\":\"M001\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("BOUND"))
			.andExpect(jsonPath("$.assetId").value(moldId));

		String locationCode = ("QR-LOC-" + suffix).toUpperCase();
		mvc.perform(post("/api/factory/mold-requests/locations").contentType(MediaType.APPLICATION_JSON)
				.content("{\"locationCode\":\"" + locationCode + "\",\"locationName\":\"二维码测试库位\",\"capacity\":1,\"operatorCode\":\"M001\"}"))
			.andExpect(status().isCreated());
		mvc.perform(post("/api/factory/mold-requests/receipts").contentType(MediaType.APPLICATION_JSON)
				.content("{\"scannedValue\":\"MES:ASSET_QR:" + secondToken + "\",\"assetName\":\"扫码入库测试模具\",\"locationCode\":\"" + locationCode + "\",\"ownershipType\":\"COMPANY_OWNED\",\"operatorCode\":\"M001\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.assetType").value("MOLD"))
			.andExpect(jsonPath("$.locationCode").value(locationCode));
		mvc.perform(post("/api/asset-qr-codes/bind").contentType(MediaType.APPLICATION_JSON)
				.content("{\"scannedValue\":\"MES:ASSET_QR:" + secondToken + "\",\"assetId\":\"" + moldId + "\",\"boundBy\":\"M001\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ASSET_QR_ALREADY_BOUND"));

		mvc.perform(post("/api/asset-qr-codes/bind").contentType(MediaType.APPLICATION_JSON)
				.content("{\"scannedValue\":\"MES:ASSET_QR:" + thirdToken + "\",\"assetId\":\"" + cartId + "\",\"boundBy\":\"C001\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ASSET_QR_TYPE_MISMATCH"));

		MvcResult issued = mvc.perform(post("/api/asset-qr-codes/assets/{id}/issue", cartId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorCode\":\"A001\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("BOUND"))
			.andExpect(jsonPath("$.printCount").value(1))
			.andReturn();
		String labelId = JsonPath.read(issued.getResponse().getContentAsString(), "$.id");
		mvc.perform(post("/api/asset-qr-codes/{id}/reprint", labelId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorCode\":\"A001\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.printCount").value(2));
	}

	private String createResource(String code, String name, String type) throws Exception {
		MvcResult result = mvc.perform(post("/api/resources").contentType(MediaType.APPLICATION_JSON)
				.content("{\"assetCode\":\"" + code + "\",\"assetName\":\"" + name + "\",\"assetType\":\"" + type + "\"}"))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
