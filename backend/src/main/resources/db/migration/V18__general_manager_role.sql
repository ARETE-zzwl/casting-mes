insert into access_role (code, name, description) values
('GENERAL_MANAGER', '总经理', '查看经营、生产、交期、质量与交付风险，进行管理决策但不执行现场业务操作');

insert into access_role_permission (role_code, permission_code) values
('GENERAL_MANAGER', 'DASHBOARD_VIEW'),
('GENERAL_MANAGER', 'TRACE_VIEW'),
('GENERAL_MANAGER', 'NOTIFICATION_VIEW'),
('GENERAL_MANAGER', 'PRINT_STATISTICS'),
('GENERAL_MANAGER', 'PRINT_SENSITIVE_ORDER'),
('GENERAL_MANAGER', 'PRINT_AUDIT_VIEW'),
('GENERAL_MANAGER', 'DOCUMENT_EXPORT');

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at) values
('54000000-0000-0000-0000-000000000003', 'GM001', '总经理', 'DEMO_FACTORY', 'GENERAL_MANAGER', true, current_timestamp);

insert into organization_member_role (employee_code, role_code) values
('GM001', 'GENERAL_MANAGER');
