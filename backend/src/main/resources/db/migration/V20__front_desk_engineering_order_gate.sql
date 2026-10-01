alter table customer_order_header
    add column created_by varchar(64);
alter table customer_order_header
    add column process_card_version varchar(64);
alter table customer_order_header
    add column engineering_parameters varchar(2000);
alter table customer_order_header
    add column engineering_confirmed_by varchar(64);
alter table customer_order_header
    add column engineering_confirmed_at timestamp with time zone;

insert into access_role_permission (role_code, permission_code) values
('FRONT_DESK_CLERK', 'ORDER_MANAGE'),
('PROCESS_ENGINEER', 'ORDER_MANAGE')
on conflict do nothing;
