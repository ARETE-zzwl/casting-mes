alter table order_mold_selection alter column mold_asset_id drop not null;
alter table order_mold_selection alter column mold_ownership_type drop not null;
alter table order_mold_selection add column selection_status varchar(40) not null default 'SELECTED';
alter table order_mold_selection add column pending_reason varchar(500);

update order_mold_selection set selection_status = 'SELECTED' where selection_status is null;

create index ix_order_mold_selection_status on order_mold_selection(selection_status, updated_at);
