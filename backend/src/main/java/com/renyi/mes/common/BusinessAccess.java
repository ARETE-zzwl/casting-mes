package com.renyi.mes.common;

import java.util.Arrays;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Database-backed authorization shared without introducing domain module dependencies. */
@Service
public class BusinessAccess {
    private final JdbcTemplate jdbc;
    private final TrustedIdentity identity;
    private final boolean secured;

    public BusinessAccess(JdbcTemplate jdbc, TrustedIdentity identity, Environment environment) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.secured = environment.acceptsProfiles(Profiles.of("prod | secure"));
    }

    public boolean secured() { return secured; }
    public String actor() { return identity.employeeCode(); }
    public String viewer(String supplied) {
        if (!secured) return supplied;
        if (supplied != null) identity.requireActor(supplied);
        return actor();
    }
    public boolean globalProduction() { return role("SYSTEM_ADMIN", "GENERAL_MANAGER", "PRODUCTION_MANAGER", "GLOBAL_SCHEDULER"); }
    public boolean canManageOperation(String route, String operation) {
        if (!secured || globalProduction()) return true;
        return permission("TASK_DISPATCH") && exists("""
            select count(*) from production_supervisor_scope s where s.employee_code = ? and s.route_type = ?
            and (not exists (select 1 from production_supervisor_operation_scope o where o.employee_code = s.employee_code and o.route_type = s.route_type)
              or exists (select 1 from production_supervisor_operation_scope o where o.employee_code = s.employee_code and o.route_type = s.route_type and o.operation_code = ?))
            """, actor(), route, operation);
    }
    public boolean permission(String... permissions) {
        if (!secured) return true;
        return Arrays.stream(permissions).anyMatch(permission -> exists("""
            select count(*) from organization_member_role mr join access_role_permission rp on rp.role_code = mr.role_code
            where mr.employee_code = ? and rp.permission_code = ? and (mr.role_code <> 'SYSTEM_ADMIN' or ?)
            """, actor(), permission, identity.isAdministrator()));
    }
    public boolean role(String... roles) {
        if (!secured) return true;
        return Arrays.stream(roles).anyMatch(role -> role.equals("SYSTEM_ADMIN") ? identity.isAdministrator() : exists(
            "select count(*) from organization_member_role where employee_code = ? and role_code = ?", actor(), role));
    }
    public void requirePermission(String... permissions) {
        if (!permission(permissions)) throw forbidden();
    }
    public void requireRole(String... roles) {
        if (!role(roles)) throw forbidden();
    }
    public boolean commercial() { return role("SYSTEM_ADMIN", "GENERAL_MANAGER", "CUSTOMER_MANAGER", "FRONT_DESK_CLERK"); }

    public boolean canReadOrder(UUID id) {
        if (!secured) return true;
        if (commercial() || role("PROCESS_ENGINEER", "PRODUCTION_MANAGER", "GLOBAL_SCHEDULER", "FINANCE_REVIEWER", "QUALITY_INSPECTOR", "QUALITY_ENGINEER", "FINISHED_GOODS_KEEPER", "DELIVERY_COORDINATOR")) return exists("select count(*) from customer_order_header where id = ?", id);
        if (role("SALES_REP")) return exists("select count(*) from customer_order_header h join organization_member m on m.employee_code = ? where h.id = ? and h.sales_owner in (m.employee_code, m.name)", actor(), id);
        return permission("ORDER_MANAGE", "PLANNING_VIEW", "TRACE_VIEW") && exists("""
            select count(*) from customer_order_line l join production_supervisor_scope s on s.route_type = l.route_type
            where l.order_id = ? and s.employee_code = ?
            """, id, actor());
    }
    public void requireOrder(UUID id) { if (!canReadOrder(id)) throw forbidden(); }

    public boolean canReadTask(UUID id) {
        if (!secured) return true;
        if (role("SYSTEM_ADMIN", "GENERAL_MANAGER", "PROCESS_ENGINEER", "FINANCE_REVIEWER", "GLOBAL_SCHEDULER", "QUALITY_INSPECTOR", "QUALITY_ENGINEER")) return true;
        if (commercial() || role("SALES_REP")) return taskOrder(id).stream().anyMatch(this::canReadOrder);
        if (permission("FINISHED_GOODS_WAREHOUSE_MANAGE", "FULFILLMENT_MANAGE")) return exists("""
            select count(*) from planning_task t where t.id = ? and t.status = 'COMPLETED'
            and t.sequence_no = (select max(last_task.sequence_no) from planning_task last_task where last_task.batch_id = t.batch_id)
            """, id) || canManageTask(id);
        if (role("WORKSHOP_DISPLAY")) return exists("""
            select count(*) from planning_task t join organization_member m on m.employee_code = ? where t.id = ?
            and ((m.unit_code = 'WAX_WORKSHOP' and t.operation_code in ('WAX_INJECTION','WAX_REPAIR','TREE_ASSEMBLY'))
             or (m.unit_code = 'SHELL_WORKSHOP' and t.operation_code in ('SHELL_BUILDING','MANUAL_SHELL_BUILDING')))
            """, actor(), id);
        return canManageTask(id);
    }
    public boolean canManageTask(UUID id) {
        if (!secured || globalProduction()) return true;
        return exists("""
            select count(*) from planning_task t join planning_batch b on b.id = t.batch_id
            join planning_work_order w on w.id = b.work_order_id where t.id = ? and
            (t.assigned_to = ? or exists (select 1 from organization_member_role where employee_code = ? and role_code = 'PRODUCTION_MANAGER')
             or exists (select 1 from production_supervisor_scope s where s.employee_code = ? and s.route_type = w.route_type
                and (not exists (select 1 from production_supervisor_operation_scope o where o.employee_code = s.employee_code and o.route_type = s.route_type)
                     or exists (select 1 from production_supervisor_operation_scope o where o.employee_code = s.employee_code and o.route_type = s.route_type and o.operation_code = t.operation_code))))
            """, id, actor(), actor(), actor());
    }
    public void requireTask(UUID id) { if (!canReadTask(id)) throw forbidden(); }
    public void requireManageTask(UUID id) { if (!canManageTask(id)) throw forbidden(); }
    public java.util.List<UUID> taskOrder(UUID taskId) {
        return jdbc.query("select w.order_id from planning_task t join planning_batch b on b.id = t.batch_id join planning_work_order w on w.id = b.work_order_id where t.id = ?", (rs, n) -> rs.getObject(1, UUID.class), taskId);
    }
    public void requireWorkOrder(UUID id) {
        if (secured && !jdbc.query("select order_id from planning_work_order where id = ?", (rs, n) -> rs.getObject(1, UUID.class), id).stream().anyMatch(this::canReadOrder)) throw forbidden();
    }
    public void requireOrderLine(UUID id) {
        if (secured && !jdbc.query("select order_id from customer_order_line where id = ?", (rs, n) -> rs.getObject(1, UUID.class), id).stream().anyMatch(this::canReadOrder)
                && !permission("MOLD_RECEIVE", "MOLD_WAREHOUSE_MANAGE")) throw forbidden();
    }
    public void requireBatch(UUID id) {
        if (secured && !jdbc.query("select id from planning_task where batch_id = ? order by sequence_no limit 1", (rs, n) -> rs.getObject(1, UUID.class), id).stream().anyMatch(this::canManageTask)) throw forbidden();
    }
    public boolean canReadFurnace(UUID id) {
        if (!secured) return true;
        var tasks = jdbc.query("select task_id from furnace_batch_task where furnace_batch_id = ?", (rs, n) -> rs.getObject(1, UUID.class), id);
        return !tasks.isEmpty() && tasks.stream().allMatch(this::canReadTask);
    }
    public void requireFurnace(UUID id) {
        if (secured) {
            var tasks = jdbc.query("select task_id from furnace_batch_task where furnace_batch_id = ?", (rs, n) -> rs.getObject(1, UUID.class), id);
            if (tasks.isEmpty() || !tasks.stream().allMatch(this::canManageTask)) throw forbidden();
        }
    }
    public boolean canReadOutsource(UUID id) {
        if (!secured) return true;
        var tasks = jdbc.query("select planning_task_id from outsourcing_order where id = ?", (rs, n) -> rs.getObject(1, UUID.class), id);
        if (tasks.isEmpty()) return false;
        if (tasks.getFirst() != null) return canReadTask(tasks.getFirst());
        var finishing = jdbc.query("select source_task_id from post_treatment_decision where outsourcing_order_id = ?", (rs, n) -> rs.getObject(1, UUID.class), id);
        return finishing.isEmpty() ? globalProduction() || permission("OUTSOURCING_MANAGE") : finishing.stream().anyMatch(this::canReadTask);
    }
    public void requireOutsource(UUID id) { if (!canReadOutsource(id)) throw forbidden(); }
    public void requireAssignedWorker(UUID taskId, String worker) {
        if (secured && !exists("select count(*) from planning_task where id = ? and assigned_to = ?", taskId, worker == null ? null : worker.trim().toUpperCase(java.util.Locale.ROOT))) throw forbidden();
    }
    public SqlScope taskScope(String column) {
        if (!secured || globalProduction() || commercial() || role("PROCESS_ENGINEER", "FINANCE_REVIEWER", "QUALITY_INSPECTOR", "QUALITY_ENGINEER")) return new SqlScope("1=1", java.util.List.of());
        return new SqlScope("""
            exists (select 1 from planning_task scoped_task join planning_batch scoped_batch on scoped_batch.id = scoped_task.batch_id
            join planning_work_order scoped_work on scoped_work.id = scoped_batch.work_order_id
            where scoped_task.id = %s and (scoped_task.assigned_to = ? or exists
                (select 1 from production_supervisor_scope s where s.employee_code = ? and s.route_type = scoped_work.route_type
                 and (not exists (select 1 from production_supervisor_operation_scope o where o.employee_code = s.employee_code and o.route_type = s.route_type)
                  or exists (select 1 from production_supervisor_operation_scope o where o.employee_code = s.employee_code and o.route_type = s.route_type and o.operation_code = scoped_task.operation_code)))))
            """.formatted(column), java.util.List.of(actor(), actor()));
    }
    public record SqlScope(String clause, java.util.List<Object> parameters) { }
    public boolean canBrowseEmployee(String employee) {
        if (!secured || employee.equals(actor()) || role("SYSTEM_ADMIN", "GENERAL_MANAGER", "FINANCE_REVIEWER")) return true;
        if (permission("TASK_DISPATCH")) return exists("""
            select count(*) from production_operator_scope o where o.employee_code = ? and
            (exists (select 1 from production_supervisor_scope s where s.employee_code = ? and s.route_type = o.route_type)
             or exists (select 1 from organization_member_role where employee_code = ? and role_code = 'PRODUCTION_MANAGER'))
            """, employee, actor(), actor());
        return role("FRONT_DESK_CLERK", "CUSTOMER_MANAGER") && exists("select count(*) from organization_member_role where employee_code = ? and role_code = 'SALES_REP'", employee);
    }
    public boolean assignedOrder(UUID id) {
        return exists("""
            select count(*) from planning_task t join planning_batch b on b.id = t.batch_id
            join planning_work_order w on w.id = b.work_order_id where w.order_id = ? and t.assigned_to = ?
            """, id, actor());
    }
    boolean exists(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments) > 0;
    }
    static DomainException forbidden() {
        return DomainException.forbidden("BUSINESS_ACCESS_DENIED", "当前账号无此操作权限或数据不在授权范围内");
    }
}
