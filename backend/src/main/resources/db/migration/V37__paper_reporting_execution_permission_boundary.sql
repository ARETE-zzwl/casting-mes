-- Paper-based execution is performed by the responsible workshop supervisor only.
delete from access_role_permission
where role_code in ('FRONT_DESK_CLERK', 'GENERAL_MANAGER')
  and permission_code in ('MANUAL_REPORT_REVIEW', 'SUPERVISOR_REPORT', 'LABOR_TIME_MANAGE');
