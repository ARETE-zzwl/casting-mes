alter table customer_order_header add column order_drawing_url text;

create table production_supervisor_operation_scope (
    employee_code varchar(64) not null,
    route_type varchar(32) not null,
    operation_code varchar(64) not null,
    primary key (employee_code, route_type, operation_code),
    constraint fk_supervisor_operation_member foreign key (employee_code) references organization_member(employee_code)
);

insert into access_role (code, name, description) values
('MID_WAX_SUPERVISOR', '中温蜡间主管', '负责中温蜡射蜡、修蜡、组树派工与计件单价'),
('LOW_WAX_SUPERVISOR', '低温蜡间主管', '负责低温蜡射蜡、修蜡、组树派工与计件单价'),
('MID_SHELL_SUPERVISOR', '中温蜡制壳主管', '负责中温蜡自动及人工制壳派工与进度'),
('LOW_SHELL_SUPERVISOR', '低温蜡制壳主管', '负责低温蜡制壳派工与进度'),
('POST_PROCESS_SUPERVISOR', '后续工艺主管', '负责脱蜡、浇筑、脱壳、后处理及成品清点协同');

insert into access_role_permission (role_code, permission_code)
select role.code, permission.code
from access_role role cross join access_permission permission
where role.code in ('MID_WAX_SUPERVISOR','LOW_WAX_SUPERVISOR','MID_SHELL_SUPERVISOR','LOW_SHELL_SUPERVISOR','POST_PROCESS_SUPERVISOR')
  and permission.code in ('ORDER_MANAGE','PLANNING_VIEW','TASK_DISPATCH','TRACE_VIEW','WORKBENCH_VIEW','LINE_SUPERVISE','SCHEDULE_MANAGE','PIECEWORK_MANAGE','PRINT_WORKSHOP_DOCUMENT','NOTIFICATION_VIEW');

insert into access_role_permission (role_code, permission_code) values
('PRODUCTION_MANAGER', 'PIECEWORK_MANAGE'),
('FRONT_DESK_CLERK', 'TRACE_VIEW')
on conflict do nothing;

update organization_member set role_code = 'MID_WAX_SUPERVISOR' where employee_code = 'S001';
update organization_member set role_code = 'LOW_WAX_SUPERVISOR' where employee_code = 'LW01';
update organization_member set role_code = 'MID_SHELL_SUPERVISOR' where employee_code = 'S002';
update organization_member set role_code = 'LOW_SHELL_SUPERVISOR' where employee_code = 'L001';
update organization_member set role_code = 'POST_PROCESS_SUPERVISOR' where employee_code = 'S003';

insert into organization_member_role (employee_code, role_code) values
('S001', 'MID_WAX_SUPERVISOR'), ('LW01', 'LOW_WAX_SUPERVISOR'), ('S002', 'MID_SHELL_SUPERVISOR'),
('L001', 'LOW_SHELL_SUPERVISOR'), ('S003', 'POST_PROCESS_SUPERVISOR')
on conflict do nothing;

insert into production_supervisor_operation_scope (employee_code, route_type, operation_code) values
('S001','MID_TEMP_WAX','WAX_INJECTION'), ('S001','MID_TEMP_WAX','WAX_REPAIR'), ('S001','MID_TEMP_WAX','TREE_ASSEMBLY'),
('LW01','LOW_TEMP_WAX','WAX_INJECTION'), ('LW01','LOW_TEMP_WAX','WAX_REPAIR'), ('LW01','LOW_TEMP_WAX','TREE_ASSEMBLY'),
('S002','MID_TEMP_WAX','SHELL_BUILDING'), ('S002','MID_TEMP_WAX','MANUAL_SHELL_BUILDING'),
('L001','LOW_TEMP_WAX','SHELL_BUILDING'), ('L001','LOW_TEMP_WAX','MANUAL_SHELL_BUILDING'),
('S003','MID_TEMP_WAX','DEWAX'), ('S003','MID_TEMP_WAX','POURING'), ('S003','MID_TEMP_WAX','KNOCKOUT_CUTTING'), ('S003','MID_TEMP_WAX','SEMI_FINISHED_COUNT'), ('S003','MID_TEMP_WAX','OPTIONAL_FINISHING'), ('S003','MID_TEMP_WAX','FINAL_COUNT'),
('S003','LOW_TEMP_WAX','DEWAX'), ('S003','LOW_TEMP_WAX','POURING'), ('S003','LOW_TEMP_WAX','KNOCKOUT_CUTTING'), ('S003','LOW_TEMP_WAX','SEMI_FINISHED_COUNT'), ('S003','LOW_TEMP_WAX','OPTIONAL_FINISHING'), ('S003','LOW_TEMP_WAX','FINAL_COUNT')
on conflict do nothing;
