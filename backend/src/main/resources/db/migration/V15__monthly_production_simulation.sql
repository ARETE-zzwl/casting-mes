create table simulation_run (
    id uuid primary key,
    simulation_code varchar(64) not null unique,
    month_start date not null,
    completed_order_id uuid,
    completed_order_no varchar(64),
    created_at timestamp with time zone not null
);

create table simulation_role_operation_log (
    id uuid primary key,
    simulation_run_id uuid not null,
    occurred_at timestamp with time zone not null,
    employee_code varchar(64) not null,
    role_code varchar(64) not null,
    module_code varchar(64) not null,
    function_code varchar(100) not null,
    business_reference varchar(128) not null,
    operation_summary varchar(500) not null,
    constraint fk_simulation_role_operation_run foreign key (simulation_run_id) references simulation_run(id)
);

create index ix_simulation_role_operation_run on simulation_role_operation_log(simulation_run_id, occurred_at);
