insert into access_role (code, name, description) values
('FRONT_DESK_CLERK', '前台生产文员', '录入经主管审核的纸质报工和交接资料，不直接执行现场生产任务');

insert into access_role_permission (role_code, permission_code) values
('FRONT_DESK_CLERK', 'WORKBENCH_VIEW'),
('FRONT_DESK_CLERK', 'NOTIFICATION_VIEW'),
('FRONT_DESK_CLERK', 'MANUAL_REPORT_REVIEW'),
('FRONT_DESK_CLERK', 'SUPERVISOR_REPORT');

insert into access_role_permission (role_code, permission_code)
select code, 'CART_OPERATE'
from access_role
where code in (
  'WAX_INJECTION_OPERATOR', 'WAX_REPAIR_OPERATOR', 'TREE_ASSEMBLY_OPERATOR',
  'SHELL_BUILDING_OPERATOR', 'DEWAX_OPERATOR', 'POURING_OPERATOR',
  'KNOCKOUT_OPERATOR', 'FINISHING_OPERATOR'
)
on conflict do nothing;

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at) values
('55000000-0000-0000-0000-000000000001', 'WX01', '射蜡员工01', 'WAX_WORKSHOP', 'WAX_INJECTION_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000002', 'WX02', '射蜡员工02', 'WAX_WORKSHOP', 'WAX_INJECTION_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000003', 'WX03', '射蜡员工03', 'WAX_WORKSHOP', 'WAX_INJECTION_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000004', 'WX04', '射蜡员工04', 'WAX_WORKSHOP', 'WAX_INJECTION_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000005', 'WX05', '射蜡员工05', 'WAX_WORKSHOP', 'WAX_INJECTION_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000006', 'WR01', '修蜡员工01', 'WAX_WORKSHOP', 'WAX_REPAIR_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000007', 'WR02', '修蜡员工02', 'WAX_WORKSHOP', 'WAX_REPAIR_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000008', 'WR03', '修蜡员工03', 'WAX_WORKSHOP', 'WAX_REPAIR_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000009', 'WR04', '修蜡员工04', 'WAX_WORKSHOP', 'WAX_REPAIR_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000010', 'WR05', '修蜡员工05', 'WAX_WORKSHOP', 'WAX_REPAIR_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000011', 'TA01', '组树员工01', 'WAX_WORKSHOP', 'TREE_ASSEMBLY_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000012', 'SH01', '手动制壳员工01', 'WAX_WORKSHOP', 'SHELL_BUILDING_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000013', 'SH02', '手动制壳员工02', 'WAX_WORKSHOP', 'SHELL_BUILDING_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000014', 'DW01', '脱蜡员工01', 'WAX_WORKSHOP', 'DEWAX_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000015', 'DW02', '脱蜡员工02', 'WAX_WORKSHOP', 'DEWAX_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000016', 'PO01', '浇筑员工01', 'WAX_WORKSHOP', 'POURING_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000017', 'PO02', '浇筑员工02', 'WAX_WORKSHOP', 'POURING_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000018', 'KO01', '脱壳员工01', 'WAX_WORKSHOP', 'KNOCKOUT_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000019', 'KO02', '脱壳员工02', 'WAX_WORKSHOP', 'KNOCKOUT_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000020', 'FN01', '喷砂后处理员工', 'WAX_WORKSHOP', 'FINISHING_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000021', 'FN02', '打磨后处理员工', 'WAX_WORKSHOP', 'FINISHING_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000022', 'FN03', '抛丸后处理员工', 'WAX_WORKSHOP', 'FINISHING_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000023', 'FN04', '热处理后处理员工', 'WAX_WORKSHOP', 'FINISHING_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000024', 'FN05', '矫形后处理员工', 'WAX_WORKSHOP', 'FINISHING_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000025', 'FN06', '机加工后处理员工', 'WAX_WORKSHOP', 'FINISHING_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000026', 'FN07', '清洗后处理员工', 'WAX_WORKSHOP', 'FINISHING_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000027', 'FN08', '终检整理员工', 'WAX_WORKSHOP', 'FINISHING_OPERATOR', true, current_timestamp),
('55000000-0000-0000-0000-000000000028', 'S002', '自动化制壳线主管', 'WAX_WORKSHOP', 'PRODUCTION_MANAGER', true, current_timestamp),
('55000000-0000-0000-0000-000000000029', 'S003', '熔炼后处理主管', 'WAX_WORKSHOP', 'PRODUCTION_MANAGER', true, current_timestamp),
('55000000-0000-0000-0000-000000000030', 'FD01', '前台生产文员01', 'DEMO_FACTORY', 'FRONT_DESK_CLERK', true, current_timestamp),
('55000000-0000-0000-0000-000000000031', 'FD02', '前台生产文员02', 'DEMO_FACTORY', 'FRONT_DESK_CLERK', true, current_timestamp),
('55000000-0000-0000-0000-000000000032', 'FD03', '前台生产文员03', 'DEMO_FACTORY', 'FRONT_DESK_CLERK', true, current_timestamp),
('55000000-0000-0000-0000-000000000033', 'E002', '模具工艺工程师', 'DEMO_FACTORY', 'PROCESS_ENGINEER', true, current_timestamp);

insert into organization_member_role (employee_code, role_code)
select employee_code, role_code from organization_member
where employee_code in (
  'WX01','WX02','WX03','WX04','WX05','WR01','WR02','WR03','WR04','WR05','TA01','SH01','SH02',
  'DW01','DW02','PO01','PO02','KO01','KO02','FN01','FN02','FN03','FN04','FN05','FN06','FN07','FN08',
  'S002','S003','FD01','FD02','FD03','E002'
)
on conflict do nothing;
