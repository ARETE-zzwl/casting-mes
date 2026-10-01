create index if not exists ix_resource_asset_code_updated
    on resource_asset(asset_code, updated_at);

create index if not exists ix_finished_goods_lot_code_registered
    on finished_goods_lot(lot_no, registered_at);

create index if not exists ix_finished_goods_lot_product_registered
    on finished_goods_lot(product_code, registered_at);

create index if not exists ix_delivery_order_code_created
    on delivery_order(delivery_no, created_at);

create index if not exists ix_delivery_order_order_created
    on delivery_order(order_no, created_at);
