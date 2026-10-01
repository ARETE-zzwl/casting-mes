-- Keep a compact, production-ready set of baseline accounts. Additional demo accounts remain available.
delete from organization_member_role
where employee_code in ('A001', 'GM001', 'CM001', 'FD01', 'E001', 'PM01', 'S001', 'S002', 'S003', 'LW01', 'L001', 'FS001', 'M001', 'K001', 'G001', 'W001', 'W002', 'DSP-WAX', 'DSP-SHELL');

insert into organization_member_role (employee_code, role_code) values
('A001', 'SYSTEM_ADMIN'),
('GM001', 'GENERAL_MANAGER'),
('CM001', 'CUSTOMER_MANAGER'),
('FD01', 'FRONT_DESK_CLERK'),
('E001', 'PROCESS_ENGINEER'),
('PM01', 'PRODUCTION_MANAGER'),
('S001', 'MID_WAX_SUPERVISOR'),
('S002', 'MID_SHELL_SUPERVISOR'),
('S003', 'POST_PROCESS_SUPERVISOR'),
('LW01', 'LOW_WAX_SUPERVISOR'),
('L001', 'LOW_SHELL_SUPERVISOR'),
('FS001', 'FINISHING_SUPERVISOR'),
('M001', 'MOLD_KEEPER'),
('K001', 'RAW_MATERIAL_KEEPER'),
('G001', 'FINISHED_GOODS_KEEPER'),
('W001', 'WAX_INJECTION_OPERATOR'),
('W002', 'FINISHING_OPERATOR'),
('DSP-WAX', 'WORKSHOP_DISPLAY'),
('DSP-SHELL', 'WORKSHOP_DISPLAY');

update organization_member set role_code = case employee_code
  when 'A001' then 'SYSTEM_ADMIN'
  when 'GM001' then 'GENERAL_MANAGER'
  when 'CM001' then 'CUSTOMER_MANAGER'
  when 'FD01' then 'FRONT_DESK_CLERK'
  when 'E001' then 'PROCESS_ENGINEER'
  when 'PM01' then 'PRODUCTION_MANAGER'
  when 'S001' then 'MID_WAX_SUPERVISOR'
  when 'S002' then 'MID_SHELL_SUPERVISOR'
  when 'S003' then 'POST_PROCESS_SUPERVISOR'
  when 'LW01' then 'LOW_WAX_SUPERVISOR'
  when 'L001' then 'LOW_SHELL_SUPERVISOR'
  when 'FS001' then 'FINISHING_SUPERVISOR'
  when 'M001' then 'MOLD_KEEPER'
  when 'K001' then 'RAW_MATERIAL_KEEPER'
  when 'G001' then 'FINISHED_GOODS_KEEPER'
  when 'W001' then 'WAX_INJECTION_OPERATOR'
  when 'W002' then 'FINISHING_OPERATOR'
  when 'DSP-WAX' then 'WORKSHOP_DISPLAY'
  when 'DSP-SHELL' then 'WORKSHOP_DISPLAY'
end
where employee_code in ('A001', 'GM001', 'CM001', 'FD01', 'E001', 'PM01', 'S001', 'S002', 'S003', 'LW01', 'L001', 'FS001', 'M001', 'K001', 'G001', 'W001', 'W002', 'DSP-WAX', 'DSP-SHELL');

delete from production_supervisor_operation_scope where employee_code = 'PM01';
delete from production_supervisor_scope where employee_code = 'PM01';
insert into production_supervisor_scope (employee_code, route_type) values
('PM01', 'MID_TEMP_WAX'), ('PM01', 'LOW_TEMP_WAX'), ('PM01', 'SAND_OUTSOURCE');

insert into production_supervisor_operation_scope (employee_code, route_type, operation_code) values
('PM01', 'MID_TEMP_WAX', 'WAX_INJECTION'), ('PM01', 'MID_TEMP_WAX', 'WAX_REPAIR'),
('PM01', 'MID_TEMP_WAX', 'TREE_ASSEMBLY'), ('PM01', 'MID_TEMP_WAX', 'SHELL_BUILDING'),
('PM01', 'MID_TEMP_WAX', 'MANUAL_SHELL_BUILDING'), ('PM01', 'MID_TEMP_WAX', 'DEWAX'),
('PM01', 'MID_TEMP_WAX', 'POURING'), ('PM01', 'MID_TEMP_WAX', 'KNOCKOUT'),
('PM01', 'MID_TEMP_WAX', 'CUTTING'), ('PM01', 'MID_TEMP_WAX', 'SEMI_FINISHED_COUNT'),
('PM01', 'MID_TEMP_WAX', 'OPTIONAL_FINISHING'), ('PM01', 'MID_TEMP_WAX', 'FINAL_COUNT'),
('PM01', 'LOW_TEMP_WAX', 'WAX_INJECTION'), ('PM01', 'LOW_TEMP_WAX', 'WAX_REPAIR'),
('PM01', 'LOW_TEMP_WAX', 'TREE_ASSEMBLY'), ('PM01', 'LOW_TEMP_WAX', 'SHELL_BUILDING'),
('PM01', 'LOW_TEMP_WAX', 'MANUAL_SHELL_BUILDING'), ('PM01', 'LOW_TEMP_WAX', 'DEWAX'),
('PM01', 'LOW_TEMP_WAX', 'POURING'), ('PM01', 'LOW_TEMP_WAX', 'KNOCKOUT'),
('PM01', 'LOW_TEMP_WAX', 'CUTTING'), ('PM01', 'LOW_TEMP_WAX', 'SEMI_FINISHED_COUNT'),
('PM01', 'LOW_TEMP_WAX', 'OPTIONAL_FINISHING'), ('PM01', 'LOW_TEMP_WAX', 'FINAL_COUNT');
