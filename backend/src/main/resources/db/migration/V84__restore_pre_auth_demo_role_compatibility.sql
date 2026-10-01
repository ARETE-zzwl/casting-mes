-- Preserve the no-login test roster capabilities after the deployed baseline migration.
update organization_member
set role_code = 'OPERATOR'
where employee_code = 'W002';

insert into organization_member_role (employee_code, role_code)
select 'W002', 'OPERATOR'
where not exists (select 1 from organization_member_role where employee_code = 'W002' and role_code = 'OPERATOR');

insert into organization_member_role (employee_code, role_code)
select 'S001', 'PROCESS_ENGINEER'
where not exists (select 1 from organization_member_role where employee_code = 'S001' and role_code = 'PROCESS_ENGINEER');
