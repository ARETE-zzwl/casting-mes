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
class PlatformOperationsApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void managesOrganizationResourceOccupationAndLife() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String unitCode = "TEAM-" + suffix;
		post("/api/organization/units", """
			{"code":"%s","name":"精铸班组","unitType":"TEAM"}
			""".formatted(unitCode))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.unitType").value("TEAM"));
		post("/api/organization/members", """
			{"employeeCode":"EMP-%s","name":"测试员工","unitCode":"%s","roleCode":"OPERATOR"}
			""".formatted(suffix, unitCode))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.unitCode").value(unitCode.toUpperCase()));

		MvcResult assetResult = post("/api/resources", """
			{
			  "assetCode":"MOLD-%s",
			  "assetName":"叶轮模具",
			  "assetType":"MOLD",
			  "lifeLimit":1
			}
			""".formatted(suffix))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("AVAILABLE"))
			.andReturn();
		String assetId = JsonPath.read(assetResult.getResponse().getContentAsString(), "$.id");

		String occupation = """
			{"businessKey":"TASK-%s","operatorCode":"W001","note":"开工占用"}
			""".formatted(suffix);
		post("/api/resources/" + assetId + "/occupations", occupation)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("OCCUPIED"));
		post("/api/resources/" + assetId + "/occupations", occupation)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_AVAILABLE"));
		post("/api/resources/" + assetId + "/release",
			"{\"operatorCode\":\"W001\",\"consumeLife\":true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("EXHAUSTED"))
			.andExpect(jsonPath("$.lifeUsed").value(1));
		post("/api/resources/" + assetId + "/occupations", occupation)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_AVAILABLE"));

		mvc.perform(get("/api/resources/occupations"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value("RELEASED"));
	}

	@Test
	void managesNotificationsIntegrationRetriesAndOperationsOverview() throws Exception {
		MvcResult notificationResult = post("/api/notifications", """
			{
			  "recipientCode":"W001",
			  "category":"TASK",
			  "title":"新任务待接收",
			  "content":"任务已进入待执行队列",
			  "businessLink":"/tasks"
			}
			""")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("UNREAD"))
			.andReturn();
		String notificationId =
			JsonPath.read(notificationResult.getResponse().getContentAsString(), "$.id");
		post("/api/notifications/" + notificationId + "/read", "{\"recipientCode\":\"W002\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("NOTIFICATION_RECIPIENT_MISMATCH"));
		post("/api/notifications/" + notificationId + "/read", "{\"recipientCode\":\"W001\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("READ"));

		String operationId = UUID.randomUUID().toString();
		String integrationJson = """
			{
			  "operationId":"%s",
			  "interfaceCode":"ERP_ORDER_SYNC",
			  "businessKey":"ORDER-001",
			  "direction":"OUTBOUND",
			  "payload":"{\\"orderNo\\":\\"ORDER-001\\"}"
			}
			""".formatted(operationId);
		MvcResult jobResult = post("/api/integration/jobs", integrationJson)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.duplicate").value(false))
			.andReturn();
		String jobId = JsonPath.read(jobResult.getResponse().getContentAsString(), "$.job.id");
		post("/api/integration/jobs", integrationJson)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.duplicate").value(true));
		post("/api/integration/jobs/" + jobId + "/failure", "{\"error\":\"ERP timeout\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.attemptCount").value(1));
		post("/api/integration/jobs/" + jobId + "/retry", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RETRYING"));
		post("/api/integration/jobs/" + jobId + "/success", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.attemptCount").value(2));
		post("/api/integration/jobs/" + jobId + "/retry", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INTEGRATION_RETRY_INVALID"));

		mvc.perform(get("/api/operations/overview"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.occupiedResources").exists())
			.andExpect(jsonPath("$.publishedConfigurations").exists())
			.andExpect(jsonPath("$.generatedAt").exists());
	}

	private org.springframework.test.web.servlet.ResultActions post(String path, String content)
			throws Exception {
		var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path);
		if (content != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(content);
		}
		return mvc.perform(request);
	}
}
