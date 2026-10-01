create table access_permission (
    code varchar(64) primary key,
    name varchar(120) not null,
    module_code varchar(64) not null
);

create table access_role (
    code varchar(64) primary key,
    name varchar(120) not null,
    description varchar(500) not null
);

create table access_role_permission (
    role_code varchar(64) not null,
    permission_code varchar(64) not null,
    primary key (role_code, permission_code),
    constraint fk_role_permission_role foreign key (role_code) references access_role(code),
    constraint fk_role_permission_permission foreign key (permission_code) references access_permission(code)
);

create table organization_member_role (
    employee_code varchar(64) not null,
    role_code varchar(64) not null,
    primary key (employee_code, role_code),
    constraint fk_member_role_member foreign key (employee_code) references organization_member(employee_code),
    constraint fk_member_role_role foreign key (role_code) references access_role(code)
);

create table operation_sop (
    id uuid primary key,
    operation_code varchar(64) not null,
    operation_name varchar(120) not null,
    version varchar(32) not null,
    safety_notice varchar(1000) not null,
    preparation_note varchar(1000) not null,
    status varchar(24) not null,
    updated_by varchar(64) not null,
    updated_at timestamp with time zone not null,
    constraint uk_operation_sop_version unique (operation_code, version)
);

create table operation_sop_step (
    id uuid primary key,
    sop_id uuid not null,
    step_no integer not null,
    title varchar(120) not null,
    instruction varchar(1000) not null,
    constraint fk_sop_step foreign key (sop_id) references operation_sop(id),
    constraint uk_sop_step unique (sop_id, step_no)
);

create table operation_sop_quality_point (
    id uuid primary key,
    sop_id uuid not null,
    point_no integer not null,
    content varchar(500) not null,
    constraint fk_sop_quality_point foreign key (sop_id) references operation_sop(id),
    constraint uk_sop_quality_point unique (sop_id, point_no)
);

create table finished_goods_lot (
    id uuid primary key,
    operation_id uuid not null unique,
    lot_no varchar(64) not null unique,
    order_id uuid not null,
    order_no varchar(64) not null,
    task_id uuid not null,
    product_code varchar(64) not null,
    product_name varchar(160) not null,
    quantity numeric(19, 3) not null,
    available_quantity numeric(19, 3) not null,
    warehouse_code varchar(64) not null,
    registered_by varchar(64) not null,
    registered_at timestamp with time zone not null,
    constraint fk_finished_lot_order foreign key (order_id) references customer_order_header(id),
    constraint fk_finished_lot_task foreign key (task_id) references planning_task(id),
    constraint ck_finished_lot_quantity check (
        quantity > 0 and available_quantity >= 0 and available_quantity <= quantity
    )
);

create table delivery_order (
    id uuid primary key,
    delivery_no varchar(64) not null unique,
    order_id uuid not null,
    order_no varchar(64) not null,
    customer_name varchar(160) not null,
    lot_id uuid not null,
    lot_no varchar(64) not null,
    product_code varchar(64) not null,
    product_name varchar(160) not null,
    quantity numeric(19, 3) not null,
    recipient_name varchar(100) not null,
    delivery_address varchar(500) not null,
    carrier varchar(160),
    tracking_no varchar(120),
    status varchar(24) not null,
    created_by varchar(64) not null,
    created_at timestamp with time zone not null,
    picked_at timestamp with time zone,
    shipped_at timestamp with time zone,
    delivered_at timestamp with time zone,
    updated_at timestamp with time zone not null,
    constraint fk_delivery_order foreign key (order_id) references customer_order_header(id),
    constraint fk_delivery_lot foreign key (lot_id) references finished_goods_lot(id),
    constraint ck_delivery_quantity check (quantity > 0)
);

