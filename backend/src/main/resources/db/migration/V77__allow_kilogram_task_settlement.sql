alter table planning_task drop constraint ck_task_settlement_unit;

alter table planning_task add constraint ck_task_settlement_unit
    check (settlement_unit in ('PCS', 'TREE', 'KG'));
