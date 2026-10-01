create table partial_flow_release (
    id uuid primary key,
    source_task_id uuid not null,
    target_batch_id uuid not null unique,
    quantity numeric(19,3) not null,
    released_by varchar(64) not null,
    released_at timestamp with time zone not null,
    constraint fk_partial_flow_source_task foreign key (source_task_id) references planning_task(id),
    constraint fk_partial_flow_target_batch foreign key (target_batch_id) references planning_batch(id),
    constraint ck_partial_flow_quantity check (quantity > 0)
);
create index ix_partial_flow_source_task on partial_flow_release(source_task_id);
