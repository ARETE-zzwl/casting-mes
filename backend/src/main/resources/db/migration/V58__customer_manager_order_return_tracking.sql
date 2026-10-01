alter table customer_order_header add column if not exists customer_manager_returned_by varchar(64);
alter table customer_order_header add column if not exists customer_manager_returned_at timestamp with time zone;
alter table customer_order_header add column if not exists customer_manager_return_reason varchar(500);
alter table customer_order_header add column if not exists customer_manager_return_resolved_at timestamp with time zone;
