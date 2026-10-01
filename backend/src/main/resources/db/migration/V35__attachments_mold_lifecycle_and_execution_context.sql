alter table resource_asset add column maintenance_interval_days integer;
alter table resource_asset add column next_maintenance_date date;
alter table resource_asset add column mold_lock_reason varchar(500);
alter table resource_asset add column mold_locked_at timestamp with time zone;

create table mold_maintenance_record (
    id uuid primary key,
    mold_asset_id uuid not null,
    record_no varchar(64) not null unique,
    record_type varchar(24) not null,
    description varchar(1000) not null,
    service_provider varchar(160),
    performed_by varchar(64) not null,
    occurred_at timestamp with time zone not null,
    next_maintenance_date date,
    constraint fk_mold_maintenance_asset foreign key (mold_asset_id) references resource_asset(id)
);

create index ix_mold_maintenance_asset_occurred on mold_maintenance_record(mold_asset_id, occurred_at desc);
create index ix_mold_maintenance_next_due on resource_asset(next_maintenance_date, asset_type);

alter table execution_report add column device_code varchar(64);
alter table execution_report add column workstation_code varchar(64);

alter table production_cart_transfer add column load_device_code varchar(64);
alter table production_cart_transfer add column load_workstation_code varchar(64);
alter table production_cart_transfer add column receive_device_code varchar(64);
alter table production_cart_transfer add column receive_workstation_code varchar(64);
alter table production_cart_transfer add column load_operation_id uuid;
alter table production_cart_transfer add column receive_operation_id uuid;
create unique index uk_cart_transfer_load_operation on production_cart_transfer(load_operation_id);
create unique index uk_cart_transfer_receive_operation on production_cart_transfer(receive_operation_id);

create table operation_audit_event (
    id uuid primary key,
    event_type varchar(32) not null,
    entity_type varchar(32) not null,
    entity_id uuid not null,
    operation_id uuid,
    operator_code varchar(64) not null,
    device_code varchar(64),
    workstation_code varchar(64),
    occurred_at timestamp with time zone not null,
    detail varchar(500)
);

create unique index uk_operation_audit_operation on operation_audit_event(operation_id);
create index ix_operation_audit_entity on operation_audit_event(entity_type, entity_id, occurred_at desc);
