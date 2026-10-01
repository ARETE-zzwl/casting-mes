insert into access_permission (code, name, module_code) values
('PRINT_WORKSHOP_DOCUMENT', '打印车间作业单', 'document'),
('PRINT_SENSITIVE_ORDER', '打印敏感订单明细', 'document'),
('PRINT_STATISTICS', '打印统计报表', 'document'),
('PRINT_AUDIT_VIEW', '查看打印导出审计', 'document'),
('DOCUMENT_EXPORT', '导出受控文档', 'document');

insert into access_role (code, name, description) values
('PLANT_MANAGER', '厂长/生产总监', '查看全厂经营与生产态势，审核排产和关键例外'),
('MOLD_ENGINEER', '模具工程师', '评估模具适配、定制和寿命，维护模具技术资料'),
('RAW_MATERIAL_KEEPER', '原材料仓管员', '原材料收发、批次盘点与投料追溯'),
('FINISHED_GOODS_KEEPER', '成品仓管员', '成品清点、入库、备货与交付交接'),
('OUTSOURCING_COORDINATOR', '外协协调员', '砂型外协下单、在途跟踪、收货与异常闭环'),
('QUALITY_ENGINEER', '质量工程师', '质量标准、异常判定、处置闭环和质量分析'),
('METALLURGY_LAB', '冶金/实验室人员', '炉前成分、温度和试样检验记录'),
('MAINTENANCE_EHS', '设备与EHS人员', '设备点检、维修保养、安全环保风险处置'),
('WAX_INJECTION_OPERATOR', '射蜡操作工', '按任务和工艺卡制作蜡模、报工和报废记录'),
('WAX_REPAIR_OPERATOR', '修蜡操作工', '按任务修整蜡模、报工和不良记录'),
('TREE_ASSEMBLY_OPERATOR', '组树操作工', '按组树标准图组树、记录每树件数和流转数量'),
('SHELL_BUILDING_OPERATOR', '制壳操作工', '按工艺卡完成自动/手工制壳和干燥记录'),
('DEWAX_OPERATOR', '脱蜡操作工', '按炉次装炉、脱蜡和出炉数量报工'),
('POURING_OPERATOR', '浇筑操作工', '按炉次浇筑、记录炉前参数和产出'),
('KNOCKOUT_OPERATOR', '脱壳分割操作工', '脱壳分割、半成品交接和报废记录'),
('FINISHING_OPERATOR', '后处理操作工', '执行指定后处理、成品清点前的数量报工');

insert into access_role_permission (role_code, permission_code) values
('SYSTEM_ADMIN', 'PRINT_WORKSHOP_DOCUMENT'),
('SYSTEM_ADMIN', 'PRINT_SENSITIVE_ORDER'),
('SYSTEM_ADMIN', 'PRINT_STATISTICS'),
('SYSTEM_ADMIN', 'PRINT_AUDIT_VIEW'),
('SYSTEM_ADMIN', 'DOCUMENT_EXPORT'),
('PRODUCTION_MANAGER', 'PRINT_WORKSHOP_DOCUMENT'),
('PRODUCTION_MANAGER', 'PRINT_SENSITIVE_ORDER'),
('PRODUCTION_MANAGER', 'PRINT_STATISTICS'),
('PRODUCTION_MANAGER', 'PRINT_AUDIT_VIEW'),
('PRODUCTION_MANAGER', 'DOCUMENT_EXPORT'),
('GLOBAL_SCHEDULER', 'PRINT_WORKSHOP_DOCUMENT'),
('GLOBAL_SCHEDULER', 'PRINT_STATISTICS'),
('GLOBAL_SCHEDULER', 'DOCUMENT_EXPORT'),
('WORKSHOP_SUPERVISOR', 'PRINT_WORKSHOP_DOCUMENT'),
('WORKSHOP_SUPERVISOR', 'DOCUMENT_EXPORT'),
('PROCESS_ENGINEER', 'PRINT_WORKSHOP_DOCUMENT'),
('PROCESS_ENGINEER', 'DOCUMENT_EXPORT'),
('FINANCE_REVIEWER', 'PRINT_STATISTICS'),
('FINANCE_REVIEWER', 'DOCUMENT_EXPORT'),
('QUALITY_INSPECTOR', 'PRINT_WORKSHOP_DOCUMENT'),
('QUALITY_INSPECTOR', 'DOCUMENT_EXPORT'),
('DELIVERY_COORDINATOR', 'PRINT_SENSITIVE_ORDER'),
('DELIVERY_COORDINATOR', 'DOCUMENT_EXPORT'),
('PLANT_MANAGER', 'DASHBOARD_VIEW'),
('PLANT_MANAGER', 'TRACE_VIEW'),
('PLANT_MANAGER', 'PRINT_STATISTICS'),
('PLANT_MANAGER', 'PRINT_SENSITIVE_ORDER'),
('PLANT_MANAGER', 'PRINT_AUDIT_VIEW'),
('PLANT_MANAGER', 'DOCUMENT_EXPORT'),
('MOLD_ENGINEER', 'MOLD_REQUEST'),
('MOLD_ENGINEER', 'MOLD_ISSUE'),
('MOLD_ENGINEER', 'PROCESS_CARD_VIEW'),
('MOLD_ENGINEER', 'PRINT_WORKSHOP_DOCUMENT'),
('RAW_MATERIAL_KEEPER', 'INVENTORY_MANAGE'),
('FINISHED_GOODS_KEEPER', 'INVENTORY_MANAGE'),
('FINISHED_GOODS_KEEPER', 'FULFILLMENT_MANAGE'),
('FINISHED_GOODS_KEEPER', 'PRINT_WORKSHOP_DOCUMENT'),
('OUTSOURCING_COORDINATOR', 'OUTSOURCING_MANAGE'),
('OUTSOURCING_COORDINATOR', 'TRACE_VIEW'),
('QUALITY_ENGINEER', 'QUALITY_MANAGE'),
('QUALITY_ENGINEER', 'TRACE_VIEW'),
('QUALITY_ENGINEER', 'PRINT_WORKSHOP_DOCUMENT'),
('METALLURGY_LAB', 'QUALITY_MANAGE'),
('METALLURGY_LAB', 'PROCESS_CARD_VIEW'),
('MAINTENANCE_EHS', 'WORKBENCH_VIEW'),
('MAINTENANCE_EHS', 'NOTIFICATION_VIEW');

insert into access_role_permission (role_code, permission_code)
select role.code, permission.code
from access_role role cross join access_permission permission
where role.code in (
  'WAX_INJECTION_OPERATOR', 'WAX_REPAIR_OPERATOR', 'TREE_ASSEMBLY_OPERATOR',
  'SHELL_BUILDING_OPERATOR', 'DEWAX_OPERATOR', 'POURING_OPERATOR',
  'KNOCKOUT_OPERATOR', 'FINISHING_OPERATOR'
)
and permission.code in ('WORKBENCH_VIEW', 'TASK_EXECUTE', 'MOBILE_REPORT', 'PROCESS_CARD_VIEW', 'NOTIFICATION_VIEW');

create table document_print_audit (
    id uuid primary key,
    document_type varchar(32) not null,
    entity_id uuid,
    actor_code varchar(64) not null,
    selected_fields varchar(1000) not null,
    sensitive_included boolean not null,
    output_type varchar(24) not null,
    occurred_at timestamp with time zone not null
);

create index ix_document_print_audit_actor on document_print_audit(actor_code, occurred_at desc);
create index ix_document_print_audit_entity on document_print_audit(entity_id, occurred_at desc);
