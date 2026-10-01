alter table engineering_product add column specification varchar(500);
alter table engineering_product add column material varchar(160);

alter table customer_order_header add column engineering_operation_parameters clob;
