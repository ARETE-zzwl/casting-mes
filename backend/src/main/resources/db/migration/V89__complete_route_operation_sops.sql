-- Route codes introduced after the initial SOP seed still need a worker-readable baseline.
insert into operation_sop (id, operation_code, operation_name, version, safety_notice, preparation_note, status, updated_by, updated_at)
select '53000000-0000-0000-0000-000000000089', 'MANUAL_SHELL_BUILDING', '人工制壳', 'V1', '佩戴防尘口罩、护目镜和防滑鞋，搅拌设备运行时禁止伸手。', '核对浆料、砂料、环境条件和本次壳层要求。', 'PUBLISHED', 'E001', current_timestamp
where not exists (select 1 from operation_sop where operation_code = 'MANUAL_SHELL_BUILDING' and version = 'V1');

insert into operation_sop (id, operation_code, operation_name, version, safety_notice, preparation_note, status, updated_by, updated_at)
select '53000000-0000-0000-0000-000000000090', 'KNOCKOUT', '脱壳', 'V1', '佩戴面屏、防尘口罩和防割手套，确认设备防护与除尘正常。', '核对批次、型壳状态和脱壳工装，准备隔离容器。', 'PUBLISHED', 'E001', current_timestamp
where not exists (select 1 from operation_sop where operation_code = 'KNOCKOUT' and version = 'V1');

insert into operation_sop (id, operation_code, operation_name, version, safety_notice, preparation_note, status, updated_by, updated_at)
select '53000000-0000-0000-0000-000000000091', 'CUTTING', '分割', 'V1', '佩戴面屏、耳塞和防割手套，严禁拆除砂轮防护罩。', '核对批次、分割线和工装，确认合格品与待判品周转箱。', 'PUBLISHED', 'E001', current_timestamp
where not exists (select 1 from operation_sop where operation_code = 'CUTTING' and version = 'V1');

insert into operation_sop (id, operation_code, operation_name, version, safety_notice, preparation_note, status, updated_by, updated_at)
select '53000000-0000-0000-0000-000000000092', 'SEMI_FINISHED_COUNT', '半成品清点', 'V1', '搬运重件使用适当工装，码放不得超过周转箱限高。', '准备批次标签、计数托盘和异常隔离标识。', 'PUBLISHED', 'E001', current_timestamp
where not exists (select 1 from operation_sop where operation_code = 'SEMI_FINISHED_COUNT' and version = 'V1');

insert into operation_sop (id, operation_code, operation_name, version, safety_notice, preparation_note, status, updated_by, updated_at)
select '53000000-0000-0000-0000-000000000093', 'OPTIONAL_FINISHING', '后处理', 'V1', '按工艺要求佩戴防护用品，使用设备前确认防护与除尘状态。', '核对后处理路线、批次、重量或工时计量要求。', 'PUBLISHED', 'E001', current_timestamp
where not exists (select 1 from operation_sop where operation_code = 'OPTIONAL_FINISHING' and version = 'V1');

insert into operation_sop_step (id, sop_id, step_no, title, instruction)
select '54000000-0000-0000-0089-000000000001', '53000000-0000-0000-0000-000000000089', 1, '逐层制壳', '扫码核对工单后按工艺卡完成当前壳层，并记录层号和干燥时间。'
where exists (select 1 from operation_sop where id = '53000000-0000-0000-0000-000000000089')
  and not exists (select 1 from operation_sop_step where sop_id = '53000000-0000-0000-0000-000000000089' and step_no = 1);
insert into operation_sop_step (id, sop_id, step_no, title, instruction)
select '54000000-0000-0000-0090-000000000001', '53000000-0000-0000-0000-000000000090', 1, '脱除型壳', '按设备或工装要求完成脱壳，避免损伤铸件关键部位。'
where exists (select 1 from operation_sop where id = '53000000-0000-0000-0000-000000000090')
  and not exists (select 1 from operation_sop_step where sop_id = '53000000-0000-0000-0000-000000000090' and step_no = 1);
insert into operation_sop_step (id, sop_id, step_no, title, instruction)
select '54000000-0000-0000-0091-000000000001', '53000000-0000-0000-0000-000000000091', 1, '按线分割', '按工艺卡和分割线去除浇冒口，避免伤及产品本体。'
where exists (select 1 from operation_sop where id = '53000000-0000-0000-0000-000000000091')
  and not exists (select 1 from operation_sop_step where sop_id = '53000000-0000-0000-0000-000000000091' and step_no = 1);
insert into operation_sop_step (id, sop_id, step_no, title, instruction)
select '54000000-0000-0000-0092-000000000001', '53000000-0000-0000-0000-000000000092', 1, '核对批次与数量', '按批次清点半成品，数量差异立即标记并上报，不阻断流转。'
where exists (select 1 from operation_sop where id = '53000000-0000-0000-0000-000000000092')
  and not exists (select 1 from operation_sop_step where sop_id = '53000000-0000-0000-0000-000000000092' and step_no = 1);
insert into operation_sop_step (id, sop_id, step_no, title, instruction)
select '54000000-0000-0000-0093-000000000001', '53000000-0000-0000-0000-000000000093', 1, '执行已选后处理', '只执行工艺卡或后处理主管选定的工序，记录重量、数量或工时。'
where exists (select 1 from operation_sop where id = '53000000-0000-0000-0000-000000000093')
  and not exists (select 1 from operation_sop_step where sop_id = '53000000-0000-0000-0000-000000000093' and step_no = 1);

insert into operation_sop_quality_point (id, sop_id, point_no, content)
select '55000000-0000-0000-0090-000000000001', '53000000-0000-0000-0000-000000000090', 1, '铸件本体无明显碰伤、裂纹和残留型壳。'
where exists (select 1 from operation_sop where id = '53000000-0000-0000-0000-000000000090')
  and not exists (select 1 from operation_sop_quality_point where sop_id = '53000000-0000-0000-0000-000000000090' and point_no = 1);
insert into operation_sop_quality_point (id, sop_id, point_no, content)
select '55000000-0000-0000-0091-000000000001', '53000000-0000-0000-0000-000000000091', 1, '分割面符合工艺要求，产品本体不得有切伤。'
where exists (select 1 from operation_sop where id = '53000000-0000-0000-0000-000000000091')
  and not exists (select 1 from operation_sop_quality_point where sop_id = '53000000-0000-0000-0000-000000000091' and point_no = 1);
insert into operation_sop_quality_point (id, sop_id, point_no, content)
select '55000000-0000-0000-0092-000000000001', '53000000-0000-0000-0000-000000000092', 1, '数量、批次和实物标识一致；差异已留痕。'
where exists (select 1 from operation_sop where id = '53000000-0000-0000-0000-000000000092')
  and not exists (select 1 from operation_sop_quality_point where sop_id = '53000000-0000-0000-0000-000000000092' and point_no = 1);
insert into operation_sop_quality_point (id, sop_id, point_no, content)
select '55000000-0000-0000-0093-000000000001', '53000000-0000-0000-0000-000000000093', 1, '后处理结果、计量口径和批次标识可追溯。'
where exists (select 1 from operation_sop where id = '53000000-0000-0000-0000-000000000093')
  and not exists (select 1 from operation_sop_quality_point where sop_id = '53000000-0000-0000-0000-000000000093' and point_no = 1);
