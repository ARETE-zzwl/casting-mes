package com.renyi.mes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:business-security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "mes.web.allowed-origins=http://localhost:5173",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.invalid"
})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
@Transactional
class BusinessAuthorizationApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired jakarta.persistence.EntityManager entities;
    @Autowired com.renyi.mes.common.BusinessAccess access;
    @MockitoBean JwtDecoder decoder;
    @TempDir static java.nio.file.Path uploads;
    @DynamicPropertySource static void storage(DynamicPropertyRegistry registry) { registry.add("mes.storage.upload-dir", uploads::toString); }
    @BeforeEach void identities() {
        // Demo data intentionally gives S001 a second engineer role; this fixture tests a single-role supervisor.
        jdbc.update("delete from organization_member_role where employee_code='S001' and role_code='PROCESS_ENGINEER'");
        for (String code : List.of("FD01", "CM001", "GM001", "E001", "S001", "LW01", "W001", "WR01", "K001", "M001", "G001", "PM01", "S003", "F001", "P001", "Q001", "C001", "DSP-WAX", "DSP-SHELL")) {
            jdbc.update("insert into trusted_identity_binding(issuer, subject, employee_code) values ('https://issuer.invalid', ?, ?)", code, code);
        }
        when(decoder.decode(anyString())).thenAnswer(call -> Jwt.withTokenValue(call.getArgument(0)).header("alg", "RS256")
            .issuer("https://issuer.invalid").subject(call.getArgument(0)).claim("scope", "mes.worker").build());
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String actor) { return request.header("Authorization", "Bearer " + actor); }

    @Test void readonlyRoleDependenciesWorkWithoutGrantingProductionWrites() throws Exception {
        var task = fixture("MID_TEMP_WAX", "W001");
        for (String actor : List.of("F001", "Q001", "P001")) {
            mvc.perform(as(get("/api/tasks"), actor)).andExpect(status().isOk());
            mvc.perform(as(get("/api/execution/reports").param("viewerCode", actor), actor)).andExpect(status().isOk());
            mvc.perform(as(get("/api/orders/"+task.order()), actor)).andExpect(status().isOk());
        }
        mvc.perform(as(get("/api/work-orders"), "P001")).andExpect(status().isOk());
        mvc.perform(as(get("/api/products"), "M001")).andExpect(status().isOk());
        for (String actor : List.of("F001", "Q001")) {
            mvc.perform(as(post("/api/tasks/"+task.task()+"/start").header("X-Operator-Code",actor), actor)).andExpect(status().isForbidden());
        }
        mvc.perform(as(get("/api/reporting/dashboard"), "Q001")).andExpect(status().isOk())
            .andExpect(jsonPath("$.overview.confirmedPieceworkAmount").isEmpty()).andExpect(jsonPath("$.workerAmounts.length()").value(0));
        mvc.perform(as(get("/api/reporting/dashboard"), "P001")).andExpect(status().isOk())
            .andExpect(jsonPath("$.overview.confirmedPieceworkAmount").isEmpty());
        mvc.perform(as(get("/api/reporting/dashboard"), "F001")).andExpect(status().isOk())
            .andExpect(jsonPath("$.overview.confirmedPieceworkAmount").isNumber());
    }

    @Test void engineerCustomerLookupDoesNotDiscloseContactDetails() throws Exception {
        var fixture = fixture("MID_TEMP_WAX", "W001");
        UUID customer = jdbc.queryForObject("select customer_id from customer_order_header where id=?", UUID.class, fixture.order());
        jdbc.update("update customer_order_customer set contact_name='Private contact',contact_phone='13800000000',sales_owner='SALE01' where id=?",customer);
        var engineer = mvc.perform(as(get("/api/customers"), "E001")).andExpect(status().isOk()).andReturn();
        assertThat(engineer.getResponse().getContentAsString()).contains("Security customer").doesNotContain("13800000000", "Private contact", "SALE01");
        var frontDesk = mvc.perform(as(get("/api/customers"), "FD01")).andExpect(status().isOk()).andReturn();
        assertThat(frontDesk.getResponse().getContentAsString()).contains("13800000000", "Private contact");
    }

    @Test void carrierBindingRoleCannotBindMoldLabelsEvenOnReplay() throws Exception {
        UUID mold = UUID.randomUUID(), cart = UUID.randomUUID(), label = UUID.randomUUID();
        for (UUID asset : List.of(mold,cart)) jdbc.update("insert into resource_asset(id,asset_code,asset_name,asset_type,status,life_used,version,created_at,updated_at) values (?,?,?,?,'AVAILABLE',0,0,current_timestamp,current_timestamp)", asset,asset.toString(),"Security asset",asset.equals(mold)?"MOLD":"CARRIER");
        String token = label.toString().replace("-","").toUpperCase();
        jdbc.update("insert into asset_qr_label(id,label_no,qr_token,intended_asset_type,status,created_by,created_at) values (?,?,?,'MOLD','UNBOUND','M001',current_timestamp)",label,label.toString(),token);
        String body = json.writeValueAsString(java.util.Map.of("scannedValue",token,"assetId",mold,"boundBy","C001"));
        mvc.perform(as(post("/api/asset-qr-codes/bind").contentType("application/json").content(body),"C001")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/asset-qr-codes/bind").contentType("application/json").content(body.replace("C001","M001")),"M001")).andExpect(status().isOk());
        mvc.perform(as(post("/api/asset-qr-codes/bind").contentType("application/json").content(body),"C001")).andExpect(status().isForbidden());
        jdbc.update("update asset_qr_label set asset_id=null,intended_asset_type='CARRIER',status='UNBOUND' where id=?",label);
        mvc.perform(as(post("/api/asset-qr-codes/bind").contentType("application/json").content(body.replace(mold.toString(),cart.toString())),"C001")).andExpect(status().isOk());
    }

    @Test void anAdministratorRoleWithoutJwtAdminScopeCannotElevateASecondRole() throws Exception {
        jdbc.update("insert into organization_member_role(employee_code,role_code) values ('S001','SYSTEM_ADMIN')");
        var own = fixture("MID_TEMP_WAX","W001");
        var foreign = fixture("LOW_TEMP_WAX","LWX01");
        UUID foreignEntry = null;
        for (var fixture : List.of(own,foreign)) {
            UUID entry = UUID.randomUUID();
            if (fixture.equals(foreign)) foreignEntry = entry;
            String worker = fixture.equals(own) ? "W001" : "LWX01";
            jdbc.update("insert into piecework_entry(id,operation_id,entry_no,task_id,task_no,operation_code,operation_name,worker_code,recorded_by,quantity,settlement_unit,rate_version,unit_rate,amount,status,settlement_date,occurred_at) values (?,?,?,?,?,'WAX_INJECTION','Wax',?,?,1,'PCS','SEC',1,1,'CONFIRMED',current_date,current_timestamp)",entry,UUID.randomUUID(),entry.toString(),fixture.task(),fixture.task().toString(),worker,worker);
            jdbc.update("insert into execution_report(id,operation_id,task_id,good_quantity,scrap_quantity,operator_code,task_good_total,task_scrap_total,task_status,occurred_at) values (?,?,?,1,0,?,1,0,'IN_PROGRESS',current_timestamp)",UUID.randomUUID(),UUID.randomUUID(),fixture.task(),worker);
        }
        var ledger = mvc.perform(as(get("/api/piecework/entries").param("viewerCode","S001"),"S001")).andExpect(status().isOk()).andReturn();
        assertThat(ledger.getResponse().getContentAsString()).contains(own.task().toString()).doesNotContain(foreign.task().toString());
        mvc.perform(as(post("/api/piecework/entries/"+foreignEntry+"/confirmation").contentType("application/json").content("{\"supervisorCode\":\"S001\"}"),"S001")).andExpect(status().isForbidden());
        var reports = mvc.perform(as(get("/api/execution/reports").param("viewerCode","S001"),"S001")).andExpect(status().isOk()).andReturn();
        assertThat(reports.getResponse().getContentAsString()).contains(own.task().toString()).doesNotContain(foreign.task().toString());
        mvc.perform(as(get("/api/documents/audits").param("actorCode","S001"),"S001")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/piecework/rates").contentType("application/json").content("""
            {"supervisorCode":"S001","operationCode":"OPTIONAL_FINISHING","operationName":"Finishing","routeType":"LOW_TEMP_WAX","version":"SEC","settlementUnit":"KG","unitRate":1,"effectiveFrom":"2026-10-01"}
            """),"S001")).andExpect(status().isForbidden());
    }

    @Test void warehouseRolesCannotBorrowAnotherWarehouseIdentity() throws Exception {
        mvc.perform(as(get("/api/resources/query"), "M001")).andExpect(status().isOk());
        mvc.perform(as(get("/api/factory/mold-requests/locations"), "M001")).andExpect(status().isOk());
        mvc.perform(as(get("/api/factory/mold-requests/locations"), "K001")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/fulfillment/lots/query").param("viewerCode", "G001"), "G001")).andExpect(status().isOk());
        mvc.perform(as(get("/api/fulfillment/lots/query").param("viewerCode", "G001"), "K001")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/fulfillment/lots").contentType("application/json").content("""
            {"operationId":"%s","taskId":"%s","quantity":1,"warehouseCode":"FG-01","registeredBy":"A001"}
            """.formatted(UUID.randomUUID(), UUID.randomUUID())), "G001")).andExpect(status().isForbidden());
    }

    @Test void taskListsAndIdsCannotCrossAssignmentsOrProductionLines() throws Exception {
        var mid = fixture("MID_TEMP_WAX", "W001");
        var low = fixture("LOW_TEMP_WAX", "LWX01");
        assertThat(jdbc.queryForList("select route_type from production_supervisor_scope where employee_code='S001'", String.class)).containsExactly("MID_TEMP_WAX");
        var result = mvc.perform(as(get("/api/tasks"), "W001")).andExpect(status().isOk()).andReturn();
        var tasks = json.readTree(result.getResponse().getContentAsString());
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).get("id").asText()).isEqualTo(mid.task().toString());
        mvc.perform(as(get("/api/tasks/" + low.task()), "W001")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/tasks/" + low.task()), "S001")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/tasks/" + low.task()), "LW01")).andExpect(status().isOk());
        mvc.perform(as(get("/api/tasks/claimable").param("workerCode", "W001"), "WR01")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/tasks/" + mid.task() + "/start").header("X-Operator-Code", "WR01"), "W001")).andExpect(status().isForbidden());
        var supervisor = mvc.perform(as(get("/api/tasks"), "S001")).andExpect(status().isOk()).andReturn();
        assertThat(supervisor.getResponse().getContentAsString()).doesNotContain(low.task().toString());
    }

    @Test void readPermissionDoesNotImplyReportPermission() throws Exception {
        var fixture = fixture("MID_TEMP_WAX", "W001");
        jdbc.update("delete from access_role_permission where permission_code='TASK_EXECUTE' and role_code in (select role_code from organization_member_role where employee_code='W001')");
        mvc.perform(as(get("/api/tasks/" + fixture.task()), "W001")).andExpect(status().isOk());
        mvc.perform(as(post("/api/tasks/" + fixture.task() + "/reports").header("X-Operator-Code", "W001")
            .contentType("application/json").content("{\"operationId\":\"" + UUID.randomUUID() + "\",\"goodQuantity\":1,\"scrapQuantity\":0}"), "W001"))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from execution_report where task_id=?", Integer.class, fixture.task())).isZero();
    }

    @Test void supervisorCanStartOwnAssignedInspectionButCannotImpersonateItsWorker() throws Exception {
        var own = fixture("SAND_OUTSOURCE", "PM01");
        jdbc.update("update planning_task set operation_code='INCOMING_INSPECTION' where id=?", own.task());
        mvc.perform(as(post("/api/tasks/"+own.task()+"/start").header("X-Operator-Code", "PM01"), "PM01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        var other = fixture("MID_TEMP_WAX", "W001");
        mvc.perform(as(post("/api/tasks/"+other.task()+"/start").header("X-Operator-Code", "W001"), "PM01"))
            .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/tasks/"+other.task()+"/start").header("X-Operator-Code", "FD01"), "FD01"))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select status from planning_task where id=?", String.class, other.task())).isEqualTo("ASSIGNED");
    }

    @Test void upstreamPhotoIsSharedWithNextAssignedOperationOnly() throws Exception {
        var fixture = fixture("MID_TEMP_WAX", "W001");
        String photo = upload("EXECUTION_PHOTO", "W001");
        UUID batch = jdbc.queryForObject("select batch_id from planning_task where id=?", UUID.class, fixture.task());
        UUID next = UUID.randomUUID();
        jdbc.update("insert into planning_task(id,task_no,batch_id,sequence_no,operation_code,operation_name,planned_quantity,good_quantity,scrap_quantity,status,assigned_to,version,created_at) values (?,?,?,2,'WAX_REPAIR','Wax repair',10,0,0,'ASSIGNED','WR01',0,current_timestamp)", next, next.toString(), batch);
        jdbc.update("insert into execution_report(id,operation_id,task_id,good_quantity,scrap_quantity,operator_code,task_good_total,task_scrap_total,task_status,occurred_at,photo_url) values (?,?,?,10,0,'W001',10,0,'COMPLETED',current_timestamp,?)", UUID.randomUUID(),UUID.randomUUID(),fixture.task(),photo);
        mvc.perform(as(get(photo), "WR01")).andExpect(status().isOk());
        mvc.perform(as(get(photo), "K001")).andExpect(status().isNotFound());
        jdbc.update("update planning_task set assigned_to=null where id=?", next);
        mvc.perform(as(get(photo), "WR01")).andExpect(status().isNotFound());
    }

    @Test void orderResponsesHideCommerceAndRejectImpersonationAndWrongRoles() throws Exception {
        var mid = fixture("MID_TEMP_WAX", "W001");
        var low = fixture("LOW_TEMP_WAX", "LWX01");
        mvc.perform(as(get("/api/orders/" + mid.order()), "E001")).andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].salesUnitPrice").isEmpty()).andExpect(jsonPath("$.contractAttachmentUrl").isEmpty());
        mvc.perform(as(get("/api/orders/" + mid.order()), "CM001")).andExpect(status().isOk())
            .andExpect(jsonPath("$.lines[0].salesUnitPrice").value(99));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(decoder.decode("S001")));
        try {
            assertThat(access.actor()).isEqualTo("S001");
            assertThat(access.commercial()).isFalse();
            assertThat(access.role("PROCESS_ENGINEER", "PRODUCTION_MANAGER")).as("roles: %s", jdbc.queryForList("select role_code from organization_member_role where employee_code='S001'")).isFalse();
            assertThat(access.canReadOrder(low.order())).isFalse();
        }
        finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        mvc.perform(as(get("/api/orders/" + low.order()), "S001")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/orders/" + mid.order() + "/submit").contentType("application/json")
            .content("{\"editorCode\":\"OTHER\"}"), "FD01")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/orders/" + mid.order() + "/general-manager-review").contentType("application/json")
            .content("{\"managerCode\":\"GM001\"}"), "W001")).andExpect(status().isForbidden());
    }

    @Test void attachmentsFollowBusinessRelationsAndRevokeWhenAssignmentChanges() throws Exception {
        var fixture = fixture("MID_TEMP_WAX", "W001");
        String drawing = upload("ORDER_DRAWING", "FD01");
        String contract = upload("ORDER_CONTRACT", "FD01");
        mvc.perform(as(get(drawing), "E001")).andExpect(status().isNotFound());
        jdbc.update("update customer_order_header set order_drawing_url=?, contract_attachment_url=? where id=?", drawing, contract, fixture.order());
        mvc.perform(as(get(drawing), "E001")).andExpect(status().isOk());
        mvc.perform(as(get(drawing), "W001")).andExpect(status().isOk());
        mvc.perform(as(get(drawing), "K001")).andExpect(status().isNotFound());
        mvc.perform(as(get(contract), "CM001")).andExpect(status().isOk());
        mvc.perform(as(get(contract), "S001")).andExpect(status().isNotFound());
        mvc.perform(as(get(contract), "E001")).andExpect(status().isNotFound());
        jdbc.update("update planning_task set assigned_to='WR01' where id=?", fixture.task());
        mvc.perform(as(get(drawing), "W001")).andExpect(status().isNotFound());
        mvc.perform(as(get(drawing), "WR01")).andExpect(status().isOk());
    }

    @Test void guessingPrivateImageCannotPublishItThroughAnotherProduct() throws Exception {
        String image = upload("PRODUCT_MODEL", "E001");
        var fixture = fixture("MID_TEMP_WAX", "W001");
        mvc.perform(as(post("/api/products/" + fixture.product() + "/model-image").contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of("modelImageUrl", image))), "FD01")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select model_image_url from engineering_product where id=?", String.class, fixture.product())).isNull();
        mvc.perform(as(post("/api/products/" + fixture.product() + "/model-image").contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of("modelImageUrl", image))), "E001")).andExpect(status().isOk());
        entities.flush();
        mvc.perform(as(get(image), "W001")).andExpect(status().isOk());
    }

    @Test void onlyTheAssignedOperationCanReadItsProcessImages() throws Exception {
        var fixture = fixture("MID_TEMP_WAX", "W001");
        String ownImage = upload("PROCESS_CARD_IMAGE", "E001");
        String otherImage = upload("PROCESS_CARD_IMAGE", "E001");
        String card = json.writeValueAsString(java.util.Map.of("_operationImages", java.util.Map.of("WAX_INJECTION", List.of(ownImage), "POURING", List.of(otherImage))));
        jdbc.update("update planning_work_order set engineering_operation_parameters=? where id=?", card, fixture.workOrder());
        mvc.perform(as(get(ownImage), "W001")).andExpect(status().isOk());
        mvc.perform(as(get(otherImage), "W001")).andExpect(status().isNotFound());
    }

    @Test void ordinaryRolePagesHaveTheirRequiredQueriesButNoAdministration() throws Exception {
        for (String path : List.of("/api/tasks", "/api/orders", "/api/factory/mold-requests/order-selections", "/api/factory/mold-requests", "/api/resources", "/api/outsourcing/suppliers", "/api/access/users")) {
            mvc.perform(as(get(path), "S001")).andExpect(status().isOk());
        }
        mvc.perform(as(get("/api/post-treatment/decisions").param("supervisorCode", "S001"), "S001")).andExpect(status().isOk());
        mvc.perform(as(get("/api/labor/paper-sheets").param("supervisorCode", "S001"), "S001")).andExpect(status().isOk());
        mvc.perform(as(get("/api/access/users"), "W001")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].employeeCode").value("W001"));
        mvc.perform(as(get("/api/access/users/GM001"), "W001")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/access/roles"), "S001")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/piecework/entries"), "W001")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/integration/jobs"), "PM01")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/tasks"), "DSP-WAX")).andExpect(status().isOk());
        mvc.perform(as(get("/api/asset-qr-codes"), "DSP-SHELL")).andExpect(status().isOk());
    }

    @Test void payrollCannotBeMadeGlobalByOmittingOrForgingViewer() throws Exception {
        var mid = fixture("MID_TEMP_WAX", "W001");
        var low = fixture("LOW_TEMP_WAX", "LWX01");
        for (var fixture : List.of(mid, low)) {
            jdbc.update("insert into piecework_entry(id,operation_id,entry_no,task_id,task_no,operation_code,operation_name,worker_code,recorded_by,quantity,settlement_unit,rate_version,unit_rate,amount,status,settlement_date,occurred_at) values (?,?,?,?,?,'WAX_INJECTION','Wax','W001','W001',1,'PCS','SEC',1,1,'CANDIDATE',current_date,current_timestamp)", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), fixture.task(), fixture.task().toString());
        }
        var response = mvc.perform(as(get("/api/piecework/entries"), "S001")).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString()).contains(mid.task().toString()).doesNotContain(low.task().toString());
        mvc.perform(as(get("/api/piecework/entries").param("viewerCode", "F001"), "S001")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/piecework/exports/payroll").param("viewerCode", "F001").param("month", "2026-10"), "W001")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/piecework/exports/payroll").param("viewerCode", "F001").param("month", "2026-10"), "F001")).andExpect(status().isOk());
    }

    @Test void confirmedPayrollReplayStillChecksProductionScope() throws Exception {
        var low = fixture("LOW_TEMP_WAX", "LWX01");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into piecework_entry(id,operation_id,entry_no,task_id,task_no,operation_code,operation_name,worker_code,recorded_by,quantity,settlement_unit,rate_version,unit_rate,amount,status,settlement_date,occurred_at) values (?,?,?,?,?,'WAX_INJECTION','Wax','LWX01','LW01',1,'PCS','SEC',1,1,'CONFIRMED',current_date,current_timestamp)", id, UUID.randomUUID(), id.toString(), low.task(), low.task().toString());
        mvc.perform(as(post("/api/piecework/entries/"+id+"/confirmation").contentType("application/json").content("{\"supervisorCode\":\"S001\"}"), "S001"))
            .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/piecework/entries/"+id+"/confirmation").contentType("application/json").content("{\"supervisorCode\":\"F001\"}"), "F001"))
            .andExpect(status().isOk());
    }

    @Test void cartRetriesCannotReturnAnotherTransferOrChangeRecordedContents() throws Exception {
        var first = fixture("MID_TEMP_WAX", "W001");
        var second = fixture("MID_TEMP_WAX", "W001");
        var low = fixture("LOW_TEMP_WAX", "LWX01");
        for (var fixture : List.of(first, second)) {
            jdbc.update("update planning_task set status='COMPLETED', good_quantity=10 where id=?", fixture.task());
            UUID batch = jdbc.queryForObject("select batch_id from planning_task where id=?", UUID.class, fixture.task());
            UUID next = UUID.randomUUID();
            jdbc.update("insert into planning_task(id,task_no,batch_id,sequence_no,operation_code,operation_name,planned_quantity,good_quantity,scrap_quantity,status,assigned_to,version,created_at) values (?,?,?,2,'WAX_REPAIR','Repair',10,0,0,'ASSIGNED','WR01',0,current_timestamp)", next, next.toString(), batch);
        }
        UUID asset = UUID.randomUUID(), operation = UUID.randomUUID();
        String cart = "CART-"+asset.toString().substring(0,8).toUpperCase();
        jdbc.update("insert into resource_asset(id,asset_code,asset_name,asset_type,status,life_used,version,created_at,updated_at) values (?,?,?,'CARRIER','AVAILABLE',0,0,current_timestamp,current_timestamp)", asset,cart,"Security cart");
        mvc.perform(as(get("/api/cart-transfers/ready-sources"), "W001")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(as(get("/api/cart-transfers/ready-sources"), "LW01")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        String body = json.writeValueAsString(java.util.Map.of("sourceTaskId",first.task(),"cartCode",cart,"quantity",5,"loadedBy","W001","operationId",operation));
        var result = mvc.perform(as(post("/api/cart-transfers/load").contentType("application/json").content(body), "W001")).andExpect(status().isOk()).andReturn();
        String transfer = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(as(post("/api/cart-transfers/load").contentType("application/json").content(body), "W001")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(transfer));
        mvc.perform(as(post("/api/cart-transfers/load").contentType("application/json").content(body.replace(first.task().toString(), second.task().toString())), "W001")).andExpect(status().isConflict());
        mvc.perform(as(post("/api/cart-transfers/load").contentType("application/json").content(body.replace(first.task().toString(), low.task().toString())), "W001")).andExpect(status().isForbidden());
        String receive = json.writeValueAsString(java.util.Map.of("receivedQuantity",5,"receivedBy","WR01","operationId",UUID.randomUUID()));
        mvc.perform(as(post("/api/cart-transfers/"+transfer+"/receive").contentType("application/json").content(receive), "WR01")).andExpect(status().isOk());
        mvc.perform(as(post("/api/cart-transfers/"+transfer+"/receive").contentType("application/json").content(receive), "WR01")).andExpect(status().isOk());
        mvc.perform(as(post("/api/cart-transfers/"+transfer+"/receive").contentType("application/json").content(receive.replace("\"receivedQuantity\":5", "\"receivedQuantity\":4")), "WR01")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from production_cart_transfer where cart_asset_id=?", Integer.class, asset)).isEqualTo(1);
        mvc.perform(as(get("/api/cart-transfers"), "LW01")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test void furnaceRejectsOneUnauthorizedTaskWithoutPartialWrites() throws Exception {
        var mid = fixture("MID_TEMP_WAX", "W001");
        var low = fixture("LOW_TEMP_WAX", "LWX01");
        jdbc.update("update planning_task set operation_code='DEWAX' where id in (?,?)", mid.task(), low.task());
        String body = json.writeValueAsString(java.util.Map.of("operationCode", "DEWAX", "chargeQuantity", 20, "taskIds", List.of(mid.task(), low.task()), "createdBy", "W001"));
        mvc.perform(as(post("/api/furnace-batches").contentType("application/json").content(body), "W001")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from furnace_batch_task where task_id in (?,?)", Integer.class, mid.task(), low.task())).isZero();
        body = json.writeValueAsString(java.util.Map.of("operationCode", "DEWAX", "chargeQuantity", 10, "taskIds", List.of(mid.task()), "createdBy", "W001"));
        var result = mvc.perform(as(post("/api/furnace-batches").contentType("application/json").content(body), "W001")).andExpect(status().isCreated()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(as(get("/api/furnace-batches"), "WR01")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(post("/api/furnace-batches/"+id+"/complete").contentType("application/json").content("{\"completedBy\":\"WR01\"}"), "WR01")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/furnace-batches/"+id+"/complete").contentType("application/json").content("{\"completedBy\":\"W001\"}"), "W001")).andExpect(status().isOk());
    }

    @Test void scansShowOnlyAuthorizedTasksAndCannotForgeAuditIdentity() throws Exception {
        var own = fixture("MID_TEMP_WAX", "W001");
        var other = fixture("LOW_TEMP_WAX", "LWX01");
        mvc.perform(as(get("/api/scans/resolve").param("value", "MES:TASK:"+own.task()), "W001")).andExpect(status().isOk());
        mvc.perform(as(get("/api/scans/resolve").param("value", "MES:TASK:"+other.task()), "W001")).andExpect(status().isNotFound());
        UUID batch = jdbc.queryForObject("select batch_id from planning_task where id=?", UUID.class, own.task());
        UUID extra = UUID.randomUUID();
        jdbc.update("insert into planning_task(id,task_no,batch_id,sequence_no,operation_code,operation_name,planned_quantity,good_quantity,scrap_quantity,status,assigned_to,version,created_at) values (?,?,?,2,'WAX_REPAIR','Repair',10,0,0,'ASSIGNED','WR01',0,current_timestamp)", extra, extra.toString(), batch);
        mvc.perform(as(get("/api/scans/resolve").param("value", "MES:BATCH:"+batch), "W001")).andExpect(status().isOk()).andExpect(jsonPath("$.tasks.length()").value(1));
        String event = json.writeValueAsString(java.util.Map.of("operationId",UUID.randomUUID(),"entityType","TASK","entityId",own.task(),"intent","VIEW","operatorCode","WR01","scannedValue","MES:TASK:"+own.task()));
        mvc.perform(as(post("/api/scan-events").contentType("application/json").content(event), "W001")).andExpect(status().isForbidden());
        event = json.writeValueAsString(java.util.Map.of("operationId",UUID.randomUUID(),"entityType","TASK","entityId",other.task(),"intent","VIEW","operatorCode","W001","scannedValue","MES:TASK:"+own.task()));
        mvc.perform(as(post("/api/scan-events").contentType("application/json").content(event), "W001")).andExpect(status().isBadRequest());
    }

    @Test void paperReportingBindsRecorderReviewerAndActualAssignedWorker() throws Exception {
        var own = fixture("MID_TEMP_WAX", "W001");
        String body = json.writeValueAsString(java.util.Map.of("taskId",own.task(),"workerCode","WR01","reportKind","QUANTITY","goodQuantity",10,"scrapQuantity",0,"enteredBy","S001"));
        mvc.perform(as(post("/api/labor/paper-sheets").contentType("application/json").content(body), "S001")).andExpect(status().isForbidden());
        body = json.writeValueAsString(java.util.Map.of("taskId",own.task(),"workerCode","W001","reportKind","QUANTITY","goodQuantity",10,"scrapQuantity",0,"enteredBy","S001"));
        var result = mvc.perform(as(post("/api/labor/paper-sheets").contentType("application/json").content(body), "S001")).andExpect(status().isCreated()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(as(post("/api/labor/paper-sheets/"+id+"/approval").contentType("application/json").content("{\"reviewerCode\":\"S001\"}"), "LW01")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/labor/paper-sheets/"+id+"/rejection").contentType("application/json").content("{\"reviewerCode\":\"LW01\",\"reason\":\"test\"}"), "LW01")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/labor/paper-sheets/"+id+"/approval").contentType("application/json").content("{\"reviewerCode\":\"S001\"}"), "S001")).andExpect(status().isOk());
        entities.flush();
        assertThat(jdbc.queryForObject("select operator_code from execution_report where task_id=?", String.class, own.task())).isEqualTo("W001");
        assertThat(jdbc.queryForObject("select recorded_by from execution_report where task_id=?", String.class, own.task())).isEqualTo("S001");
    }

    @Test void traceDoesNotReintroduceCommercialSecrets() throws Exception {
        var own = fixture("MID_TEMP_WAX", "W001");
        mvc.perform(as(get("/api/trace/orders/"+own.order()), "S001")).andExpect(status().isOk()).andExpect(jsonPath("$.order.lines[0].salesUnitPrice").isEmpty());
        mvc.perform(as(get("/api/trace/orders/"+own.order()), "CM001")).andExpect(status().isOk()).andExpect(jsonPath("$.order.lines[0].salesUnitPrice").value(99));
    }

    private String upload(String category, String actor) throws Exception {
        var file = new MockMultipartFile("file", "test.png", "image/png", new byte[]{(byte)137,80,78,71,13,10,26,10});
        var response = mvc.perform(multipart("/api/files/upload").file(file).param("category", category)
            .header("Authorization", "Bearer " + actor)).andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("url").asText();
    }
    private Fixture fixture(String route, String worker) {
        UUID customer=UUID.randomUUID(), product=UUID.randomUUID(), order=UUID.randomUUID(), line=UUID.randomUUID(), work=UUID.randomUUID(), batch=UUID.randomUUID(), task=UUID.randomUUID();
        jdbc.update("insert into customer_order_customer(id,code,name,active,created_at) values (?,?,?,true,current_timestamp)", customer, customer.toString(), "Security customer");
        jdbc.update("insert into engineering_product(id,code,name,route_type,route_version,active,created_at) values (?,?,?,?,'V1',true,current_timestamp)", product, product.toString(), "Security part", route);
        jdbc.update("insert into customer_order_header(id,order_no,customer_id,customer_code,customer_name,status,priority,version,created_at) values (?,?,?,?,?,'DRAFT','NORMAL',0,current_timestamp)", order, order.toString(), customer, customer.toString(), "Security customer");
        jdbc.update("insert into customer_order_line(id,order_id,line_no,product_id,product_code,product_name,route_type,route_version,ordered_quantity,unit,sales_unit_price,sales_price_unit) values (?,?,1,?,?,?,?,'V1',10,'PCS',99,'PCS')", line,order,product,product.toString(),"Security part",route);
        jdbc.update("insert into planning_work_order(id,work_order_no,order_id,order_line_id,product_id,product_code,product_name,route_type,route_version,planned_quantity,status,created_at) values (?,?,?,?,?,?,?,?,'V1',10,'RELEASED',current_timestamp)", work,work.toString(),order,line,product,product.toString(),"Security part",route);
        jdbc.update("insert into planning_batch(id,batch_no,work_order_id,planned_quantity,status,created_at) values (?,?,?,10,'READY',current_timestamp)", batch,batch.toString(),work);
        jdbc.update("insert into planning_task(id,task_no,batch_id,sequence_no,operation_code,operation_name,planned_quantity,good_quantity,scrap_quantity,status,assigned_to,version,created_at) values (?,?,?,1,'WAX_INJECTION','Wax injection',10,0,0,'ASSIGNED',?,0,current_timestamp)", task,task.toString(),batch,worker);
        return new Fixture(order,product,work,task);
    }
    record Fixture(UUID order, UUID product, UUID workOrder, UUID task) { }

    @Test void schedulingQueueCannotCrossProductionScope() throws Exception {
        var own = fixture("MID_TEMP_WAX", "W001");
        var other = fixture("LOW_TEMP_WAX", "LWX01");
        var response = mvc.perform(as(get("/api/scheduling/queue"), "S001")).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString()).contains(own.task().toString()).doesNotContain(other.task().toString());
        mvc.perform(as(post("/api/scheduling/queue/"+other.task()+"/rank").contentType("application/json").content("{\"manualRank\":1}"), "S001"))
            .andExpect(status().isForbidden());
    }

    @Test void printingCannotBypassTaskScopeThroughGenericEntityId() throws Exception {
        var other = fixture("LOW_TEMP_WAX", "LWX01");
        mvc.perform(as(post("/api/documents/previews").contentType("application/json").content(json.writeValueAsString(java.util.Map.of(
            "documentType", "WORKSHOP_JOB_SHEET", "entityId", other.task(), "actorCode", "S001", "selectedFields", List.of("TASK_NO"), "outputType", "PRINT"))), "S001"))
            .andExpect(status().isForbidden());
    }

    @Test void outsourcedOrderAndMilestonesRemainInsideLinkedTaskScope() throws Exception {
        var sand = fixture("SAND_OUTSOURCE", "PM01");
        jdbc.update("update planning_task set operation_code='OUTSOURCE_DISPATCH', status='READY', assigned_to=null where id=?", sand.task());
        jdbc.update("update planning_work_order set product_code='SEC-SAND' where id=?", sand.workOrder());
        UUID supplier = UUID.randomUUID();
        jdbc.update("insert into outsourcing_supplier(id,code,name,active,created_at) values (?,?,?,true,current_timestamp)", supplier,supplier.toString(),"Security supplier");
        String body = json.writeValueAsString(java.util.Map.of("orderNo",UUID.randomUUID().toString(),"supplierId",supplier,"planningTaskId",sand.task(),"itemCode","SEC-SAND","itemName","Security part","quantity",10,"unit","PCS"));
        mvc.perform(as(post("/api/outsourcing/orders").contentType("application/json").content(body), "S001")).andExpect(status().isForbidden());
        var result = mvc.perform(as(post("/api/outsourcing/orders").contentType("application/json").content(body), "PM01")).andExpect(status().isCreated()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(as(get("/api/outsourcing/orders/"+id+"/milestones"), "PM01")).andExpect(status().isOk());
        mvc.perform(as(get("/api/outsourcing/orders/"+id+"/milestones"), "S001")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/outsourcing/orders/"+id+"/milestones").contentType("application/json").content("{\"nextStatus\":\"SENT\",\"operatorCode\":\"S001\"}"), "S001"))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select status from outsourcing_order where id=?", String.class, UUID.fromString(id))).isEqualTo("DRAFT");
    }

    @Test void finishedGoodsKeeperCanReceiveCompletedSandInspectionWithoutTakingOverProduction() throws Exception {
        var sand = fixture("SAND_OUTSOURCE", "PM01");
        jdbc.update("update planning_task set operation_code='INCOMING_INSPECTION', status='COMPLETED', good_quantity=10 where id=?", sand.task());
        var pending = mvc.perform(as(get("/api/fulfillment/receipts").param("viewerCode", "G001"), "G001"))
            .andExpect(status().isOk()).andReturn();
        assertThat(pending.getResponse().getContentAsString()).contains(sand.task().toString());
        UUID operation = UUID.randomUUID();
        String body = json.writeValueAsString(java.util.Map.of("operationId",operation,"taskId",sand.task(),"quantity",10,"warehouseCode","FG-01","registeredBy","G001"));
        mvc.perform(as(post("/api/fulfillment/lots").contentType("application/json").content(body), "G001"))
            .andExpect(status().isCreated());
        mvc.perform(as(post("/api/fulfillment/lots").contentType("application/json").content(body), "G001"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        mvc.perform(as(post("/api/fulfillment/lots").contentType("application/json").content(body.replace(operation.toString(), UUID.randomUUID().toString())), "G001"))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select assigned_to from planning_task where id=?", String.class, sand.task())).isEqualTo("PM01");
        var incomplete = fixture("SAND_OUTSOURCE", "PM01");
        mvc.perform(as(post("/api/fulfillment/lots").contentType("application/json").content(body.replace(sand.task().toString(), incomplete.task().toString())), "G001"))
            .andExpect(status().isForbidden());
    }

    @Test void paperQuantitiesCannotStoreNegativeFractionalOrEmptyProduction() throws Exception {
        var own = fixture("MID_TEMP_WAX", "W001");
        for (double quantity : new double[]{-1,0,1.5}) {
            String body = json.writeValueAsString(java.util.Map.of("taskId",own.task(),"workerCode","W001","reportKind","QUANTITY","goodQuantity",quantity,"scrapQuantity",0,"enteredBy","S001"));
            mvc.perform(as(post("/api/labor/paper-sheets").contentType("application/json").content(body), "S001")).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("select count(*) from manual_report_sheet where task_id=?", Integer.class, own.task())).isZero();
    }
}
