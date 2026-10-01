create index ix_inventory_balance_warehouse_updated
    on inventory_balance(warehouse_code, updated_at desc, item_code);

create index ix_inventory_movement_occurred
    on inventory_movement(occurred_at desc, id);

create index ix_inventory_movement_type_operator
    on inventory_movement(movement_type, operator_code, occurred_at desc);

create index ix_inventory_movement_reference
    on inventory_movement(reference_no, occurred_at desc);
