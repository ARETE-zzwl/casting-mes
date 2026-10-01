package com.renyi.mes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationUpgradeTests {
    @Test
    void upgradesExistingV92WithoutLosingLongProcessParametersOrExportJobs() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:upgrade-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).target("92").load().migrate();
        var jdbc = new JdbcTemplate(source);
        UUID customer = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID work = UUID.randomUUID();
        String parameters = "{\"notes\":\"" + "temperature and material; ".repeat(6000) + "\"}";
        jdbc.update("insert into customer_order_customer(id, code, name, active, created_at) values (?, 'UPGRADE', 'Fixture', true, current_timestamp)", customer);
        jdbc.update("""
            insert into customer_order_header(id, order_no, customer_id, customer_code, customer_name, status,
                priority, version, created_at, engineering_operation_parameters)
            values (?, 'UPGRADE', ?, 'UPGRADE', 'Fixture', 'DRAFT', 'NORMAL', 0, current_timestamp, ?)
            """, order, customer, parameters);
        jdbc.update("""
            insert into customer_order_line(id, order_id, line_no, product_id, product_code, product_name,
                route_type, route_version, ordered_quantity, unit, engineering_operation_parameters)
            values (?, ?, 1, ?, 'UPGRADE', 'Fixture', 'MID_TEMP_WAX', 'V1', 10, 'PCS', ?)
            """, line, order, product, parameters);
        jdbc.update("""
            insert into planning_work_order(id, work_order_no, order_id, order_line_id, product_id, product_code,
                product_name, route_type, route_version, planned_quantity, status, created_at, engineering_operation_parameters)
            values (?, 'UPGRADE', ?, ?, ?, 'UPGRADE', 'Fixture', 'MID_TEMP_WAX', 'V1', 10, 'RELEASED', current_timestamp, ?)
            """, work, order, line, product, parameters);
        UUID job = UUID.randomUUID();
        jdbc.update("insert into inventory_export_job(id, operation_id, job_no, requested_by, status, created_at) values (?, ?, 'UPGRADE', 'K001', 'RUNNING', current_timestamp)", job, UUID.randomUUID());
        var flyway = Flyway.configure().dataSource(source).load();
        flyway.migrate();
        flyway.validate();
        for (String table : java.util.List.of("customer_order_header", "customer_order_line", "planning_work_order")) {
            assertThat(jdbc.queryForObject("select engineering_operation_parameters from " + table, String.class)).isEqualTo(parameters);
        }
        assertThat(jdbc.queryForObject("select attempts from inventory_export_job where id = ?", Integer.class, job)).isZero();
        assertThat(jdbc.queryForObject("select status from inventory_export_job where id = ?", String.class, job)).isEqualTo("RUNNING");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        jdbc.execute("shutdown");
    }
}
