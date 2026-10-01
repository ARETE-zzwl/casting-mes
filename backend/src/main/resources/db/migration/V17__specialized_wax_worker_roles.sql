update organization_member set role_code = 'WAX_INJECTION_OPERATOR' where employee_code = 'W001';

insert into organization_member_role (employee_code, role_code) values
('W001', 'WAX_INJECTION_OPERATOR'),
('W001', 'WAX_REPAIR_OPERATOR'),
('W001', 'TREE_ASSEMBLY_OPERATOR');
