-- 成品清点由成品仓管执行：仅开放三条路线末道，不授予前段生产工序。
insert into production_operator_scope (employee_code, route_type) values
('G001', 'MID_TEMP_WAX'),
('G001', 'LOW_TEMP_WAX'),
('G001', 'SAND_OUTSOURCE')
on conflict do nothing;