create table delivery_event (
    id uuid primary key,
    delivery_id uuid not null,
    from_status varchar(24) not null,
    to_status varchar(24) not null,
    operator_code varchar(64) not null,
    note varchar(500),
    occurred_at timestamp with time zone not null,
    constraint fk_delivery_event foreign key (delivery_id) references delivery_order(id)
);

insert into access_permission (code, name, module_code) values
('WORKBENCH_VIEW', '员工工作台', 'execution'),
('DASHBOARD_VIEW', '经营看板', 'reporting'),
('ORDER_MANAGE', '客户订单管理', 'customerorder'),
('PLANNING_VIEW', '工单与计划查看', 'planning'),
('TASK_DISPATCH', '任务派工', 'planning'),
('TASK_EXECUTE', '开工与报工', 'execution'),
('QUALITY_MANAGE', '质量检验与处置', 'quality'),
('INVENTORY_MANAGE', '库存收发', 'inventory'),
('PIECEWORK_MANAGE', '计件核算', 'piecework'),
('OUTSOURCING_MANAGE', '外协管理', 'outsourcing'),
('WORKFLOW_MANAGE', '审批处理', 'workflow'),
('CONFIG_MANAGE', '配置发布', 'configuration'),
('PLATFORM_ADMIN', '平台运维', 'operations'),
('TRACE_VIEW', '订单追溯', 'traceability'),
('MASTERDATA_MANAGE', '基础资料', 'engineering'),
('FULFILLMENT_MANAGE', '成品交付', 'fulfillment'),
('SOP_MANAGE', 'SOP维护', 'engineering'),
('ACCESS_MANAGE', '角色权限配置', 'identity');

insert into access_role (code, name, description) values
('SYSTEM_ADMIN', '系统管理员', '系统配置、权限与全部业务模块'),
('PRODUCTION_MANAGER', '生产主管', '订单放行、派工、进度与追溯'),
('OPERATOR', '一线操作员', '只处理分配给自己的任务并按SOP报工'),
('QUALITY_INSPECTOR', '质量检验员', '检验、缺陷与不合格处置'),
('WAREHOUSE_CLERK', '仓库管理员', '库存收发、成品入库与备货'),
('DELIVERY_COORDINATOR', '交付专员', '发货、物流与客户签收'),
('PROCESS_ENGINEER', '工艺工程师', '产品、路线、SOP与配置版本'),
('FINANCE_REVIEWER', '财务复核员', '计件确认与经营数据查看');

insert into access_role_permission (role_code, permission_code)
select 'SYSTEM_ADMIN', code from access_permission;

insert into access_role_permission (role_code, permission_code) values
('PRODUCTION_MANAGER', 'DASHBOARD_VIEW'),
('PRODUCTION_MANAGER', 'ORDER_MANAGE'),
('PRODUCTION_MANAGER', 'PLANNING_VIEW'),
('PRODUCTION_MANAGER', 'TASK_DISPATCH'),
('PRODUCTION_MANAGER', 'TRACE_VIEW'),
('PRODUCTION_MANAGER', 'WORKBENCH_VIEW'),
('OPERATOR', 'WORKBENCH_VIEW'),
('OPERATOR', 'TASK_EXECUTE'),
('QUALITY_INSPECTOR', 'DASHBOARD_VIEW'),
('QUALITY_INSPECTOR', 'QUALITY_MANAGE'),
('QUALITY_INSPECTOR', 'TRACE_VIEW'),
('WAREHOUSE_CLERK', 'WORKBENCH_VIEW'),
('WAREHOUSE_CLERK', 'INVENTORY_MANAGE'),
('WAREHOUSE_CLERK', 'FULFILLMENT_MANAGE'),
('WAREHOUSE_CLERK', 'TRACE_VIEW'),
('DELIVERY_COORDINATOR', 'FULFILLMENT_MANAGE'),
('DELIVERY_COORDINATOR', 'TRACE_VIEW'),
('PROCESS_ENGINEER', 'MASTERDATA_MANAGE'),
('PROCESS_ENGINEER', 'CONFIG_MANAGE'),
('PROCESS_ENGINEER', 'SOP_MANAGE'),
('PROCESS_ENGINEER', 'TRACE_VIEW'),
('FINANCE_REVIEWER', 'DASHBOARD_VIEW'),
('FINANCE_REVIEWER', 'PIECEWORK_MANAGE');

