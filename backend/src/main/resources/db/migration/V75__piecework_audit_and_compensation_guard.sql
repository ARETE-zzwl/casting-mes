alter table piecework_entry add column recorded_by varchar(64);

update piecework_entry
set recorded_by = worker_code
where recorded_by is null;

alter table piecework_entry alter column recorded_by set not null;

create index ix_piecework_task_status on piecework_entry(task_id, status);
