insert into access_permission (code, name, module_code) values
('MOLD_RECEIVE', '模具入库', 'resource')
on conflict do nothing;

insert into access_role_permission (role_code, permission_code) values
('FRONT_DESK_CLERK', 'MOLD_RECEIVE')
on conflict do nothing;
