alter table manual_report_sheet add column paper_form_no varchar(64);
alter table manual_report_sheet add column paper_image_url varchar(1000);
alter table manual_report_sheet add column rejected_by varchar(64);
alter table manual_report_sheet add column rejected_at timestamp with time zone;
alter table manual_report_sheet add column rejection_reason varchar(500);
create unique index uk_manual_report_paper_form on manual_report_sheet(paper_form_no);

delete from access_role_permission where role_code = 'FRONT_DESK_CLERK' and permission_code = 'MANUAL_REPORT_REVIEW';

insert into access_role_permission (role_code, permission_code)
select role.code, permission.code
from access_role role cross join access_permission permission
where role.code in ('MID_WAX_SUPERVISOR','LOW_WAX_SUPERVISOR','MID_SHELL_SUPERVISOR','LOW_SHELL_SUPERVISOR','POST_PROCESS_SUPERVISOR','GENERAL_MANAGER')
  and permission.code in ('MANUAL_REPORT_REVIEW','SUPERVISOR_REPORT','LABOR_TIME_MANAGE')
on conflict do nothing;