insert into organization_unit (id, code, name, unit_type, parent_code, active, created_at) values
('51000000-0000-0000-0000-000000000001', 'DEMO_COMPANY', '示范精密铸造有限公司', 'COMPANY', null, true, current_timestamp),
('51000000-0000-0000-0000-000000000002', 'DEMO_FACTORY', '精铸一厂', 'FACTORY', 'DEMO_COMPANY', true, current_timestamp),
('51000000-0000-0000-0000-000000000003', 'WAX_WORKSHOP', '中温蜡车间', 'WORKSHOP', 'DEMO_FACTORY', true, current_timestamp),
('51000000-0000-0000-0000-000000000004', 'WAX_TEAM', '精铸甲班', 'TEAM', 'WAX_WORKSHOP', true, current_timestamp);

insert into organization_member (
    id, employee_code, name, unit_code, role_code, active, created_at
) values
('52000000-0000-0000-0000-000000000001', 'A001', '系统管理员', 'DEMO_COMPANY', 'SYSTEM_ADMIN', true, current_timestamp),
('52000000-0000-0000-0000-000000000002', 'S001', '生产主管', 'WAX_WORKSHOP', 'PRODUCTION_MANAGER', true, current_timestamp),
('52000000-0000-0000-0000-000000000003', 'W001', '射蜡操作员', 'WAX_TEAM', 'OPERATOR', true, current_timestamp),
('52000000-0000-0000-0000-000000000004', 'W002', '后处理操作员', 'WAX_TEAM', 'OPERATOR', true, current_timestamp),
('52000000-0000-0000-0000-000000000005', 'Q001', '质量检验员', 'WAX_WORKSHOP', 'QUALITY_INSPECTOR', true, current_timestamp),
('52000000-0000-0000-0000-000000000006', 'K001', '仓库配送员', 'DEMO_FACTORY', 'WAREHOUSE_CLERK', true, current_timestamp),
('52000000-0000-0000-0000-000000000007', 'E001', '工艺工程师', 'WAX_WORKSHOP', 'PROCESS_ENGINEER', true, current_timestamp),
('52000000-0000-0000-0000-000000000008', 'F001', '财务复核员', 'DEMO_COMPANY', 'FINANCE_REVIEWER', true, current_timestamp);

insert into organization_member_role (employee_code, role_code) values
('A001', 'SYSTEM_ADMIN'),
('A001', 'PRODUCTION_MANAGER'),
('S001', 'PRODUCTION_MANAGER'),
('S001', 'PROCESS_ENGINEER'),
('W001', 'OPERATOR'),
('W002', 'OPERATOR'),
('W002', 'WAREHOUSE_CLERK'),
('Q001', 'QUALITY_INSPECTOR'),
('K001', 'WAREHOUSE_CLERK'),
('K001', 'DELIVERY_COORDINATOR'),
('E001', 'PROCESS_ENGINEER'),
('E001', 'QUALITY_INSPECTOR'),
('F001', 'FINANCE_REVIEWER');

