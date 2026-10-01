create table organization_unit (
    id uuid primary key,
    code varchar(64) not null unique,
    name varchar(160) not null,
    unit_type varchar(32) not null,
    parent_code varchar(64),
    active boolean not null,
    created_at timestamp with time zone not null
);

create table organization_member (
    id uuid primary key,
    employee_code varchar(64) not null unique,
    name varchar(100) not null,
    unit_code varchar(64) not null,
    role_code varchar(64) not null,
    active boolean not null,
    created_at timestamp with time zone not null,
    constraint fk_member_unit foreign key (unit_code) references organization_unit(code)
);

create table resource_asset (
    id uuid primary key,
    asset_code varchar(64) not null unique,
    asset_name varchar(160) not null,
    asset_type varchar(32) not null,
    status varchar(24) not null,
    location_code varchar(64),
    life_limit integer,
    life_used integer not null,
    version bigint not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint ck_resource_life check (
        life_used >= 0 and (life_limit is null or (life_limit > 0 and life_used <= life_limit))
    )
);

create table resource_occupation (
    id uuid primary key,
    asset_id uuid not null,
    active_asset_id uuid unique,
    business_key varchar(128) not null,
    status varchar(24) not null,
    occupied_by varchar(64) not null,
    occupied_at timestamp with time zone not null,
    released_by varchar(64),
    released_at timestamp with time zone,
    note varchar(500),
    constraint fk_occupation_asset foreign key (asset_id) references resource_asset(id),
    constraint fk_active_occupation_asset foreign key (active_asset_id) references resource_asset(id)
);

create table notification_item (
    id uuid primary key,
    notification_no varchar(64) not null unique,
    recipient_code varchar(64) not null,
    category varchar(32) not null,
    title varchar(200) not null,
    content_text varchar(1000) not null,
    business_link varchar(300),
    status varchar(24) not null,
    created_at timestamp with time zone not null,
    read_at timestamp with time zone
);

create table integration_job (
    id uuid primary key,
    operation_id uuid not null unique,
    job_no varchar(64) not null unique,
    interface_code varchar(64) not null,
    business_key varchar(128) not null,
    direction varchar(16) not null,
    payload_text varchar(4000) not null,
    status varchar(24) not null,
    attempt_count integer not null,
    last_error varchar(1000),
    next_retry_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint ck_integration_attempt check (attempt_count >= 0)
);

create index ix_member_unit on organization_member(unit_code, active);
create index ix_resource_type_status on resource_asset(asset_type, status);
create index ix_occupation_business on resource_occupation(business_key, occupied_at);
create index ix_notification_recipient on notification_item(recipient_code, status, created_at);
create index ix_integration_status on integration_job(status, next_retry_at);
