create table if not exists product_mold_relation (
    id uuid primary key,
    product_id uuid not null references engineering_product(id),
    mold_asset_id uuid not null references resource_asset(id),
    note varchar(500),
    created_by varchar(64) not null,
    created_at timestamp with time zone not null,
    unique(product_id, mold_asset_id)
);