insert into operation_sop (
    id, operation_code, operation_name, version, safety_notice,
    preparation_note, status, updated_by, updated_at
) values
('53000000-0000-0000-0000-000000000001', 'WAX_INJECTION', '射蜡', 'V1', '佩戴防烫手套和护目镜，确认射蜡机急停有效。', '核对模具编号、蜡温和任务数量，模腔清洁无残留。', 'PUBLISHED', 'E001', current_timestamp),
('53000000-0000-0000-0000-000000000002', 'WAX_REPAIR', '修蜡', 'V1', '使用修蜡刀时刀口朝外，热工具放回专用支架。', '准备修蜡刀、酒精棉和合格样件，核对蜡件批次。', 'PUBLISHED', 'E001', current_timestamp),
('53000000-0000-0000-0000-000000000003', 'TREE_ASSEMBLY', '组树', 'V1', '焊接蜡件时佩戴防烫手套，保持作业区通风。', '核对浇口棒、蜡件数量和组树图，确认间距治具完好。', 'PUBLISHED', 'E001', current_timestamp),
('53000000-0000-0000-0000-000000000004', 'SHELL_BUILDING', '制壳', 'V1', '佩戴防尘口罩、护目镜和防滑鞋，搅拌设备运行时禁止伸手。', '确认浆料黏度、砂料批次、环境温湿度和层次要求。', 'PUBLISHED', 'E001', current_timestamp),
('53000000-0000-0000-0000-000000000005', 'DEWAX', '脱蜡', 'V1', '高温高压区域禁止逗留，确认釜门联锁和泄压完成。', '核对型壳批次、装釜数量和设备点检状态。', 'PUBLISHED', 'E001', current_timestamp),
('53000000-0000-0000-0000-000000000006', 'POURING', '浇筑', 'V1', '穿戴全套耐高温防护用品，浇注路径禁止无关人员进入。', '核对炉次、材质、型壳温度和浇注温度窗口。', 'PUBLISHED', 'E001', current_timestamp),
('53000000-0000-0000-0000-000000000007', 'CLEANING', '清理', 'V1', '佩戴面屏、耳塞和防割手套，抛丸设备门禁必须有效。', '核对批次、清理工装和外观检验样板。', 'PUBLISHED', 'E001', current_timestamp),
('53000000-0000-0000-0000-000000000008', 'FINAL_COUNT', '成品清点', 'V1', '搬运重件使用助力装置，码放不得超过库位限高。', '准备计数托盘、合格标识和周转箱，核对订单与批次。', 'PUBLISHED', 'E001', current_timestamp);

