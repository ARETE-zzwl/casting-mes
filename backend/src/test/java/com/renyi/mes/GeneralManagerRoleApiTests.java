package com.renyi.mes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class GeneralManagerRoleApiTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void generalManagerHasDecisionPermissionsButNoExecutionPermissions() throws Exception {
		MvcResult result = mvc.perform(get("/api/access/users"))
			.andExpect(status().isOk())
			.andReturn();
		List<Map<String, Object>> managers = JsonPath.parse(result.getResponse().getContentAsString())
			.read("$[?(@.employeeCode == 'GM001')]");
		Map<String, Object> manager = managers.getFirst();

		assertEquals("GENERAL_MANAGER", manager.get("primaryRole"));
		List<?> permissions = (List<?>) manager.get("permissions");
		assertTrue(permissions.contains("DASHBOARD_VIEW"));
		assertTrue(permissions.contains("TRACE_VIEW"));
		assertTrue(permissions.contains("PRINT_STATISTICS"));
		assertTrue(permissions.contains("TASK_DISPATCH"));
		assertFalse(permissions.contains("TASK_EXECUTE"));
		assertFalse(permissions.contains("INVENTORY_MANAGE"));
	}
}
