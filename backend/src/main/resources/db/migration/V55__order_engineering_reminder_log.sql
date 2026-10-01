create table if not exists order_engineering_reminder (
    id uuid primary key,
    order_id uuid not null references customer_order_header(id),
    supervisor_code varchar(64) not null,
    reminded_at timestamp with time zone not null
);
