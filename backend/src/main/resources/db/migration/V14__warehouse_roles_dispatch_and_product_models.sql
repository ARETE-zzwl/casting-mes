alter table engineering_product add column model_image_url varchar(1000);
alter table customer_order_line add column model_image_url varchar(1000);

insert into access_permission (code, name, module_code) values
('MOLD_WAREHOUSE_MANAGE', '模具仓管理', 'inventory'),
('RAW_MATERIAL_WAREHOUSE_MANAGE', '原材料仓管理', 'inventory'),
('FINISHED_GOODS_WAREHOUSE_MANAGE', '成品仓管理', 'inventory'),
('LOGISTICS_MANAGE', '物流管理', 'fulfillment');

delete from access_role_permission
where (role_code = 'MOLD_KEEPER' and permission_code = 'INVENTORY_MANAGE')
   or (role_code = 'WAREHOUSE_CLERK' and permission_code in ('INVENTORY_MANAGE', 'FULFILLMENT_MANAGE', 'MOLD_ISSUE'))
   or (role_code in ('RAW_MATERIAL_KEEPER', 'FINISHED_GOODS_KEEPER') and permission_code = 'INVENTORY_MANAGE')
   or (role_code = 'FINISHED_GOODS_KEEPER' and permission_code = 'FULFILLMENT_MANAGE');

insert into access_role_permission (role_code, permission_code) values
('SYSTEM_ADMIN', 'MOLD_WAREHOUSE_MANAGE'),
('SYSTEM_ADMIN', 'RAW_MATERIAL_WAREHOUSE_MANAGE'),
('SYSTEM_ADMIN', 'FINISHED_GOODS_WAREHOUSE_MANAGE'),
('SYSTEM_ADMIN', 'LOGISTICS_MANAGE'),
('MOLD_KEEPER', 'MOLD_WAREHOUSE_MANAGE'),
('MOLD_KEEPER', 'WORKBENCH_VIEW'),
('MOLD_KEEPER', 'TASK_EXECUTE'),
('MOLD_KEEPER', 'MOBILE_REPORT'),
('MOLD_KEEPER', 'PROCESS_CARD_VIEW'),
('RAW_MATERIAL_KEEPER', 'RAW_MATERIAL_WAREHOUSE_MANAGE'),
('RAW_MATERIAL_KEEPER', 'NOTIFICATION_VIEW'),
('FINISHED_GOODS_KEEPER', 'FINISHED_GOODS_WAREHOUSE_MANAGE'),
('FINISHED_GOODS_KEEPER', 'FULFILLMENT_MANAGE'),
('FINISHED_GOODS_KEEPER', 'LOGISTICS_MANAGE'),
('FINISHED_GOODS_KEEPER', 'WORKBENCH_VIEW'),
('FINISHED_GOODS_KEEPER', 'TASK_EXECUTE'),
('FINISHED_GOODS_KEEPER', 'MOBILE_REPORT'),
('FINISHED_GOODS_KEEPER', 'PROCESS_CARD_VIEW'),
('FINISHED_GOODS_KEEPER', 'NOTIFICATION_VIEW');

delete from organization_member_role where employee_code in ('W002', 'K001') and role_code in ('WAREHOUSE_CLERK', 'DELIVERY_COORDINATOR');
insert into organization_member_role (employee_code, role_code) values
('W002', 'FINISHING_OPERATOR'),
('K001', 'RAW_MATERIAL_KEEPER');
update organization_member set role_code = 'RAW_MATERIAL_KEEPER' where employee_code = 'K001';

insert into organization_member (id, employee_code, name, unit_code, role_code, active, created_at) values
('52000000-0000-0000-0000-000000000013', 'G001', '成品仓管员', 'DEMO_FACTORY', 'FINISHED_GOODS_KEEPER', true, current_timestamp);
insert into organization_member_role (employee_code, role_code) values ('G001', 'FINISHED_GOODS_KEEPER');
