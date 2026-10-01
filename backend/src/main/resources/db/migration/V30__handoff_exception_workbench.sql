alter table production_handoff_event add column resolution_status varchar(24) not null default 'OPEN';
alter table production_handoff_event add column owner_code varchar(64);
alter table production_handoff_event add column resolution_note varchar(1000);
alter table production_handoff_event add column resolved_by varchar(64);
alter table production_handoff_event add column resolved_at timestamp with time zone;

alter table production_cart_transfer add column resolution_status varchar(24) not null default 'OPEN';
alter table production_cart_transfer add column owner_code varchar(64);
alter table production_cart_transfer add column resolution_note varchar(1000);
alter table production_cart_transfer add column resolved_by varchar(64);
alter table production_cart_transfer add column resolved_at timestamp with time zone;

create index ix_handoff_resolution on production_handoff_event(resolution_status, occurred_at desc);
create index ix_cart_transfer_resolution on production_cart_transfer(resolution_status, received_at desc);
