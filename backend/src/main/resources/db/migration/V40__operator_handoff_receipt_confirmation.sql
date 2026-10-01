alter table production_handoff_event add column receiving_task_id uuid;
alter table production_handoff_event add column exception_type varchar(32);
alter table production_handoff_event add column received_device_code varchar(64);
alter table production_handoff_event add column received_workstation_code varchar(64);

alter table production_handoff_event add constraint fk_handoff_receiving_task
    foreign key (receiving_task_id) references planning_task(id);

create index ix_handoff_receiving_task on production_handoff_event(receiving_task_id, occurred_at desc);
