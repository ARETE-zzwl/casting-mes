create table inventory_export_job (
    id uuid primary key,
    operation_id uuid not null unique,
    job_no varchar(64) not null unique,
    requested_by varchar(64) not null,
    warehouse_code varchar(64),
    keyword varchar(160),
    movement_type varchar(32),
    operator_code varchar(64),
    from_date date,
    to_date date,
    status varchar(24) not null,
    file_name varchar(180),
    file_content clob,
    error_message varchar(1000),
    created_at timestamp with time zone not null,
    completed_at timestamp with time zone
);

create index ix_inventory_export_job_owner_status
    on inventory_export_job(requested_by, status, created_at desc);