insert into operation_sop_step (id, sop_id, step_no, title, instruction) values
('54000000-0000-0000-0001-000000000001', '53000000-0000-0000-0000-000000000001', 1, '装模', '核对模具编号并锁紧，确认分型面无异物。'),
('54000000-0000-0000-0001-000000000002', '53000000-0000-0000-0000-000000000001', 2, '设定参数', '按工艺卡设定蜡温、压力和保压时间。'),
('54000000-0000-0000-0001-000000000003', '53000000-0000-0000-0000-000000000001', 3, '首件确认', '射出首件并对照样件检查充型和尺寸。'),
('54000000-0000-0000-0001-000000000004', '53000000-0000-0000-0000-000000000001', 4, '批量生产', '每十件抽查一次外观，完成后清点并流转。'),
('54000000-0000-0000-0002-000000000001', '53000000-0000-0000-0000-000000000002', 1, '领件核对', '核对蜡件批次、数量和待修缺陷。'),
('54000000-0000-0000-0002-000000000002', '53000000-0000-0000-0000-000000000002', 2, '去除飞边', '沿分型线轻修，禁止损伤基体。'),
('54000000-0000-0000-0002-000000000003', '53000000-0000-0000-0000-000000000002', 3, '表面修补', '按样件修补气泡、凹痕和接缝。'),
('54000000-0000-0000-0002-000000000004', '53000000-0000-0000-0000-000000000002', 4, '复检交接', '逐件目检并分开放置合格品和待判品。'),
('54000000-0000-0000-0003-000000000001', '53000000-0000-0000-0000-000000000003', 1, '核对组树图', '确认零件方向、数量和浇口位置。'),
('54000000-0000-0000-0003-000000000002', '53000000-0000-0000-0000-000000000003', 2, '焊接蜡件', '按顺序焊接，焊口平滑无虚焊。'),
('54000000-0000-0000-0003-000000000003', '53000000-0000-0000-0000-000000000003', 3, '检查间距', '使用治具检查层距和最小间隙。'),
('54000000-0000-0000-0003-000000000004', '53000000-0000-0000-0000-000000000003', 4, '挂签流转', '标记树号和数量，移交制壳区。'),
('54000000-0000-0000-0004-000000000001', '53000000-0000-0000-0000-000000000004', 1, '检测浆料', '记录黏度、温度和浆料批次。'),
('54000000-0000-0000-0004-000000000002', '53000000-0000-0000-0000-000000000004', 2, '沾浆撒砂', '均匀沾浆、控浆并按层次撒指定砂料。'),
('54000000-0000-0000-0004-000000000003', '53000000-0000-0000-0000-000000000004', 3, '干燥计时', '扫码开始干燥，达到工艺时长后方可转层。'),
('54000000-0000-0000-0004-000000000004', '53000000-0000-0000-0000-000000000004', 4, '层次确认', '检查漏涂、结块和开裂，记录完成层数。'),
('54000000-0000-0000-0005-000000000001', '53000000-0000-0000-0000-000000000005', 1, '装釜', '按装载图摆放型壳并复核数量。'),
('54000000-0000-0000-0005-000000000002', '53000000-0000-0000-0000-000000000005', 2, '锁门确认', '确认釜门锁紧、排放管路和联锁指示。'),
('54000000-0000-0000-0005-000000000003', '53000000-0000-0000-0000-000000000005', 3, '执行程序', '选择对应脱蜡程序并记录压力温度曲线。'),
('54000000-0000-0000-0005-000000000004', '53000000-0000-0000-0000-000000000005', 4, '泄压出釜', '压力归零后开门，检查型壳完整性。'),
('54000000-0000-0000-0006-000000000001', '53000000-0000-0000-0000-000000000006', 1, '炉前确认', '核对材质、炉次、光谱结果和型壳温度。'),
('54000000-0000-0000-0006-000000000002', '53000000-0000-0000-0000-000000000006', 2, '清理通道', '确认浇注通道、应急砂和人员站位。'),
('54000000-0000-0000-0006-000000000003', '53000000-0000-0000-0000-000000000006', 3, '连续浇注', '按规定顺序和速度完成浇注，禁止中途停顿。'),
('54000000-0000-0000-0006-000000000004', '53000000-0000-0000-0000-000000000006', 4, '炉次记录', '记录温度、时间、树号和异常情况。'),
('54000000-0000-0000-0007-000000000001', '53000000-0000-0000-0000-000000000007', 1, '去壳', '按工艺去除型壳，避免碰伤铸件。'),
('54000000-0000-0000-0007-000000000002', '53000000-0000-0000-0000-000000000007', 2, '切割', '按切割线分离铸件，保留规定余量。'),
('54000000-0000-0000-0007-000000000003', '53000000-0000-0000-0000-000000000007', 3, '抛丸打磨', '清理表面残砂和浇口，避免过磨。'),
('54000000-0000-0000-0007-000000000004', '53000000-0000-0000-0000-000000000007', 4, '外观分选', '按样板分选合格、返修和待判品。'),
('54000000-0000-0000-0008-000000000001', '53000000-0000-0000-0000-000000000008', 1, '核对批次', '核对订单、产品、批次和任务均已完成。'),
('54000000-0000-0000-0008-000000000002', '53000000-0000-0000-0000-000000000008', 2, '逐箱清点', '按周转箱逐件清点并记录箱号和数量。'),
('54000000-0000-0000-0008-000000000003', '53000000-0000-0000-0000-000000000008', 3, '标识隔离', '粘贴合格标签，待检或不合格品单独隔离。'),
('54000000-0000-0000-0008-000000000004', '53000000-0000-0000-0000-000000000008', 4, '移交终检', '核对总数并移交质量终检，双方确认。');

