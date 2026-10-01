package com.renyi.mes.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import com.renyi.mes.common.WarehouseAccessApplication;
import org.springframework.core.task.TaskRejectedException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class InventoryExportWorkerTests {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private UUID job;

    @BeforeEach
    void setup() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
            .addScript("db/migration/V87__inventory_export_jobs.sql")
            .addScript("db/migration/V94__inventory_export_recovery.sql").build();
        jdbc = new JdbcTemplate(database);
        job = UUID.randomUUID();
        jdbc.update("insert into inventory_export_job(id, operation_id, job_no, requested_by, status, created_at) values (?, ?, ?, 'K001', 'PENDING', current_timestamp)",
            job, UUID.randomUUID(), "TEST-EXPORT");
    }

    @AfterEach
    void shutdown() { database.shutdown(); }

    @Test
    void queryFailureIsRecordedWithoutExposingSql() {
        new InventoryExportWorker(jdbc, 2).generate(job);
        assertThat(jdbc.queryForObject("select status from inventory_export_job where id = ?", String.class, job)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select error_message from inventory_export_job where id = ?", String.class, job))
            .contains(job.toString()).doesNotContain("select", "inventory_balance", "SQL");
    }

    @Test
    void rejectedQueueJobIsMarkedFailed() {
        var access = mock(WarehouseAccessApplication.class);
        when(access.canViewRawMaterials("K001")).thenReturn(true);
        var worker = mock(InventoryExportWorker.class);
        doThrow(new TaskRejectedException("Executor busy")).when(worker).generate(any(UUID.class));
        var app = new InventoryExportApplication(jdbc, access, worker);
        var result = app.create(new InventoryExportApplication.ExportCommand(UUID.randomUUID(), "K001", null, null, null, null, null, null));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).doesNotContain("Executor").contains("繁忙");
    }

    @Test
    void exceedingLimitFailsInsteadOfSilentlyTruncatingCsv() {
        jdbc.execute("create table inventory_balance(id uuid, warehouse_code varchar, item_code varchar, item_name varchar, unit varchar)");
        jdbc.execute("create table inventory_movement(balance_id uuid, movement_no varchar, movement_type varchar, quantity numeric, balance_after numeric, reference_type varchar, reference_no varchar, operator_code varchar, remark varchar, occurred_at timestamp)");
        UUID balance = UUID.randomUUID();
        jdbc.update("insert into inventory_balance values (?, 'RM-01', 'TEST', 'Test material', 'KG')", balance);
        for (int i = 0; i < 3; i++) {
            jdbc.update("insert into inventory_movement values (?, ?, 'RECEIPT', 1, 1, '', '', 'K001', '', current_timestamp)", balance, "MOV-" + i);
        }
        new InventoryExportWorker(jdbc, 2).generate(job);
        assertThat(jdbc.queryForObject("select status from inventory_export_job where id = ?", String.class, job)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select error_message from inventory_export_job where id = ?", String.class, job)).contains("2");
        assertThat(jdbc.queryForObject("select file_content from inventory_export_job where id = ?", String.class, job)).isNull();
        jdbc.update("update inventory_export_job set status = 'PENDING' where id = ?", job);
        new InventoryExportWorker(jdbc, 3).generate(job);
        assertThat(jdbc.queryForObject("select status from inventory_export_job where id = ?", String.class, job)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select error_message from inventory_export_job where id = ?", String.class, job)).isNull();
        assertThat(jdbc.queryForObject("select file_content from inventory_export_job where id = ?", String.class, job)).contains("MOV-0", "MOV-1", "MOV-2");
    }

    @Test
    void liveLeaseAndCompletedJobsCannotBeClaimedAgain() {
        jdbc.update("update inventory_export_job set status = 'RUNNING', lease_until = ?, attempts = 1 where id = ?",
            java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(600)), job);
        new InventoryExportWorker(jdbc, 2).generate(job);
        assertThat(jdbc.queryForObject("select attempts from inventory_export_job where id = ?", Integer.class, job)).isEqualTo(1);
        jdbc.update("update inventory_export_job set status = 'COMPLETED' where id = ?", job);
        new InventoryExportWorker(jdbc, 2).generate(job);
        assertThat(jdbc.queryForObject("select status from inventory_export_job where id = ?", String.class, job)).isEqualTo("COMPLETED");
    }

    @Test
    void expiredJobIsRetriedButRecoveryStopsAfterThreeInterruptions() {
        jdbc.update("update inventory_export_job set status = 'RUNNING', attempts = 1 where id = ?", job);
        new InventoryExportRecovery(jdbc, new InventoryExportWorker(jdbc, 2)).recover();
        assertThat(jdbc.queryForObject("select attempts from inventory_export_job where id = ?", Integer.class, job)).isEqualTo(2);
        jdbc.update("update inventory_export_job set status = 'RUNNING', attempts = 3, lease_until = null where id = ?", job);
        new InventoryExportRecovery(jdbc, new InventoryExportWorker(jdbc, 2)).recover();
        assertThat(jdbc.queryForObject("select status from inventory_export_job where id = ?", String.class, job)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select error_message from inventory_export_job where id = ?", String.class, job)).contains("多次中断");
    }

    @Test
    void competingWorkersClaimTheSameJobOnlyOnce() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    new InventoryExportWorker(jdbc, 2).generate(job);
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select attempts from inventory_export_job where id = ?", Integer.class, job)).isEqualTo(1);
    }
}
