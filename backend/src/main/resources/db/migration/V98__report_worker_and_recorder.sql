alter table execution_report add column if not exists recorded_by varchar(64);
update execution_report set recorded_by = operator_code where recorded_by is null;
