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
class CustomerSensitiveRequestApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void letsFrontDeskRequestCustomerCreationAndEitherReviewerApproveItsExecution() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		MvcResult created = mvc.perform(post("/api/customers/sensitive-requests")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"requesterCode\":\"FD01\",\"name\":\"受控客户 " + suffix
					+ "\",\"contactName\":\"王工\",\"contactPhone\":\"13800000000\",\"salesOwner\":\"销售一组\",\"requestNote\":\"新客户建档申请\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn();
		String requestId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

		mvc.perform(get("/api/customers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.name == '受控客户 " + suffix + "')]").isEmpty());

		mvc.perform(post("/api/customers/sensitive-requests/{id}/approve", requestId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reviewerCode\":\"GM001\",\"reviewNote\":\"同意建档\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.reviewedBy").value("GM001"));

		mvc.perform(get("/api/customers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.name == '受控客户 " + suffix + "')].salesOwner").value("销售一组"));
	}
}
