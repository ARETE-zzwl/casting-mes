package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
class FrontDeskEngineeringDispatchFlowApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void movesFrontDeskOrderThroughEngineeringConfirmationIntoPrintableSupervisorDispatchSheet() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		String customerId = create("/api/customers", """
			{"code":"FD-C-%s","name":"Front Desk Customer"}
			""".formatted(suffix));
		String productId = create("/api/products", """
			{"code":"FD-P-%s","name":"Front Desk Part","routeType":"MID_TEMP_WAX","routeVersion":"V1"}
			""".formatted(suffix));
		String orderId = create("/api/orders", """
			{"orderNo":"FD-SO-%s","customerId":"%s","priority":"URGENT","createdBy":"FD01",
			"lines":[{"productId":"%s","quantity":12,"unit":"PCS"}]}
			""".formatted(suffix, customerId, productId));
		OrderReviewTestSupport.submit(mvc, orderId);

		mvc.perform(get("/api/orders/{orderId}", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.createdBy").value("FD01"));

		mvc.perform(post("/api/orders/{orderId}/engineering-confirmation", orderId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"engineerCode":"E001","processCardVersion":"ENG-%s","engineeringParameters":"304; pouring 1,580C; shell 6 layers",
					 "engineeringOperationParameters":"{\\"WAX_INJECTION\\":\\"68C; 0.55MPa; hold 12s\\",\\"POURING\\":\\"1,580C; 304\\"}"}
			""".formatted(suffix)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.engineeringConfirmedBy").value("E001"))
			.andExpect(jsonPath("$.processCardVersion").value("ENG-" + suffix));

		OrderReviewTestSupport.releaseAfterRequiredReviews(mvc, orderId);
		MvcResult tasks = mvc.perform(get("/api/tasks").param("orderId", orderId))
			.andExpect(status().isOk()).andReturn();
		List<String> taskIds = JsonPath.read(tasks.getResponse().getContentAsString(), "$[?(@.operationCode == 'WAX_INJECTION')].id");
		List<String> repairTaskIds = JsonPath.read(tasks.getResponse().getContentAsString(), "$[?(@.operationCode == 'WAX_REPAIR')].id");
		List<String> repairStatuses = JsonPath.read(tasks.getResponse().getContentAsString(), "$[?(@.operationCode == 'WAX_REPAIR')].status");
		assertEquals(List.of("BLOCKED"), repairStatuses);
		mvc.perform(post("/api/tasks/{taskId}/assignment", repairTaskIds.getFirst())
				.contentType(MediaType.APPLICATION_JSON).content("{\"workerCode\":\"WR01\",\"supervisorCode\":\"S001\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TASK_STATE_CONFLICT"));

		mvc.perform(post("/api/documents/previews")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"documentType":"WORKSHOP_JOB_SHEET","entityId":"%s","actorCode":"S001",
					"selectedFields":["TASK_NO","WORK_ORDER_NO","PRODUCT","OPERATION","BATCH_NO","PLANNED_QUANTITY","PROCESS_CARD_VERSION","ENGINEERING_PARAMETERS","TASK_QR_PAYLOAD","QUANTITY_STATUS"],"outputType":"PRINT"}
					""".formatted(taskIds.getFirst())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.fields[?(@.code == 'PROCESS_CARD_VERSION')].value").value("ENG-" + suffix))
			.andExpect(jsonPath("$.fields[?(@.code == 'ENGINEERING_PARAMETERS')].value").value("68C; 0.55MPa; hold 12s"))
			.andExpect(jsonPath("$.fields[?(@.code == 'TASK_QR_PAYLOAD')].value").isNotEmpty());
	}

	private String create(String path, String json) throws Exception {
		MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
			.andExpect(status().isCreated()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
