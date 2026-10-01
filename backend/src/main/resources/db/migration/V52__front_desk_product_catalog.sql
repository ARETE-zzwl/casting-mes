insert into access_role_permission (role_code, permission_code)
select 'FRONT_DESK_CLERK', code from access_permission where code = 'MASTERDATA_MANAGE'
on conflict do nothing;
