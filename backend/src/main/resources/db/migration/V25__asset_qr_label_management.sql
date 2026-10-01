create table asset_qr_label (
    id uuid primary key,
    label_no varchar(64) not null unique,
    qr_token varchar(96) not null unique,
    intended_asset_type varchar(24) not null,
    asset_id uuid unique,
    status varchar(24) not null,
    print_count integer not null default 0,
    last_printed_by varchar(64),
    last_printed_at timestamp with time zone,
    bound_by varchar(64),
    bound_at timestamp with time zone,
    created_by varchar(64) not null,
    created_at timestamp with time zone not null,
    constraint fk_asset_qr_label_asset foreign key (asset_id) references resource_asset(id)
);

create index ix_asset_qr_label_status on asset_qr_label(status, intended_asset_type);
create index ix_asset_qr_label_asset on asset_qr_label(asset_id);

insert into access_permission (code, name, module_code) values
('QR_MANAGE', '管理资产二维码', 'resource'),
('QR_BIND', '扫码绑定资产二维码', 'resource');

insert into access_role_permission (role_code, permission_code) values
('SYSTEM_ADMIN', 'QR_MANAGE'),
('SYSTEM_ADMIN', 'QR_BIND'),
('MOLD_KEEPER', 'QR_BIND'),
('CART_OPERATOR', 'QR_BIND')
on conflict do nothing;
