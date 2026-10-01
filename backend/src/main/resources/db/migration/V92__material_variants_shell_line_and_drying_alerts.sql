alter table engineering_product add column material_variant_of uuid;
alter table engineering_product add constraint fk_product_material_variant_of
    foreign key (material_variant_of) references engineering_product(id);
create index ix_product_material_variant on engineering_product(material_variant_of, material);

alter table planning_task add column shell_line_mode varchar(16);
alter table planning_task add constraint ck_task_shell_line_mode
    check (shell_line_mode is null or shell_line_mode in ('AUTOMATED', 'MANUAL'));
update planning_task set shell_line_mode = 'MANUAL'
where operation_code = 'MANUAL_SHELL_BUILDING' and shell_line_mode is null;

create table manual_shell_drying_alert (
    id uuid primary key,
    task_id uuid not null unique,
    latest_shell_record_id uuid not null,
    status varchar(16) not null,
    first_detected_at timestamp with time zone not null,
    last_evaluated_at timestamp with time zone not null,
    resolved_at timestamp with time zone,
    constraint fk_manual_shell_alert_task foreign key (task_id) references planning_task(id),
    constraint fk_manual_shell_alert_record foreign key (latest_shell_record_id) references shell_building_record(id),
    constraint ck_manual_shell_alert_status check (status in ('OPEN', 'RESOLVED'))
);
create index ix_manual_shell_alert_status on manual_shell_drying_alert(status, last_evaluated_at desc);
