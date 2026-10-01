create table quality_inspection (
    id uuid primary key,
    operation_id uuid not null unique,
    inspection_no varchar(64) not null unique,
    task_id uuid not null,
    inspected_quantity numeric(19, 3) not null,
    accepted_quantity numeric(19, 3) not null,
    rejected_quantity numeric(19, 3) not null,
    result varchar(24) not null,
    defect_code varchar(64),
    inspector_code varchar(64) not null,
    remark varchar(500),
    occurred_at timestamp with time zone not null,
    constraint fk_quality_task foreign key (task_id) references planning_task(id),
    constraint ck_quality_quantities check (
        inspected_quantity > 0
        and accepted_quantity >= 0
        and rejected_quantity >= 0
        and accepted_quantity + rejected_quantity = inspected_quantity
    )
);

create table quality_disposition (
    id uuid primary key,
    inspection_id uuid not null,
    decision varchar(24) not null,
    quantity numeric(19, 3) not null,
    reason varchar(500) not null,
    decided_by varchar(64) not null,
    occurred_at timestamp with time zone not null,
    constraint fk_disposition_inspection foreign key (inspection_id) references quality_inspection(id),
    constraint ck_disposition_quantity check (quantity > 0)
);

create table inventory_balance (
    id uuid primary key,
    warehouse_code varchar(64) not null,
    item_code varchar(64) not null,
    item_name varchar(160) not null,
    unit varchar(16) not null,
    quantity numeric(19, 3) not null,
    version bigint not null,
    updated_at timestamp with time zone not null,
    constraint uk_inventory_balance unique (warehouse_code, item_code, unit),
    constraint ck_inventory_balance_quantity check (quantity >= 0)
);

create table inventory_movement (
    id uuid primary key,
    operation_id uuid not null unique,
    movement_no varchar(64) not null unique,
    balance_id uuid not null,
    movement_type varchar(32) not null,
    quantity numeric(19, 3) not null,
    balance_after numeric(19, 3) not null,
    reference_type varchar(64),
    reference_no varchar(64),
    operator_code varchar(64) not null,
    remark varchar(500),
    occurred_at timestamp with time zone not null,
    constraint fk_movement_balance foreign key (balance_id) references inventory_balance(id),
    constraint ck_movement_quantity check (quantity > 0),
    constraint ck_movement_balance check (balance_after >= 0)
);

create table piecework_rate (
    id uuid primary key,
    operation_code varchar(64) not null,
    operation_name varchar(120) not null,
    version varchar(32) not null,
    unit_rate numeric(19, 4) not null,
    effective_from date not null,
    active boolean not null,
    created_at timestamp with time zone not null,
    constraint uk_piecework_rate unique (operation_code, version),
    constraint ck_piecework_rate check (unit_rate >= 0)
);

create table piecework_entry (
    id uuid primary key,
    operation_id uuid not null unique,
    entry_no varchar(64) not null unique,
    task_id uuid not null,
    task_no varchar(64) not null,
    operation_code varchar(64) not null,
    operation_name varchar(120) not null,
    worker_code varchar(64) not null,
    quantity numeric(19, 3) not null,
    rate_version varchar(32) not null,
    unit_rate numeric(19, 4) not null,
    amount numeric(19, 4) not null,
    status varchar(24) not null,
    confirmed_by varchar(64),
    occurred_at timestamp with time zone not null,
    confirmed_at timestamp with time zone,
    constraint fk_piecework_task foreign key (task_id) references planning_task(id),
    constraint ck_piecework_quantity check (quantity > 0),
    constraint ck_piecework_amount check (amount >= 0)
);

create table outsourcing_supplier (
    id uuid primary key,
    code varchar(64) not null unique,
    name varchar(160) not null,
    contact_name varchar(100),
    contact_phone varchar(40),
    active boolean not null,
    created_at timestamp with time zone not null
);

create table outsourcing_order (
    id uuid primary key,
    order_no varchar(64) not null unique,
    supplier_id uuid not null,
    supplier_code varchar(64) not null,
    supplier_name varchar(160) not null,
    item_code varchar(64) not null,
    item_name varchar(160) not null,
    quantity numeric(19, 3) not null,
    received_quantity numeric(19, 3) not null,
    unit varchar(16) not null,
    due_date date,
    status varchar(24) not null,
    remark varchar(500),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_outsourcing_supplier foreign key (supplier_id) references outsourcing_supplier(id),
    constraint ck_outsourcing_quantity check (
        quantity > 0
        and received_quantity >= 0
        and received_quantity <= quantity
    )
);

create table outsourcing_milestone (
    id uuid primary key,
    order_id uuid not null,
    from_status varchar(24) not null,
    to_status varchar(24) not null,
    received_quantity numeric(19, 3) not null,
    note varchar(500),
    operator_code varchar(64) not null,
    occurred_at timestamp with time zone not null,
    constraint fk_outsourcing_milestone foreign key (order_id) references outsourcing_order(id)
);

create index ix_quality_task on quality_inspection(task_id, occurred_at);
create index ix_disposition_inspection on quality_disposition(inspection_id, occurred_at);
create index ix_inventory_item on inventory_balance(item_code, warehouse_code);
create index ix_movement_balance on inventory_movement(balance_id, occurred_at);
create index ix_piecework_worker on piecework_entry(worker_code, occurred_at);
create index ix_outsourcing_status on outsourcing_order(status, due_date);
create index ix_outsourcing_milestone on outsourcing_milestone(order_id, occurred_at);
