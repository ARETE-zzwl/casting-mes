create table if not exists customer_product_mold_relation (
    id uuid primary key,
    customer_id uuid not null references customer_order_customer(id),
    product_id uuid not null references engineering_product(id),
    mold_asset_id uuid not null references resource_asset(id),
    created_by varchar(64) not null,
    created_at timestamp with time zone not null,
    last_used_at timestamp with time zone not null,
    unique(customer_id, product_id, mold_asset_id)
);

create index if not exists ix_customer_product_mold_lookup
    on customer_product_mold_relation(customer_id, product_id, last_used_at desc);
