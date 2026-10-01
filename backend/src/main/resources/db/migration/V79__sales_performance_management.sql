alter table customer_order_header add column if not exists sales_owner varchar(100);

update customer_order_customer
set sales_owner = '销售专员01'
where sales_owner is null;

update customer_order_header header
set sales_owner = coalesce((select customer.sales_owner from customer_order_customer customer where customer.id = header.customer_id), '未分配')
where header.sales_owner is null;

create index if not exists ix_order_sales_performance
    on customer_order_header(sales_owner, customer_manager_reviewed_at, general_manager_reviewed_at);

insert into access_permission (code, name, module_code) values
('SALES_PERFORMANCE_VIEW', '销售业绩查看', 'sales')
on conflict do nothing;

insert into access_role_permission (role_code, permission_code) values
('SALES_REP', 'SALES_PERFORMANCE_VIEW'),
('CUSTOMER_MANAGER', 'SALES_PERFORMANCE_VIEW'),
('GENERAL_MANAGER', 'SALES_PERFORMANCE_VIEW'),
('SYSTEM_ADMIN', 'SALES_PERFORMANCE_VIEW')
on conflict do nothing;

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at) values
('79000000-0000-0000-0000-000000000001', 'SAL01', '销售专员01', 'DEMO_COMPANY', 'SALES_REP', true, current_timestamp),
('79000000-0000-0000-0000-000000000002', 'SAL02', '销售专员02', 'DEMO_COMPANY', 'SALES_REP', true, current_timestamp)
on conflict do nothing;

insert into organization_member_role (employee_code, role_code) values
('SAL01', 'SALES_REP'),
('SAL02', 'SALES_REP')
on conflict do nothing;
