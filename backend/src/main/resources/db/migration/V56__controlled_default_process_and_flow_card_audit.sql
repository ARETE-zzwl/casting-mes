alter table customer_order_header add column if not exists default_process_card_version varchar(64);
alter table customer_order_header add column if not exists default_process_released_by varchar(64);
alter table customer_order_header add column if not exists default_process_released_at timestamp with time zone;

alter table planning_work_order add column if not exists process_card_version varchar(64);
alter table planning_work_order add column if not exists engineering_parameters varchar(2000);
alter table planning_work_order add column if not exists engineering_operation_parameters clob;

alter table partial_flow_release add column if not exists handoff_photo_url varchar(1000);
