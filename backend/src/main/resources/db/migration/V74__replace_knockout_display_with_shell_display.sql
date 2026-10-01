insert into organization_unit (id, code, name, unit_type, parent_code, active, created_at) values
('5b000000-0000-0000-0000-000000000002', 'SHELL_WORKSHOP', '制壳车间', 'WORKSHOP', 'DEMO_FACTORY', true, current_timestamp)
on conflict do nothing;

update organization_member set active = false where employee_code = 'DSP-KO';

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at) values
('5b000000-0000-0000-0000-000000000013', 'DSP-SHELL', '制壳车间大屏', 'SHELL_WORKSHOP', 'WORKSHOP_DISPLAY', true, current_timestamp)
on conflict do nothing;

insert into organization_member_role (employee_code, role_code) values
('DSP-SHELL', 'WORKSHOP_DISPLAY')
on conflict do nothing;
