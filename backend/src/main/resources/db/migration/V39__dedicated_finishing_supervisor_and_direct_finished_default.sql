insert into access_role (code, name, description) values
('FINISHING_SUPERVISOR', '后处理主管', '负责半成品清点后的后处理去向、本厂后处理派工与外送后处理协调');

insert into access_role_permission (role_code, permission_code)
select role.code, permission.code
from access_role role cross join access_permission permission
where role.code = 'FINISHING_SUPERVISOR'
  and permission.code in ('PLANNING_VIEW', 'TASK_DISPATCH', 'TRACE_VIEW', 'WORKBENCH_VIEW', 'LINE_SUPERVISE', 'PIECEWORK_MANAGE', 'PRINT_WORKSHOP_DOCUMENT', 'NOTIFICATION_VIEW', 'MANUAL_REPORT_REVIEW', 'SUPERVISOR_REPORT', 'LABOR_TIME_MANAGE')
on conflict do nothing;

insert into organization_unit (id, code, name, unit_type, parent_code, active, created_at)
select '51000000-0000-0000-0000-000000000005', 'FINISHING_WORKSHOP', '后处理车间', 'WORKSHOP', 'DEMO_FACTORY', true, current_timestamp
where not exists (select 1 from organization_unit where code = 'FINISHING_WORKSHOP');

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at)
select '56000000-0000-0000-0000-000000000001', 'FS001', '后处理主管01', 'FINISHING_WORKSHOP', 'FINISHING_SUPERVISOR', true, current_timestamp
where not exists (select 1 from organization_member where employee_code = 'FS001');

insert into organization_member_role (employee_code, role_code) values
('FS001', 'FINISHING_SUPERVISOR')
on conflict do nothing;

delete from production_supervisor_operation_scope
where employee_code = 'S003'
  and operation_code in ('SEMI_FINISHED_COUNT', 'OPTIONAL_FINISHING', 'FINAL_COUNT');

insert into production_supervisor_scope (employee_code, route_type) values
('FS001', 'MID_TEMP_WAX'),
('FS001', 'LOW_TEMP_WAX')
on conflict do nothing;

insert into production_supervisor_operation_scope (employee_code, route_type, operation_code) values
('FS001', 'MID_TEMP_WAX', 'SEMI_FINISHED_COUNT'), ('FS001', 'MID_TEMP_WAX', 'OPTIONAL_FINISHING'),
('FS001', 'LOW_TEMP_WAX', 'SEMI_FINISHED_COUNT'), ('FS001', 'LOW_TEMP_WAX', 'OPTIONAL_FINISHING')
on conflict do nothing;
