-- Keep the role model intact, but make the demo accounts reflect their actual workshop posts.
update organization_member set name = '中温蜡间主管01', role_code = 'MID_WAX_SUPERVISOR' where employee_code = 'S001';
update organization_member set name = '低温蜡间主管01', role_code = 'LOW_WAX_SUPERVISOR' where employee_code = 'LW01';
update organization_member set name = '中温蜡制壳主管01', role_code = 'MID_SHELL_SUPERVISOR' where employee_code = 'S002';
update organization_member set name = '低温蜡制壳主管01', role_code = 'LOW_SHELL_SUPERVISOR' where employee_code = 'L001';
update organization_member set name = '后续工艺主管01', role_code = 'POST_PROCESS_SUPERVISOR' where employee_code = 'S003';

-- S001 was originally seeded as both a supervisor and an engineer. Keep the demonstration posts independent.
delete from organization_member_role where employee_code = 'S001' and role_code in ('PRODUCTION_MANAGER', 'PROCESS_ENGINEER', 'WORKSHOP_SUPERVISOR');

insert into organization_member_role (employee_code, role_code) values
('S001', 'MID_WAX_SUPERVISOR'),
('LW01', 'LOW_WAX_SUPERVISOR'),
('S002', 'MID_SHELL_SUPERVISOR'),
('L001', 'LOW_SHELL_SUPERVISOR'),
('S003', 'POST_PROCESS_SUPERVISOR')
on conflict do nothing;
