package com.renyi.mes;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:production-security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "mes.web.allowed-origins=http://localhost:5173",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.invalid"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProductionSecurityApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder decoder;
    @TempDir static java.nio.file.Path uploads;

    @DynamicPropertySource
    static void files(DynamicPropertyRegistry registry) {
        registry.add("mes.storage.upload-dir", () -> uploads.toString());
    }

    @BeforeEach
    void signedInWorker() {
        jdbc.update("delete from trusted_identity_binding");
        for (String actor : java.util.List.of("K001", "M001", "W001", "FD01")) {
            jdbc.update("insert into trusted_identity_binding(issuer, subject, employee_code) values ('https://issuer.invalid', ?, ?)", actor, actor);
        }
        when(decoder.decode(anyString())).thenAnswer(invocation -> Jwt.withTokenValue(invocation.getArgument(0))
            .header("alg", "RS256").issuer("https://issuer.invalid").subject(invocation.getArgument(0))
            .claim("scope", "mes.worker").build());
    }

    @Test
    void anonymousRequestsCannotReadBusinessData() throws Exception {
        mvc.perform(get("/api/access/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void signedInUsersCannotRunSimulationMaintenance() throws Exception {
        // Invalid confirmation ensures this regression test never deletes data, even before the fix.
        mvc.perform(post("/api/simulations/data/cleanup").param("confirmation", "INVALID")
            .header("Authorization", "Bearer test-token")).andExpect(status().isForbidden());
    }

    @Test
    void workersCannotChangeRolePermissions() throws Exception {
        mvc.perform(post("/api/access/roles/ADMIN/permissions")
            .header("Authorization", "Bearer test-token")
            .contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void healthProbesWorkWithoutBusinessCredentials() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void administrativeEndpointsRequireBothBoundAdminAndAdminScope() throws Exception {
        when(decoder.decode("scope-only")).thenReturn(Jwt.withTokenValue("scope-only")
            .header("alg", "RS256").issuer("https://issuer.invalid").subject("K001").claim("scope", "mes.admin").build());
        mvc.perform(get("/api/access/roles").header("Authorization", "Bearer scope-only"))
            .andExpect(status().isForbidden());
        jdbc.update("insert into trusted_identity_binding(issuer, subject, employee_code) values ('https://issuer.invalid', 'admin', 'A001')");
        when(decoder.decode("bound-admin")).thenReturn(Jwt.withTokenValue("bound-admin")
            .header("alg", "RS256").issuer("https://issuer.invalid").subject("admin").claim("scope", "mes.admin").build());
        mvc.perform(get("/api/access/roles").header("Authorization", "Bearer bound-admin"))
            .andExpect(status().isOk());
        when(decoder.decode("admin-without-scope")).thenReturn(Jwt.withTokenValue("admin-without-scope")
            .header("alg", "RS256").issuer("https://issuer.invalid").subject("admin").claim("scope", "mes.worker").build());
        mvc.perform(get("/api/access/roles").header("Authorization", "Bearer admin-without-scope"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory/balances").param("viewerCode", "A001").header("Authorization", "Bearer admin-without-scope"))
            .andExpect(status().isForbidden());
    }

    @Test
    void onlyBoundActiveIdentityMayUseItsOwnWarehouseScope() throws Exception {
        mvc.perform(get("/api/inventory/balances").param("viewerCode", "K001")
            .header("Authorization", "Bearer K001")).andExpect(status().isOk());
        mvc.perform(get("/api/inventory/balances").param("viewerCode", "A001")
            .header("Authorization", "Bearer K001")).andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory/balances").param("viewerCode", "K001")
            .header("Authorization", "Bearer unmapped")).andExpect(status().isForbidden());
        jdbc.update("update organization_member set active = false where employee_code = 'K001'");
        try {
            mvc.perform(get("/api/inventory/balances").param("viewerCode", "K001")
                .header("Authorization", "Bearer K001")).andExpect(status().isForbidden());
        } finally { jdbc.update("update organization_member set active = true where employee_code = 'K001'"); }
    }

    @Test
    void bodyAndHeaderIdentityCannotBeSpoofedAndSearchFiltersStayUsable() throws Exception {
        mvc.perform(post("/api/inventory/exports").header("Authorization", "Bearer K001")
            .contentType("application/json").content("""
                {"operationId":"%s","viewerCode":"A001"}
                """.formatted(java.util.UUID.randomUUID())))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory/balances").param("viewerCode", "K001")
            .header("Authorization", "Bearer K001").header("X-Operator-Code", "A001"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/exports").header("Authorization", "Bearer K001")
            .contentType("application/json").content("""
                {"operationId":"%s","viewerCode":"K001","operatorCode":"OTHER-WORKER"}
                """.formatted(java.util.UUID.randomUUID())))
            .andExpect(status().isAccepted());
    }

    @Test
    void attachmentOwnerAndExplicitReaderOnlyAndLegacyPathsAreClosed() throws Exception {
        var file = new MockMultipartFile("file", "contract.pdf", "application/pdf", "%PDF-1.7\ntest".getBytes());
        mvc.perform(multipart("/api/files/upload").file(file).param("category", "ORDER_CONTRACT")
            .header("Authorization", "Bearer K001")).andExpect(status().isForbidden());
        String body = mvc.perform(multipart("/api/files/upload").file(file).param("category", "ORDER_CONTRACT")
            .header("Authorization", "Bearer FD01")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String url = json.readTree(body).get("url").asText();
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header("Authorization", "Bearer FD01")).andExpect(status().isOk());
        mvc.perform(get(url).header("Authorization", "Bearer M001")).andExpect(status().isNotFound());
        jdbc.update("insert into attachment_reader(file_path, employee_code) values (?, 'M001')", url);
        mvc.perform(get(url).header("Authorization", "Bearer M001")).andExpect(status().isOk());
        jdbc.update("delete from attachment_reader where file_path = ?", url);
        mvc.perform(get(url).header("Authorization", "Bearer M001")).andExpect(status().isNotFound());
        mvc.perform(get("/uploads/order_contract/legacy.pdf").header("Authorization", "Bearer K001"))
            .andExpect(status().isNotFound());
    }
}