insert into operation_sop_quality_point (id, sop_id, point_no, content) values
('55000000-0000-0000-0001-000000000001', '53000000-0000-0000-0000-000000000001', 1, '蜡件充型完整，无缺肉和明显气泡'),
('55000000-0000-0000-0001-000000000002', '53000000-0000-0000-0000-000000000001', 2, '分型线错边和尺寸符合工艺卡'),
('55000000-0000-0000-0001-000000000003', '53000000-0000-0000-0000-000000000001', 3, '数量和模具寿命记录一致'),
('55000000-0000-0000-0002-000000000001', '53000000-0000-0000-0000-000000000002', 1, '飞边去除干净且不伤基体'),
('55000000-0000-0000-0002-000000000002', '53000000-0000-0000-0000-000000000002', 2, '表面无油污、裂纹和明显修痕'),
('55000000-0000-0000-0002-000000000003', '53000000-0000-0000-0000-000000000002', 3, '待判品已隔离并标识'),
('55000000-0000-0000-0003-000000000001', '53000000-0000-0000-0000-000000000003', 1, '零件方向和组树数量正确'),
('55000000-0000-0000-0003-000000000002', '53000000-0000-0000-0000-000000000003', 2, '焊口连续平滑无虚焊'),
('55000000-0000-0000-0003-000000000003', '53000000-0000-0000-0000-000000000003', 3, '间距满足涂挂和浇注要求'),
('55000000-0000-0000-0004-000000000001', '53000000-0000-0000-0000-000000000004', 1, '浆料黏度和环境参数在窗口内'),
('55000000-0000-0000-0004-000000000002', '53000000-0000-0000-0000-000000000004', 2, '涂层均匀无漏涂、结块和开裂'),
('55000000-0000-0000-0004-000000000003', '53000000-0000-0000-0000-000000000004', 3, '层数和干燥时长完整记录'),
('55000000-0000-0000-0005-000000000001', '53000000-0000-0000-0000-000000000005', 1, '脱蜡曲线符合程序要求'),
('55000000-0000-0000-0005-000000000002', '53000000-0000-0000-0000-000000000005', 2, '型壳无贯穿裂纹和明显破损'),
('55000000-0000-0000-0005-000000000003', '53000000-0000-0000-0000-000000000005', 3, '蜡料排放和回收记录完整'),
('55000000-0000-0000-0006-000000000001', '53000000-0000-0000-0000-000000000006', 1, '材质光谱和浇注温度合格'),
('55000000-0000-0000-0006-000000000002', '53000000-0000-0000-0000-000000000006', 2, '浇注连续，无夹渣和中断异常'),
('55000000-0000-0000-0006-000000000003', '53000000-0000-0000-0000-000000000006', 3, '炉次、树号和时间可追溯'),
('55000000-0000-0000-0007-000000000001', '53000000-0000-0000-0000-000000000007', 1, '表面残砂和浇口清理到位'),
('55000000-0000-0000-0007-000000000002', '53000000-0000-0000-0000-000000000007', 2, '无过磨、碰伤和混批'),
('55000000-0000-0000-0007-000000000003', '53000000-0000-0000-0000-000000000007', 3, '返修和待判品分类正确'),
('55000000-0000-0000-0008-000000000001', '53000000-0000-0000-0000-000000000008', 1, '清点总数与各箱数量之和一致'),
('55000000-0000-0000-0008-000000000002', '53000000-0000-0000-0000-000000000008', 2, '产品、订单、批次和标签一致'),
('55000000-0000-0000-0008-000000000003', '53000000-0000-0000-0000-000000000008', 3, '合格品与待判品物理隔离');

create index ix_member_role on organization_member_role(role_code, employee_code);
create index ix_sop_published on operation_sop(operation_code, status);
create index ix_finished_lot_order on finished_goods_lot(order_id, registered_at);
create index ix_delivery_status on delivery_order(status, updated_at);
create index ix_delivery_event_order on delivery_event(delivery_id, occurred_at);
