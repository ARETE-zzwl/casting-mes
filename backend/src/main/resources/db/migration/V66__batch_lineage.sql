alter table planning_batch add column if not exists batch_type varchar(24) default 'PLANNED' not null;
alter table planning_batch add column if not exists parent_batch_id uuid;
alter table planning_batch add constraint if not exists fk_batch_parent foreign key (parent_batch_id) references planning_batch(id);

update planning_batch set batch_type = 'PLANNED' where batch_type is null;
create index if not exists ix_batch_parent on planning_batch(parent_batch_id);
