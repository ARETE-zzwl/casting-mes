alter table inventory_export_job add column execution_token uuid;
alter table inventory_export_job add column lease_until timestamp with time zone;
alter table inventory_export_job add column attempts integer not null default 0;
create index ix_inventory_export_recovery on inventory_export_job(status, lease_until, created_at);
