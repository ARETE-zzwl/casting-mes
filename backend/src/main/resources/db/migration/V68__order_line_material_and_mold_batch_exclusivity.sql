alter table customer_order_line add column if not exists product_material varchar(160);
alter table planning_work_order add column if not exists product_material varchar(160);

update customer_order_line line
set product_material = (
    select product.material from engineering_product product where product.id = line.product_id
)
where line.product_material is null;

update planning_work_order work_order
set product_material = (
    select line.product_material from customer_order_line line where line.id = work_order.order_line_id
)
where work_order.product_material is null;

create index if not exists ix_mold_request_active_asset on mold_request(mold_asset_id, status, wax_task_id);
