package com.renyi.mes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.List;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class InventoryExportReliabilityApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void retriesReuseJobAndConflictingReuseIsRejected() throws Exception {
        UUID operation = UUID.randomUUID();
        String body = "{\"operationId\":\"" + operation + "\",\"viewerCode\":\"K001\",\"keyword\":\"RETRY-NO-MATCH\"}";
        var result = mvc.perform(post("/api/inventory/exports").contentType("application/json").content(body))
            .andExpect(status().isAccepted()).andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/inventory/exports").contentType("application/json").content(body))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/inventory/exports").contentType("application/json").content(body.replace("RETRY-NO-MATCH", "CHANGED")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EXPORT_OPERATION_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from inventory_export_job where operation_id = ?", Integer.class, operation)).isEqualTo(1);
    }

    @Test
    void concurrentRetriesCreateOnlyOneJob() throws Exception {
        UUID operation = UUID.randomUUID();
        String body = "{\"operationId\":\"" + operation + "\",\"viewerCode\":\"K001\",\"keyword\":\"CONCURRENT-NO-MATCH\"}";
        Callable<String> create = () -> {
            var result = mvc.perform(post("/api/inventory/exports").contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andReturn();
            return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(create, create));
            assertThat(results.getFirst().get()).isEqualTo(results.get(1).get());
        }
        assertThat(jdbc.queryForObject("select count(*) from inventory_export_job where operation_id = ?", Integer.class, operation)).isEqualTo(1);
    }

    @Test
    void validatesRequiredParametersAndDateRange() throws Exception {
        mvc.perform(post("/api/inventory/exports").contentType("application/json").content("{\"viewerCode\":\"K001\"}"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/inventory/exports").contentType("application/json").content("""
            {"operationId":"%s","viewerCode":"K001","fromDate":"2026-09-30","toDate":"2026-09-01"}
            """.formatted(UUID.randomUUID())))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_MOVEMENT_DATE_RANGE"));
    }

    @Test
    void unknownJobsReturnNotFoundAndUnauthorizedRolesAreRejected() throws Exception {
        String path = "/api/inventory/exports/" + UUID.randomUUID();
        mvc.perform(get(path).param("viewerCode", "K001")).andExpect(status().isNotFound());
        mvc.perform(get(path + "/download").param("viewerCode", "K001")).andExpect(status().isNotFound());
        mvc.perform(get(path).param("viewerCode", "W001")).andExpect(status().isForbidden());
    }
}
