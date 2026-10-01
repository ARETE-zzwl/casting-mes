-- Low-temperature wax workshop uses one trained team member for knockout and cutting.
-- Keep both operating roles so the worker can also claim cutting tasks from the mobile workbench.
insert into organization_member_role (employee_code, role_code)
values ('LKO01', 'CUTTING_OPERATOR')
on conflict do nothing;
