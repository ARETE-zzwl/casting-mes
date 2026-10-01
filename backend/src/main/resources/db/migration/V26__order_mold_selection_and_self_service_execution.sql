create table order_mold_selection (
    id uuid primary key,
    order_id uuid not null,
    order_line_id uuid not null unique,
    mold_asset_id uuid not null,
    mold_ownership_type varchar(32) not null,
    mold_owner_name varchar(160),
    selected_by varchar(64) not null,
    selected_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_order_mold_selection_order foreign key (order_id) references customer_order_header(id),
    constraint fk_order_mold_selection_line foreign key (order_line_id) references customer_order_line(id),
    constraint fk_order_mold_selection_asset foreign key (mold_asset_id) references resource_asset(id)
);

create index ix_order_mold_selection_order on order_mold_selection(order_id);

insert into access_permission (code, name, module_code) values
('ORDER_MOLD_SELECT', '订单模具选定', 'resource'),
('TASK_SELF_CLAIM', '自主认领开工', 'planning'),
('SHELL_SCAN_REPORT', '制壳扫码进度报工', 'labor');

insert into access_role_permission (role_code, permission_code) values
('SYSTEM_ADMIN', 'ORDER_MOLD_SELECT'),
('SYSTEM_ADMIN', 'TASK_SELF_CLAIM'),
('SYSTEM_ADMIN', 'SHELL_SCAN_REPORT'),
('GENERAL_MANAGER', 'ORDER_MOLD_SELECT'),
('PROCESS_ENGINEER', 'ORDER_MOLD_SELECT'),
('PRODUCTION_MANAGER', 'ORDER_MOLD_SELECT'),
('WAX_INJECTION_OPERATOR', 'TASK_SELF_CLAIM'),
('WAX_REPAIR_OPERATOR', 'TASK_SELF_CLAIM'),
('TREE_ASSEMBLY_OPERATOR', 'TASK_SELF_CLAIM'),
('SHELL_BUILDING_OPERATOR', 'TASK_SELF_CLAIM'),
('DEWAX_OPERATOR', 'TASK_SELF_CLAIM'),
('POURING_OPERATOR', 'TASK_SELF_CLAIM'),
('KNOCKOUT_OPERATOR', 'TASK_SELF_CLAIM'),
('FINISHING_OPERATOR', 'TASK_SELF_CLAIM'),
('OPERATOR', 'TASK_SELF_CLAIM'),
('SHELL_BUILDING_OPERATOR', 'SHELL_SCAN_REPORT'),
('OPERATOR', 'SHELL_SCAN_REPORT')
on conflict do nothing;
