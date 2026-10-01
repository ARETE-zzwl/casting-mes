alter table customer_order_header add column general_manager_code varchar(64);
alter table customer_order_header add column general_manager_reviewed_at timestamp with time zone;
alter table customer_order_header add column general_manager_review_note varchar(500);

insert into access_role_permission (role_code, permission_code)
select 'GENERAL_MANAGER', code from access_permission where code = 'ORDER_MANAGE'
on conflict do nothing;
