alter table planning_task drop constraint ck_task_reporting_mode;
alter table planning_task add constraint ck_task_reporting_mode check (
    reporting_mode in ('SELF_REPORTED_QUANTITY', 'FIXED_QUANTITY', 'HANDOFF_TO_TREE', 'HANDOFF_TO_NEXT', 'TREE_COUNT')
);
alter table planning_task add column compensation_mode varchar(16) not null default 'PIECE_PCS';
alter table planning_task add column completed_weight_kg numeric(19, 3);
alter table planning_task add constraint ck_task_compensation_mode check (
    compensation_mode in ('PIECE_PCS', 'PIECE_TREE', 'HOURLY', 'PIECE_KG', 'HANDOFF_ONLY')
);

alter table piecework_rate drop constraint ck_piecework_rate_unit;
alter table piecework_entry drop constraint ck_piecework_entry_unit;
alter table piecework_rate add constraint ck_piecework_rate_unit check (settlement_unit in ('PCS', 'TREE', 'KG'));
alter table piecework_entry add constraint ck_piecework_entry_unit check (settlement_unit in ('PCS', 'TREE', 'KG'));

create table production_handoff_event (
    id uuid primary key,
    task_id uuid not null,
    handoff_no varchar(64) not null unique,
    expected_quantity numeric(19, 3) not null,
    received_quantity numeric(19, 3),
    status varchar(24) not null,
    exception_reason varchar(500),
    handed_over_by varchar(64) not null,
    received_by varchar(64),
    occurred_at timestamp with time zone not null,
    reviewed_by varchar(64),
    reviewed_at timestamp with time zone,
    constraint fk_handoff_task foreign key (task_id) references planning_task(id),
    constraint ck_handoff_quantities check (expected_quantity > 0 and (received_quantity is null or received_quantity >= 0))
);
create index ix_handoff_task on production_handoff_event(task_id, occurred_at desc);
create index ix_handoff_status on production_handoff_event(status, occurred_at desc);

create table labor_time_entry (
    id uuid primary key,
    entry_no varchar(64) not null unique,
    task_id uuid not null,
    worker_code varchar(64) not null,
    hours numeric(12, 3) not null,
    recorded_by varchar(64) not null,
    source varchar(24) not null,
    status varchar(24) not null,
    occurred_at timestamp with time zone not null,
    confirmed_by varchar(64),
    confirmed_at timestamp with time zone,
    constraint fk_labor_time_task foreign key (task_id) references planning_task(id),
    constraint ck_labor_time_hours check (hours > 0)
);
create index ix_labor_time_task on labor_time_entry(task_id, occurred_at desc);
create index ix_labor_time_worker on labor_time_entry(worker_code, occurred_at desc);

create table manual_report_sheet (
    id uuid primary key,
    sheet_no varchar(64) not null unique,
    task_id uuid not null,
    worker_code varchar(64) not null,
    report_kind varchar(24) not null,
    good_quantity numeric(19, 3),
    scrap_quantity numeric(19, 3),
    hours numeric(12, 3),
    note varchar(500),
    entered_by varchar(64) not null,
    entered_at timestamp with time zone not null,
    status varchar(24) not null,
    reviewed_by varchar(64),
    reviewed_at timestamp with time zone,
    constraint fk_manual_sheet_task foreign key (task_id) references planning_task(id),
    constraint ck_manual_sheet_kind check (report_kind in ('QUANTITY', 'HOURS')),
    constraint ck_manual_sheet_values check (
        (report_kind = 'QUANTITY' and good_quantity >= 0 and scrap_quantity >= 0 and hours is null)
        or (report_kind = 'HOURS' and hours > 0 and good_quantity is null and scrap_quantity is null)
    )
);
create index ix_manual_sheet_status on manual_report_sheet(status, entered_at desc);

create table shell_building_record (
    id uuid primary key,
    task_id uuid not null,
    operation_id uuid not null unique,
    method varchar(16) not null,
    layer_count integer not null,
    drying_minutes integer not null,
    quantity numeric(19, 3) not null,
    operator_code varchar(64) not null,
    note varchar(500),
    occurred_at timestamp with time zone not null,
    constraint fk_shell_record_task foreign key (task_id) references planning_task(id),
    constraint ck_shell_record_method check (method in ('MANUAL', 'AUTOMATED', 'TRANSFERRED')),
    constraint ck_shell_record_values check (layer_count >= 0 and drying_minutes >= 0 and quantity > 0)
);
create index ix_shell_record_task on shell_building_record(task_id, occurred_at desc);

insert into access_permission (code, name, module_code) values
('SUPERVISOR_REPORT', '主管代报与交接异常登记', 'execution'),
('MANUAL_REPORT_REVIEW', '纸质报工录入与审核', 'execution'),
('LABOR_TIME_MANAGE', '计时工资登记与复核', 'labor');

insert into access_role_permission (role_code, permission_code) values
('WORKSHOP_SUPERVISOR', 'SUPERVISOR_REPORT'),
('WORKSHOP_SUPERVISOR', 'MANUAL_REPORT_REVIEW'),
('WORKSHOP_SUPERVISOR', 'LABOR_TIME_MANAGE'),
('PRODUCTION_MANAGER', 'SUPERVISOR_REPORT'),
('PRODUCTION_MANAGER', 'MANUAL_REPORT_REVIEW'),
('FINANCE_REVIEWER', 'LABOR_TIME_MANAGE');
