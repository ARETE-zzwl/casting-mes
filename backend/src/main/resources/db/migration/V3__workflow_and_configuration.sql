create table workflow_request (
    id uuid primary key,
    request_no varchar(64) not null unique,
    workflow_type varchar(64) not null,
    business_key varchar(128) not null,
    title varchar(200) not null,
    requester_code varchar(64) not null,
    status varchar(24) not null,
    required_approvals integer not null,
    approval_count integer not null,
    payload text,
    created_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    constraint ck_workflow_approvals check (
        required_approvals > 0
        and approval_count >= 0
        and approval_count <= required_approvals
    )
);

create table workflow_action (
    id uuid primary key,
    request_id uuid not null,
    action varchar(24) not null,
    actor_code varchar(64) not null,
    comment_text varchar(500),
    occurred_at timestamp with time zone not null,
    constraint fk_workflow_action_request foreign key (request_id) references workflow_request(id),
    constraint uk_workflow_actor unique (request_id, actor_code)
);

create table configuration_package (
    id uuid primary key,
    package_no varchar(64) not null unique,
    config_type varchar(64) not null,
    name varchar(160) not null,
    version varchar(32) not null,
    content_text text not null,
    status varchar(24) not null,
    created_by varchar(64) not null,
    created_at timestamp with time zone not null,
    published_by varchar(64),
    published_at timestamp with time zone,
    rolled_back_by varchar(64),
    rolled_back_at timestamp with time zone,
    constraint uk_configuration_version unique (config_type, name, version)
);

create index ix_workflow_status on workflow_request(status, created_at);
create index ix_workflow_action_request on workflow_action(request_id, occurred_at);
create index ix_configuration_status on configuration_package(config_type, status, created_at);
