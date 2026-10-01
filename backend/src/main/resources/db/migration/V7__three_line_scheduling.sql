create table schedule_queue_item (
    id uuid primary key,
    task_id uuid not null unique,
    line_code varchar(64) not null,
    manual_rank integer not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_schedule_queue_task foreign key (task_id) references planning_task(id)
);

create index ix_schedule_queue_line_operation on schedule_queue_item(line_code, manual_rank);
