create table mold_request (
    id uuid primary key,
    request_no varchar(64) not null unique,
    mold_asset_id uuid,
    product_code varchar(64) not null,
    status varchar(32) not null,
    requested_by varchar(64) not null,
    engineer_code varchar(64),
    warehouse_code varchar(64),
    requested_at timestamp with time zone not null,
    approved_at timestamp with time zone,
    issued_at timestamp with time zone,
    returned_at timestamp with time zone,
    constraint fk_mold_request_asset foreign key (mold_asset_id) references resource_asset(id)
);

create table mold_movement (
    id uuid primary key,
    mold_request_id uuid not null,
    movement_type varchar(24) not null,
    operator_code varchar(64) not null,
    warehouse_code varchar(64),
    occurred_at timestamp with time zone not null,
    constraint fk_mold_movement_request foreign key (mold_request_id) references mold_request(id)
);

create index ix_mold_request_status on mold_request(status, requested_at);
create index ix_mold_movement_request on mold_movement(mold_request_id, occurred_at);
