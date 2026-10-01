insert into access_role_permission (role_code, permission_code)
select code, 'NOTIFICATION_VIEW' from access_role
on conflict do nothing;
