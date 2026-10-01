alter table planning_task add column reporting_mode varchar(32) not null default 'SELF_REPORTED_QUANTITY';
alter table planning_task add column assigned_quantity numeric(19, 3);
alter table planning_task add column settlement_unit varchar(16) not null default 'PCS';
alter table planning_task add column counting_deferred boolean not null default false;
alter table planning_task add column tree_count numeric(19, 3);
alter table planning_task add column pieces_per_tree numeric(19, 3);

alter table planning_task add constraint ck_task_reporting_mode check (
	reporting_mode in ('SELF_REPORTED_QUANTITY', 'FIXED_QUANTITY', 'HANDOFF_TO_TREE', 'TREE_COUNT')
);
alter table planning_task add constraint ck_task_settlement_unit check (settlement_unit in ('PCS', 'TREE'));

alter table piecework_rate add column settlement_unit varchar(16) not null default 'PCS';
alter table piecework_entry add column settlement_unit varchar(16) not null default 'PCS';
alter table piecework_rate add constraint ck_piecework_rate_unit check (settlement_unit in ('PCS', 'TREE'));
alter table piecework_entry add constraint ck_piecework_entry_unit check (settlement_unit in ('PCS', 'TREE'));
