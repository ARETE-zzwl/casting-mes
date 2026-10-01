alter table piecework_rate add column if not exists product_code varchar(64);
alter table piecework_rate add column if not exists product_name varchar(160);

create index if not exists ix_piecework_rate_product_match
    on piecework_rate(route_type, operation_code, product_code, effective_from, active);
