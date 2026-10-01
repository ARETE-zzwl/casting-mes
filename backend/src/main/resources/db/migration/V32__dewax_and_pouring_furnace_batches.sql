create table furnace_batch (
    id uuid primary key,
    furnace_batch_no varchar(64) not null unique,
    operation_code varchar(32) not null,
    furnace_asset_id uuid,
    material_batch varchar(128),
    charge_quantity numeric(19, 3) not null,
    target_temperature numeric(10, 2),
    actual_temperature numeric(10, 2),
    pressure_mpa numeric(10, 3),
    status varchar(24) not null,
    note varchar(1000),
    created_by varchar(64) not null,
    created_at timestamp with time zone not null,
    completed_by varchar(64),
    completed_at timestamp with time zone,
    constraint fk_furnace_batch_asset foreign key (furnace_asset_id) references resource_asset(id),
    constraint ck_furnace_batch_operation check (operation_code in ('DEWAX', 'POURING')),
    constraint ck_furnace_batch_charge check (charge_quantity > 0)
);

create table furnace_batch_task (
    furnace_batch_id uuid not null,
    task_id uuid not null,
    primary key(furnace_batch_id, task_id),
    constraint fk_furnace_batch_task_batch foreign key (furnace_batch_id) references furnace_batch(id),
    constraint fk_furnace_batch_task_task foreign key (task_id) references planning_task(id)
);

create index ix_furnace_batch_operation_status on furnace_batch(operation_code, status, created_at desc);
create index ix_furnace_batch_task_task on furnace_batch_task(task_id);
