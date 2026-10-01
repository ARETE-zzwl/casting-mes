-- Specialized supervisors must have both route and operation scopes. Earlier role data only populated operation scopes.
insert into production_supervisor_scope (employee_code, route_type) values
('S002', 'MID_TEMP_WAX'),
('L001', 'LOW_TEMP_WAX'),
('S003', 'MID_TEMP_WAX'),
('S003', 'LOW_TEMP_WAX')
on conflict do nothing;

insert into production_supervisor_operation_scope (employee_code, route_type, operation_code) values
('S003', 'MID_TEMP_WAX', 'FINAL_COUNT'),
('S003', 'LOW_TEMP_WAX', 'FINAL_COUNT')
on conflict do nothing;
