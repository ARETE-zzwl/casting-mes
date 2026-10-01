alter table execution_report add column photo_url varchar(1000);
alter table production_handoff_event add column photo_url varchar(1000);

alter table shell_building_record add column next_action varchar(32) not null default 'WAIT_NEXT_LAYER';
alter table shell_building_record add column photo_url varchar(1000);
alter table shell_building_record add constraint ck_shell_record_next_action
    check (next_action in ('WAIT_NEXT_LAYER', 'FLOW_TO_NEXT'));

create table post_treatment_decision (
    id uuid primary key,
    batch_id uuid not null unique,
    source_task_id uuid not null,
    destination varchar(32) not null,
    process_summary varchar(1000),
    supplier_id uuid,
    outsourcing_order_id uuid,
    note varchar(500),
    decided_by varchar(64) not null,
    decided_at timestamp with time zone not null,
    constraint fk_post_treatment_batch foreign key (batch_id) references planning_batch(id),
    constraint fk_post_treatment_task foreign key (source_task_id) references planning_task(id),
    constraint ck_post_treatment_destination check (destination in ('IN_HOUSE', 'OUTSOURCE', 'DIRECT_FINISHED'))
);
create index ix_post_treatment_source_task on post_treatment_decision(source_task_id);
