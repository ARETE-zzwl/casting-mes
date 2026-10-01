create table engineering_product (
    id uuid primary key,
    code varchar(64) not null unique,
    name varchar(160) not null,
    route_type varchar(32) not null,
    route_version varchar(32) not null,
    active boolean not null,
    created_at timestamp with time zone not null
);

create table customer_order_customer (
    id uuid primary key,
    code varchar(64) not null unique,
    name varchar(160) not null,
    contact_name varchar(100),
    contact_phone varchar(40),
    active boolean not null,
    created_at timestamp with time zone not null
);

create table customer_order_header (
    id uuid primary key,
    order_no varchar(64) not null unique,
    customer_id uuid not null,
    customer_code varchar(64) not null,
    customer_name varchar(160) not null,
    status varchar(24) not null,
    priority varchar(24) not null,
    requested_delivery_date date,
    remark varchar(500),
    version bigint not null,
    created_at timestamp with time zone not null,
    approved_at timestamp with time zone,
    released_at timestamp with time zone,
    constraint fk_order_customer foreign key (customer_id) references customer_order_customer(id)
);

create table customer_order_line (
    id uuid primary key,
    order_id uuid not null,
    line_no integer not null,
    product_id uuid not null,
    product_code varchar(64) not null,
    product_name varchar(160) not null,
    route_type varchar(32) not null,
    route_version varchar(32) not null,
    ordered_quantity numeric(19, 3) not null,
    unit varchar(16) not null,
    constraint fk_order_line_order foreign key (order_id) references customer_order_header(id),
    constraint ck_order_line_quantity check (ordered_quantity > 0),
    constraint uk_order_line_no unique (order_id, line_no)
);

create table planning_work_order (
    id uuid primary key,
    work_order_no varchar(64) not null unique,
    order_id uuid not null,
    order_line_id uuid not null unique,
    product_id uuid not null,
    product_code varchar(64) not null,
    product_name varchar(160) not null,
    route_type varchar(32) not null,
    route_version varchar(32) not null,
    planned_quantity numeric(19, 3) not null,
    status varchar(24) not null,
    created_at timestamp with time zone not null,
    constraint ck_work_order_quantity check (planned_quantity > 0)
);

create table planning_batch (
    id uuid primary key,
    batch_no varchar(64) not null unique,
    work_order_id uuid not null,
    planned_quantity numeric(19, 3) not null,
    status varchar(24) not null,
    created_at timestamp with time zone not null,
    constraint fk_batch_work_order foreign key (work_order_id) references planning_work_order(id),
    constraint ck_batch_quantity check (planned_quantity > 0)
);

create table planning_task (
    id uuid primary key,
    task_no varchar(64) not null unique,
    batch_id uuid not null,
    sequence_no integer not null,
    operation_code varchar(64) not null,
    operation_name varchar(120) not null,
    planned_quantity numeric(19, 3) not null,
    good_quantity numeric(19, 3) not null,
    scrap_quantity numeric(19, 3) not null,
    status varchar(24) not null,
    assigned_to varchar(64),
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    version bigint not null,
    created_at timestamp with time zone not null,
    constraint fk_task_batch foreign key (batch_id) references planning_batch(id),
    constraint ck_task_quantities check (
        planned_quantity > 0
        and good_quantity >= 0
        and scrap_quantity >= 0
        and good_quantity + scrap_quantity <= planned_quantity
    ),
    constraint uk_task_sequence unique (batch_id, sequence_no)
);

create table execution_report (
    id uuid primary key,
    operation_id uuid not null unique,
    task_id uuid not null,
    good_quantity numeric(19, 3) not null,
    scrap_quantity numeric(19, 3) not null,
    operator_code varchar(64) not null,
    task_good_total numeric(19, 3) not null,
    task_scrap_total numeric(19, 3) not null,
    task_status varchar(24) not null,
    occurred_at timestamp with time zone not null,
    constraint fk_report_task foreign key (task_id) references planning_task(id),
    constraint ck_report_quantities check (
        good_quantity >= 0
        and scrap_quantity >= 0
        and good_quantity + scrap_quantity > 0
    )
);

create index ix_order_status on customer_order_header(status, created_at);
create index ix_work_order_order on planning_work_order(order_id);
create index ix_task_status on planning_task(status, created_at);
create index ix_report_task on execution_report(task_id, occurred_at);
