insert into access_role (code, name, description) values
('SALES_REP', '销售人员', '维护客户归属，协助订单录入、交期沟通与客户历史查询')
on conflict do nothing;

insert into access_role_permission (role_code, permission_code)
select 'SALES_REP', code from access_permission
where code in ('ORDER_MANAGE', 'TRACE_VIEW', 'WORKBENCH_VIEW', 'NOTIFICATION_VIEW')
on conflict do nothing;
