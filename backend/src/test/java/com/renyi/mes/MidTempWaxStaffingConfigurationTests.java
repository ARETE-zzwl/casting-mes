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
class MidTempWaxStaffingConfigurationTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void seedsMidTempWaxRosterWithSupervisorPrintingAndSelfServiceCartHandoffs() throws Exception {
		List<Map<String, Object>> users = users();

		assertEquals(5, users.stream().filter(user -> employeeCode(user).startsWith("WX")).count());
		assertEquals(5, users.stream().filter(user -> employeeCode(user).startsWith("WR")).count());
		assertEquals(1, users.stream().filter(user -> employeeCode(user).equals("TA01")).count());
		assertEquals(2, users.stream().filter(user -> employeeCode(user).startsWith("DW")).count());
		assertEquals(2, users.stream().filter(user -> employeeCode(user).startsWith("PO")).count());
		assertEquals(2, users.stream().filter(user -> employeeCode(user).startsWith("KO")).count());
		assertEquals(8, users.stream().filter(user -> employeeCode(user).startsWith("FN")).count());
		assertEquals(3, users.stream().filter(user -> employeeCode(user).startsWith("FD")).count());

		assertRoleAndPermission(users, "WX01", "WAX_INJECTION_OPERATOR", "CART_OPERATE");
		assertRoleAndPermission(users, "WR01", "WAX_REPAIR_OPERATOR", "CART_OPERATE");
		assertRoleAndPermission(users, "TA01", "TREE_ASSEMBLY_OPERATOR", "CART_OPERATE");
		assertRoleAndPermission(users, "SH01", "SHELL_BUILDING_OPERATOR", "CART_OPERATE");
		assertRoleAndPermission(users, "DW01", "DEWAX_OPERATOR", "CART_OPERATE");
		assertRoleAndPermission(users, "PO01", "POURING_OPERATOR", "CART_OPERATE");
		assertRoleAndPermission(users, "KO01", "KNOCKOUT_OPERATOR", "CART_OPERATE");
		assertRoleAndPermission(users, "FN01", "FINISHING_OPERATOR", "CART_OPERATE");

		assertRoleAndPermission(users, "S001", "MID_WAX_SUPERVISOR", "PRINT_WORKSHOP_DOCUMENT");
		assertRoleAndPermission(users, "S002", "MID_SHELL_SUPERVISOR", "PRINT_WORKSHOP_DOCUMENT");
		assertRoleAndPermission(users, "S003", "POST_PROCESS_SUPERVISOR", "PRINT_WORKSHOP_DOCUMENT");
		Map<String, Object> midWaxSupervisor = user(users, "S001");
		assertEquals("中温蜡间主管01", midWaxSupervisor.get("name"));
		assertTrue(((List<?>) midWaxSupervisor.get("roles")).contains("PROCESS_ENGINEER"));

		Map<String, Object> frontDesk = user(users, "FD01");
		assertEquals("FRONT_DESK_CLERK", frontDesk.get("primaryRole"));
		assertTrue(permissions(frontDesk).contains("TRACE_VIEW"));
		assertFalse(permissions(frontDesk).contains("MANUAL_REPORT_REVIEW"));
		assertFalse(permissions(frontDesk).contains("TASK_EXECUTE"));
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> users() throws Exception {
		MvcResult result = mvc.perform(get("/api/access/users"))
			.andExpect(status().isOk())
			.andReturn();
		return JsonPath.parse(result.getResponse().getContentAsString()).read("$");
	}

	private void assertRoleAndPermission(List<Map<String, Object>> users, String employeeCode,
			String role, String permission) {
		Map<String, Object> user = user(users, employeeCode);
		assertTrue(((List<?>) user.get("roles")).contains(role), employeeCode + " should have " + role);
		assertTrue(permissions(user).contains(permission), employeeCode + " should have " + permission);
	}

	private Map<String, Object> user(List<Map<String, Object>> users, String employeeCode) {
		return users.stream()
			.filter(user -> employeeCode.equals(employeeCode(user)))
			.findFirst()
			.orElseThrow();
	}

	private String employeeCode(Map<String, Object> user) {
		return (String) user.get("employeeCode");
	}

	@SuppressWarnings("unchecked")
	private List<String> permissions(Map<String, Object> user) {
		return (List<String>) user.get("permissions");
	}
}
