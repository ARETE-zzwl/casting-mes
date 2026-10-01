alter table customer_order_line add column if not exists process_card_version varchar(64);
alter table customer_order_line add column if not exists engineering_parameters varchar(2000);
alter table customer_order_line add column if not exists engineering_operation_parameters clob;
alter table customer_order_line add column if not exists engineering_confirmed_by varchar(64);
alter table customer_order_line add column if not exists engineering_confirmed_at timestamp with time zone;
alter table customer_order_line add column if not exists default_process_card_version varchar(64);
alter table customer_order_line add column if not exists default_process_released_by varchar(64);
alter table customer_order_line add column if not exists default_process_released_at timestamp with time zone;

-- Preserve legacy single-card orders as one card per product line before new confirmations begin.
update customer_order_line line
set process_card_version = header.process_card_version,
    engineering_parameters = header.engineering_parameters,
    engineering_operation_parameters = header.engineering_operation_parameters,
    engineering_confirmed_by = header.engineering_confirmed_by,
    engineering_confirmed_at = header.engineering_confirmed_at,
    default_process_card_version = header.default_process_card_version,
    default_process_released_by = header.default_process_released_by,
    default_process_released_at = header.default_process_released_at
from customer_order_header header
where line.order_id = header.id
  and (header.engineering_confirmed_at is not null or header.default_process_released_at is not null);

create index if not exists ix_order_line_process_card on customer_order_line(order_id, engineering_confirmed_at);
