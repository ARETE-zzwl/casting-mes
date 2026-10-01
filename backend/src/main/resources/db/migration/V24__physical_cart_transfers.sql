create table production_cart_transfer (
    id uuid primary key,
    transfer_no varchar(64) not null unique,
    cart_asset_id uuid not null,
    source_task_id uuid not null,
    target_task_id uuid not null,
    loaded_quantity numeric(19, 3) not null,
    received_quantity numeric(19, 3),
    status varchar(24) not null,
    load_photo_url varchar(1000),
    receive_photo_url varchar(1000),
    loaded_by varchar(64) not null,
    received_by varchar(64),
    exception_reason varchar(500),
    loaded_at timestamp with time zone not null,
    received_at timestamp with time zone,
    constraint fk_cart_transfer_asset foreign key (cart_asset_id) references resource_asset(id),
    constraint fk_cart_transfer_source_task foreign key (source_task_id) references planning_task(id),
    constraint fk_cart_transfer_target_task foreign key (target_task_id) references planning_task(id),
    constraint ck_cart_transfer_quantity check (loaded_quantity > 0 and (received_quantity is null or received_quantity >= 0))
);

create index ix_cart_transfer_cart_status on production_cart_transfer(cart_asset_id, status, loaded_at);
create index ix_cart_transfer_source on production_cart_transfer(source_task_id, loaded_at);
create index ix_cart_transfer_target on production_cart_transfer(target_task_id, status, loaded_at);
