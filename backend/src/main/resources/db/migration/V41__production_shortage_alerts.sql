create table production_shortage_alert (
    id uuid primary key,
    work_order_id uuid not null unique,
    order_id uuid not null,
    order_line_id uuid not null,
    route_type varchar(32) not null,
    demand_quantity numeric(19, 3) not null,
    projected_quantity numeric(19, 3) not null,
    shortage_quantity numeric(19, 3) not null,
    status varchar(24) not null,
    detected_at timestamp with time zone not null,
    last_evaluated_at timestamp with time zone not null,
    resolved_at timestamp with time zone,
    constraint ck_production_shortage_quantities check (demand_quantity > 0 and projected_quantity >= 0 and shortage_quantity >= 0),
    constraint ck_production_shortage_status check (status in ('OPEN', 'RESOLVED')),
    constraint fk_production_shortage_work_order foreign key (work_order_id) references planning_work_order(id),
    constraint fk_production_shortage_order foreign key (order_id) references customer_order_header(id),
    constraint fk_production_shortage_order_line foreign key (order_line_id) references customer_order_line(id)
);

create index ix_production_shortage_status on production_shortage_alert(status, last_evaluated_at desc);
create index ix_production_shortage_route on production_shortage_alert(route_type, status);
