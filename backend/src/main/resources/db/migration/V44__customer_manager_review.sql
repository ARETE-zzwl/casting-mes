alter table customer_order_header add column customer_manager_code varchar(64);
alter table customer_order_header add column customer_manager_reviewed_at timestamp with time zone;
alter table customer_order_header add column customer_manager_review_note varchar(500);

insert into access_role (code, name, description) values
('CUSTOMER_MANAGER', '客户经理', '核对前台录入订单、客户需求、交期和商务备注')
on conflict do nothing;

insert into access_role_permission (role_code, permission_code)
select 'CUSTOMER_MANAGER', code from access_permission
where code in ('ORDER_MANAGE', 'TRACE_VIEW', 'WORKBENCH_VIEW', 'NOTIFICATION_VIEW')
on conflict do nothing;

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at)
select '58000000-0000-0000-0000-000000000001', 'CM001', '客户经理01', 'DEMO_COMPANY', 'CUSTOMER_MANAGER', true, current_timestamp
where not exists (select 1 from organization_member where employee_code = 'CM001');

insert into organization_member_role (employee_code, role_code) values ('CM001', 'CUSTOMER_MANAGER')
on conflict do nothing;
