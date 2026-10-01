alter table resource_asset add column mold_custody_status varchar(32) not null default 'IN_STOCK';

create table mold_external_movement (
    id uuid primary key,
    movement_no varchar(64) not null unique,
    mold_asset_id uuid not null,
    movement_type varchar(24) not null,
    reason_code varchar(32) not null,
    reason_note varchar(500),
    counterparty_name varchar(160),
    expected_return_date date,
    status varchar(24) not null,
    checked_out_by varchar(64) not null,
    checked_out_at timestamp with time zone not null,
    returned_by varchar(64),
    returned_at timestamp with time zone,
    constraint fk_mold_external_asset foreign key (mold_asset_id) references resource_asset(id)
);

create index ix_mold_external_open on mold_external_movement(status, reason_code, expected_return_date);

insert into access_role_permission (role_code, permission_code) values
('FRONT_DESK_CLERK', 'ORDER_MOLD_SELECT')
on conflict do nothing;

delete from access_role_permission where role_code = 'LOW_WAX_SUPERVISOR' and permission_code = 'PIECEWORK_MANAGE';

