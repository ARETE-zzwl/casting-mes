create table workflow_definition (
    id uuid primary key,
    workflow_type varchar(64) not null unique,
    name varchar(160) not null,
    description varchar(500),
    required_approvals integer not null,
    approver_roles varchar(1000),
    active boolean not null default true,
    created_by varchar(64) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint ck_workflow_definition_approvals check (required_approvals between 1 and 5)
);

insert into workflow_definition (id, workflow_type, name, description, required_approvals, approver_roles, active, created_by, created_at, updated_at) values
(random_uuid(), 'SCRAP_APPROVAL', '报废审批', '报废、批量报废与报废责任确认。', 1, 'PRODUCTION_MANAGER,QUALITY_ENGINEER', true, 'SYSTEM', current_timestamp, current_timestamp),
(random_uuid(), 'CONCESSION_APPROVAL', '让步审批', '不满足常规质量要求时的让步接收。', 2, 'QUALITY_ENGINEER,GENERAL_MANAGER', true, 'SYSTEM', current_timestamp, current_timestamp),
(random_uuid(), 'INVENTORY_ADJUSTMENT', '库存调整', '库存盘点差异与账实调整。', 1, 'RAW_MATERIAL_KEEPER,FINISHED_GOODS_KEEPER,SYSTEM_ADMIN', true, 'SYSTEM', current_timestamp, current_timestamp),
(random_uuid(), 'PIECEWORK_ADJUSTMENT', '计件调整', '计件、工时或重量结算调整。', 1, 'PRODUCTION_MANAGER,FINANCE_REVIEWER', true, 'SYSTEM', current_timestamp, current_timestamp),
(random_uuid(), 'URGENT_OVERRIDE', '急单覆盖', '调整未开工任务优先级的例外申请。', 1, 'PRODUCTION_MANAGER,GENERAL_MANAGER', true, 'SYSTEM', current_timestamp, current_timestamp);
