package com.renyi.mes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.renyi.mes.common.LocalSessionCredentials;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties="mes.auth.bootstrap-admin-password=")
@AutoConfigureMockMvc
@ActiveProfiles("secure")
class LocalSessionSecurityTests {
    @Autowired MockMvc mvc;
    @Autowired LocalSessionCredentials credentials;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private Cookie csrf;
    private String token;

    @BeforeEach void setup() throws Exception {
        credentials.reset("K001", "Testing-only-Password1", false);
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        csrf = response.getCookie("XSRF-TOKEN");
        token = json.readTree(response.getContentAsString()).get("token").asText();
    }
    private MockHttpSession login() throws Exception {
        var session = new MockHttpSession();
        String original = session.getId();
        mvc.perform(post("/api/auth/login").session(session).cookie(csrf).header("X-XSRF-TOKEN", token)
            .contentType("application/json").content("{\"employeeCode\":\"K001\",\"password\":\"Testing-only-Password1\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.employeeCode").value("K001"))
            .andExpect(jsonPath("$.user.loginInitialized").value(true)).andExpect(jsonPath("$.user.lastLoginAt").isNotEmpty());
        assertThat(session.getId()).isNotEqualTo(original);
        return session;
    }
    @Test void loginScopeLogoutAndResetInvalidateSessions() throws Exception {
        var session = login();
        mvc.perform(get("/api/inventory/balances").session(session).param("viewerCode", "K001")).andExpect(status().isOk());
        mvc.perform(get("/api/inventory/balances").session(session).param("viewerCode", "A001")).andExpect(status().isForbidden());
        credentials.reset("K001", "Replacement-Password1", false);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void failuresPersistAndCsrfIsMandatoryEvenForLogin() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        for (int i=0; i<5; i++) {
            mvc.perform(post("/api/auth/login").cookie(csrf).header("X-XSRF-TOKEN", token)
                .contentType("application/json").content("{\"employeeCode\":\"K001\",\"password\":\"incorrect-password\"}"))
                .andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForObject("select failed_attempts from identity_account where employee_code='K001'", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select locked_until from identity_account where employee_code='K001'", java.sql.Timestamp.class)).isNotNull();
    }
    @Test void logoutAndDisabledEmployeesCannotContinue() throws Exception {
        var session = login();
        mvc.perform(post("/api/auth/logout").session(session).cookie(csrf).header("X-XSRF-TOKEN", token)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        var another = login();
        jdbc.update("update organization_member set active=false where employee_code='K001'");
        try { mvc.perform(get("/api/auth/me").session(another)).andExpect(status().isUnauthorized()); }
        finally { jdbc.update("update organization_member set active=true where employee_code='K001'"); }
    }
}
