insert into access_permission (code, name, module_code) values
('WORKSHOP_DISPLAY_VIEW', '车间可视化大屏', 'reporting')
on conflict do nothing;

insert into access_role (code, name, description) values
('WORKSHOP_DISPLAY', '车间大屏', '只读展示指定车间的现场队列、产出、异常与扫码定位，不含客户商务信息和生产操作权限')
on conflict do nothing;

insert into access_role_permission (role_code, permission_code) values
('WORKSHOP_DISPLAY', 'WORKSHOP_DISPLAY_VIEW')
on conflict do nothing;

insert into access_role_permission (role_code, permission_code) values
('SYSTEM_ADMIN', 'WORKSHOP_DISPLAY_VIEW')
on conflict do nothing;

insert into organization_unit (id, code, name, unit_type, parent_code, active, created_at) values
('5b000000-0000-0000-0000-000000000001', 'KNOCKOUT_WORKSHOP', '脱壳车间', 'WORKSHOP', 'DEMO_FACTORY', true, current_timestamp)
on conflict do nothing;

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at) values
('5b000000-0000-0000-0000-000000000011', 'DSP-WAX', '蜡间车间大屏', 'WAX_WORKSHOP', 'WORKSHOP_DISPLAY', true, current_timestamp)
on conflict do nothing;

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at) values
('5b000000-0000-0000-0000-000000000012', 'DSP-KO', '脱壳车间大屏', 'KNOCKOUT_WORKSHOP', 'WORKSHOP_DISPLAY', true, current_timestamp)
on conflict do nothing;

insert into organization_member_role (employee_code, role_code) values
('DSP-WAX', 'WORKSHOP_DISPLAY')
on conflict do nothing;

insert into organization_member_role (employee_code, role_code) values
('DSP-KO', 'WORKSHOP_DISPLAY')
on conflict do nothing;
