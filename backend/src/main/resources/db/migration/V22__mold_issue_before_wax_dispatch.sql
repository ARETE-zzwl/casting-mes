alter table mold_request add column order_id uuid;
alter table mold_request add column order_line_id uuid;
alter table mold_request add column work_order_id uuid;
alter table mold_request add column wax_task_id uuid;
alter table mold_request add column issued_to_worker_code varchar(64);

alter table mold_request add constraint fk_mold_request_order foreign key (order_id) references customer_order_header(id);
alter table mold_request add constraint fk_mold_request_order_line foreign key (order_line_id) references customer_order_line(id);
alter table mold_request add constraint fk_mold_request_work_order foreign key (work_order_id) references planning_work_order(id);
alter table mold_request add constraint fk_mold_request_wax_task foreign key (wax_task_id) references planning_task(id);
alter table mold_request add constraint uk_mold_request_order_line unique (order_line_id);

create index ix_mold_request_wax_task on mold_request(wax_task_id, status);
