create table production_supervisor_scope (
    employee_code varchar(64) not null,
    route_type varchar(32) not null,
    primary key (employee_code, route_type),
    constraint fk_supervisor_scope_member foreign key (employee_code) references organization_member(employee_code)
);

insert into organization_unit (id, code, name, unit_type, parent_code, active, created_at) values
('56000000-0000-0000-0000-000000000001', 'LOW_WAX_WORKSHOP', '低温蜡车间', 'WORKSHOP', 'DEMO_FACTORY', true, current_timestamp);

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at) values
('56000000-0000-0000-0000-000000000002', 'LW01', '低温蜡车间主管', 'LOW_WAX_WORKSHOP', 'PRODUCTION_MANAGER', true, current_timestamp);

insert into organization_member_role (employee_code, role_code) values
('LW01', 'PRODUCTION_MANAGER');

insert into production_supervisor_scope (employee_code, route_type) values
('S001', 'MID_TEMP_WAX'),
('S002', 'MID_TEMP_WAX'),
('S003', 'MID_TEMP_WAX'),
('LW01', 'LOW_TEMP_WAX');

insert into access_role_permission (role_code, permission_code) values
('GENERAL_MANAGER', 'PLANNING_VIEW'),
('GENERAL_MANAGER', 'TASK_DISPATCH');
