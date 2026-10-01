alter table outsourcing_order add column planning_task_id uuid;
alter table outsourcing_order add constraint fk_outsourcing_planning_task
    foreign key (planning_task_id) references planning_task(id);
create unique index ux_outsourcing_planning_task on outsourcing_order(planning_task_id);

insert into production_supervisor_operation_scope (employee_code, route_type, operation_code) values
('PM01', 'SAND_OUTSOURCE', 'OUTSOURCE_DISPATCH'),
('PM01', 'SAND_OUTSOURCE', 'OUTSOURCE_PROGRESS'),
('PM01', 'SAND_OUTSOURCE', 'INCOMING_INSPECTION');
