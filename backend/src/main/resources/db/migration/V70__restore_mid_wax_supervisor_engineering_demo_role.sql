-- The demonstration account intentionally combines wax-workshop supervision and engineering confirmation.
-- The UI names the primary post clearly while the second role preserves the configured multi-role workflow.
insert into organization_member_role (employee_code, role_code)
values ('S001', 'PROCESS_ENGINEER')
on conflict do nothing;
