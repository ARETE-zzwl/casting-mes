insert into access_permission (code, name, module_code) values
('PROCESS_CARD_TEMPLATE_MANAGE', '产品工艺维护', 'engineering');

insert into access_role_permission (role_code, permission_code) values
('PROCESS_ENGINEER', 'PROCESS_CARD_TEMPLATE_MANAGE'),
('SYSTEM_ADMIN', 'PROCESS_CARD_TEMPLATE_MANAGE')
on conflict do nothing;
