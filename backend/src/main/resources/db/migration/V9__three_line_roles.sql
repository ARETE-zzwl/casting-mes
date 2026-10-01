insert into access_permission (code, name, module_code) values
('SCHEDULE_MANAGE', '三线排产', 'planning'),
('LINE_SUPERVISE', '车间主管操作', 'execution'),
('PROCESS_RECEIVE', '工序接收确认', 'execution'),
('CART_OPERATE', '周转车装卸', 'resource'),
('MOLD_REQUEST', '模具申请', 'resource'),
('MOLD_ISSUE', '模具出入库', 'resource'),
('PROCESS_CARD_VIEW', '工艺卡查看', 'engineering'),
('MOBILE_REPORT', '移动报工', 'execution'),
('NOTIFICATION_VIEW', '消息待办', 'notification');

insert into access_role (code, name, description) values
('GLOBAL_SCHEDULER', '全局生产调度', '统览三线队列、处理优先级冲突并排产'),
('WORKSHOP_SUPERVISOR', '车间主管', '接收、派工、进度申报和资源占用'),
('MOLD_KEEPER', '模具仓管理员', '模具申请确认、出库与归还'),
('CART_OPERATOR', '周转车操作员', '扫码装卸和混装交接');

insert into access_role_permission (role_code, permission_code) values
('GLOBAL_SCHEDULER', 'SCHEDULE_MANAGE'),
('GLOBAL_SCHEDULER', 'PLANNING_VIEW'),
('GLOBAL_SCHEDULER', 'DASHBOARD_VIEW'),
('GLOBAL_SCHEDULER', 'NOTIFICATION_VIEW'),
('WORKSHOP_SUPERVISOR', 'LINE_SUPERVISE'),
('WORKSHOP_SUPERVISOR', 'PROCESS_RECEIVE'),
('WORKSHOP_SUPERVISOR', 'TASK_DISPATCH'),
('WORKSHOP_SUPERVISOR', 'WORKBENCH_VIEW'),
('WORKSHOP_SUPERVISOR', 'PROCESS_CARD_VIEW'),
('WORKSHOP_SUPERVISOR', 'NOTIFICATION_VIEW'),
('MOLD_KEEPER', 'MOLD_ISSUE'),
('MOLD_KEEPER', 'MOLD_REQUEST'),
('MOLD_KEEPER', 'INVENTORY_MANAGE'),
('MOLD_KEEPER', 'NOTIFICATION_VIEW'),
('CART_OPERATOR', 'CART_OPERATE'),
('CART_OPERATOR', 'MOBILE_REPORT'),
('CART_OPERATOR', 'WORKBENCH_VIEW'),
('CART_OPERATOR', 'NOTIFICATION_VIEW'),
('OPERATOR', 'MOBILE_REPORT'),
('OPERATOR', 'PROCESS_CARD_VIEW'),
('OPERATOR', 'NOTIFICATION_VIEW'),
('PRODUCTION_MANAGER', 'SCHEDULE_MANAGE'),
('PRODUCTION_MANAGER', 'LINE_SUPERVISE'),
('PRODUCTION_MANAGER', 'MOLD_REQUEST'),
('PRODUCTION_MANAGER', 'NOTIFICATION_VIEW'),
('PROCESS_ENGINEER', 'PROCESS_CARD_VIEW'),
('PROCESS_ENGINEER', 'MOLD_REQUEST'),
('WAREHOUSE_CLERK', 'MOLD_ISSUE'),
('WAREHOUSE_CLERK', 'NOTIFICATION_VIEW');

insert into access_role_permission (role_code, permission_code)
select 'SYSTEM_ADMIN', code from access_permission
where code in ('SCHEDULE_MANAGE', 'LINE_SUPERVISE', 'PROCESS_RECEIVE', 'CART_OPERATE',
               'MOLD_REQUEST', 'MOLD_ISSUE', 'PROCESS_CARD_VIEW', 'MOBILE_REPORT', 'NOTIFICATION_VIEW');

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at) values
('52000000-0000-0000-0000-000000000009', 'P001', '全局调度员', 'WAX_WORKSHOP', 'GLOBAL_SCHEDULER', true, current_timestamp),
('52000000-0000-0000-0000-000000000010', 'L001', '制壳车间主任', 'WAX_WORKSHOP', 'WORKSHOP_SUPERVISOR', true, current_timestamp),
('52000000-0000-0000-0000-000000000011', 'M001', '模具仓管理员', 'DEMO_FACTORY', 'MOLD_KEEPER', true, current_timestamp),
('52000000-0000-0000-0000-000000000012', 'C001', '周转车操作员', 'WAX_TEAM', 'CART_OPERATOR', true, current_timestamp);

insert into organization_member_role (employee_code, role_code) values
('P001', 'GLOBAL_SCHEDULER'),
('L001', 'WORKSHOP_SUPERVISOR'),
('L001', 'OPERATOR'),
('M001', 'MOLD_KEEPER'),
('C001', 'CART_OPERATOR');
