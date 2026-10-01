insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at) values
('54000000-0000-0000-0000-000000000001', 'P002', '浇筑操作工（演示）', 'WAX_WORKSHOP', 'POURING_OPERATOR', true, current_timestamp),
('54000000-0000-0000-0000-000000000002', 'K002', '脱壳分割操作工（演示）', 'WAX_WORKSHOP', 'KNOCKOUT_OPERATOR', true, current_timestamp);

insert into organization_member_role (employee_code, role_code) values
('P002', 'POURING_OPERATOR'),
('K002', 'KNOCKOUT_OPERATOR');
