package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
class SchedulingQueueApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void prioritizesSampleThenUrgentThenNormalAndLocksStartedWork() throws Exception {
		String normalOrder = createAndRelease("NORMAL", 3);
		String urgentOrder = createAndRelease("URGENT", 2);
		String sampleOrder = createAndRelease("SAMPLE", 1);

		MvcResult queueResult = mvc.perform(get("/api/scheduling/queue")
				.param("lineCode", "SAND_OUTSOURCE")
				.param("operationCode", "OUTSOURCE_DISPATCH"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].orderId").value(sampleOrder))
			.andExpect(jsonPath("$[1].orderId").value(urgentOrder))
			.andExpect(jsonPath("$[2].orderId").value(normalOrder))
			.andReturn();
		List<Map<String, Object>> queue = JsonPath.read(
			queueResult.getResponse().getContentAsString(), "$");
		String sampleTaskId = queue.getFirst().get("taskId").toString();

		String resourceId = postAndRead("/api/resources", """
			{
			  "assetCode":"AUTO-SHELL-%s",
			  "assetName":"自动制壳线",
			  "assetType":"EQUIPMENT",
			  "locationCode":"MID_WAX"
			}
			""".formatted(shortId()), "$.id");
		mvc.perform(post("/api/scheduling/queue/{taskId}/dispatch", sampleTaskId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"workerCode":"GM001","resourceAssetId":"%s","supervisorCode":"GM001"}
					""".formatted(resourceId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.task.status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.resource.status").value("OCCUPIED"));

		mvc.perform(post("/api/scheduling/queue/{taskId}/rank", sampleTaskId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{" + "\"manualRank\":99}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("SCHEDULE_ITEM_LOCKED"));
	}

	private String createAndRelease(String priority, int deliveryOffset) throws Exception {
		String suffix = shortId();
		String productId = postAndRead("/api/products", """
			{"code":"SQ-P-%s","name":"Queue Product","routeType":"SAND_OUTSOURCE","routeVersion":"V2"}
			""".formatted(suffix), "$.id");
		String customerId = postAndRead("/api/customers", """
			{"code":"SQ-C-%s","name":"Queue Customer"}
			""".formatted(suffix), "$.id");
		String orderId = postAndRead("/api/orders", """
			{
			  "orderNo":"SQ-O-%s",
			  "customerId":"%s",
			  "priority":"%s",
			  "requestedDeliveryDate":"%s",
			  "lines":[{"productId":"%s","quantity":10,"unit":"PCS"}]
			}
			""".formatted(suffix, customerId, priority, LocalDate.now().plusDays(deliveryOffset), productId), "$.id");
		OrderReviewTestSupport.releaseAndLaunchAfterRequiredReviews(mvc, orderId);
		return orderId;
	}

	private String postAndRead(String path, String json, String jsonPath) throws Exception {
		MvcResult result = mvc.perform(post(path)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json))
			.andExpect(status().isCreated())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
	}

	private static String shortId() {
		return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
	}
}
