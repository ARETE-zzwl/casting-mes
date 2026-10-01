-- Production director can coordinate all lines; workshop supervisors remain line-scoped.
update access_role
set name = '生产总管', description = '统筹中温蜡、低温蜡和砂型产线的排产、派工、进度及异常协调'
where code = 'PRODUCTION_MANAGER';

create table production_operator_scope (
    employee_code varchar(64) not null,
    route_type varchar(32) not null,
    primary key (employee_code, route_type),
    constraint fk_operator_scope_member foreign key (employee_code) references organization_member(employee_code)
);

-- Specialized supervisors must not inherit the old global production-manager role.
delete from organization_member_role
where role_code = 'PRODUCTION_MANAGER'
  and employee_code in ('S001', 'S002', 'S003', 'LW01', 'L001');

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at) values
('64000000-0000-0000-0000-000000000001', 'PM01', '生产总管01', 'DEMO_FACTORY', 'PRODUCTION_MANAGER', true, current_timestamp),
('64000000-0000-0000-0000-000000000011', 'LWX01', '低温射蜡员工01', 'LOW_WAX_WORKSHOP', 'WAX_INJECTION_OPERATOR', true, current_timestamp),
('64000000-0000-0000-0000-000000000012', 'LWR01', '低温修蜡员工01', 'LOW_WAX_WORKSHOP', 'WAX_REPAIR_OPERATOR', true, current_timestamp),
('64000000-0000-0000-0000-000000000013', 'LTA01', '低温组树员工01', 'LOW_WAX_WORKSHOP', 'TREE_ASSEMBLY_OPERATOR', true, current_timestamp),
('64000000-0000-0000-0000-000000000014', 'LSH01', '低温制壳员工01', 'LOW_WAX_WORKSHOP', 'SHELL_BUILDING_OPERATOR', true, current_timestamp),
('64000000-0000-0000-0000-000000000015', 'LDW01', '低温脱蜡员工01', 'LOW_WAX_WORKSHOP', 'DEWAX_OPERATOR', true, current_timestamp),
('64000000-0000-0000-0000-000000000016', 'LP01', '低温浇筑员工01', 'LOW_WAX_WORKSHOP', 'POURING_OPERATOR', true, current_timestamp),
('64000000-0000-0000-0000-000000000017', 'LKO01', '低温脱壳员工01', 'LOW_WAX_WORKSHOP', 'KNOCKOUT_OPERATOR', true, current_timestamp),
('64000000-0000-0000-0000-000000000018', 'LFN01', '低温后处理员工01', 'LOW_WAX_WORKSHOP', 'FINISHING_OPERATOR', true, current_timestamp)
on conflict do nothing;

insert into organization_member_role (employee_code, role_code) values
('PM01', 'PRODUCTION_MANAGER'),
('LWX01', 'WAX_INJECTION_OPERATOR'), ('LWR01', 'WAX_REPAIR_OPERATOR'),
('LTA01', 'TREE_ASSEMBLY_OPERATOR'), ('LSH01', 'SHELL_BUILDING_OPERATOR'),
('LDW01', 'DEWAX_OPERATOR'), ('LP01', 'POURING_OPERATOR'),
('LKO01', 'KNOCKOUT_OPERATOR'), ('LFN01', 'FINISHING_OPERATOR')
on conflict do nothing;

-- Existing wax-workshop operators are middle-temperature staff; low-temperature staff are explicitly seeded above.
insert into production_operator_scope (employee_code, route_type)
select distinct m.employee_code, 'MID_TEMP_WAX'
from organization_member m
join organization_member_role mr on mr.employee_code = m.employee_code
where mr.role_code in ('WAX_INJECTION_OPERATOR', 'WAX_REPAIR_OPERATOR', 'TREE_ASSEMBLY_OPERATOR',
                        'SHELL_BUILDING_OPERATOR', 'DEWAX_OPERATOR', 'POURING_OPERATOR',
                        'KNOCKOUT_OPERATOR', 'CUTTING_OPERATOR', 'FINISHING_OPERATOR', 'OPERATOR')
  and m.employee_code not like 'L%'
on conflict do nothing;

insert into production_operator_scope (employee_code, route_type)
select employee_code, 'LOW_TEMP_WAX'
from organization_member
where employee_code in ('LWX01', 'LWR01', 'LTA01', 'LSH01', 'LDW01', 'LP01', 'LKO01', 'LFN01')
on conflict do nothing;
