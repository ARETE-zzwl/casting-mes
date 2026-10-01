alter table post_treatment_decision add column process_codes varchar(1000);
alter table post_treatment_decision add column warehouse_code varchar(64);
alter table post_treatment_decision drop constraint ck_post_treatment_destination;
alter table post_treatment_decision add constraint ck_post_treatment_destination
    check (destination in ('IN_HOUSE', 'OUTSOURCE', 'DIRECT_FINISHED', 'FINISHED_GOODS_STORAGE'));

insert into access_role (code, name, description) values
('CUTTING_OPERATOR', '分割操作工', '完成分割、自检、数量报工和半成品交接')
on conflict do nothing;

insert into access_role_permission (role_code, permission_code)
select 'CUTTING_OPERATOR', code from access_permission
where code in ('WORKBENCH_VIEW', 'TASK_EXECUTE', 'MOBILE_REPORT', 'PROCESS_CARD_VIEW', 'NOTIFICATION_VIEW', 'TASK_SELF_CLAIM')
on conflict do nothing;

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at)
select '57000000-0000-0000-0000-000000000001', 'CT01', '分割员工01', 'WAX_WORKSHOP', 'CUTTING_OPERATOR', true, current_timestamp
where not exists (select 1 from organization_member where employee_code = 'CT01');

insert into organization_member_role (employee_code, role_code) values
('CT01', 'CUTTING_OPERATOR')
on conflict do nothing;

insert into production_supervisor_operation_scope (employee_code, route_type, operation_code) values
('S003', 'MID_TEMP_WAX', 'KNOCKOUT'), ('S003', 'MID_TEMP_WAX', 'CUTTING'),
('S003', 'LOW_TEMP_WAX', 'KNOCKOUT'), ('S003', 'LOW_TEMP_WAX', 'CUTTING')
on conflict do nothing;
