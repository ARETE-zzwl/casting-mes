insert into access_permission (code, name, module_code) values
('CUSTOMER_VIEW', '客户查询', 'customerorder'),
('CUSTOMER_CHANGE_REQUEST', '客户敏感操作申请', 'customerorder'),
('CUSTOMER_CHANGE_APPROVE', '客户敏感操作审批', 'customerorder')
on conflict do nothing;

insert into access_role_permission (role_code, permission_code) values
('FRONT_DESK_CLERK', 'CUSTOMER_VIEW'),
('FRONT_DESK_CLERK', 'CUSTOMER_CHANGE_REQUEST'),
('CUSTOMER_MANAGER', 'CUSTOMER_VIEW'),
('CUSTOMER_MANAGER', 'CUSTOMER_CHANGE_APPROVE'),
('CUSTOMER_MANAGER', 'ORDER_MANAGE'),
('GENERAL_MANAGER', 'CUSTOMER_VIEW'),
('GENERAL_MANAGER', 'CUSTOMER_CHANGE_APPROVE'),
('SYSTEM_ADMIN', 'CUSTOMER_VIEW'),
('SYSTEM_ADMIN', 'CUSTOMER_CHANGE_REQUEST'),
('SYSTEM_ADMIN', 'CUSTOMER_CHANGE_APPROVE')
on conflict do nothing;

create table customer_sensitive_request (
    id uuid primary key,
    request_no varchar(64) not null unique,
    action_type varchar(32) not null,
    proposed_code varchar(64),
    proposed_name varchar(160) not null,
    proposed_contact_name varchar(100),
    proposed_contact_phone varchar(40),
    proposed_sales_owner varchar(100),
    request_note varchar(500),
    requested_by varchar(64) not null,
    requested_at timestamp with time zone not null,
    status varchar(24) not null,
    reviewed_by varchar(64),
    reviewed_at timestamp with time zone,
    review_note varchar(500),
    executed_customer_id uuid,
    executed_at timestamp with time zone,
    constraint ck_customer_sensitive_request_status check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    constraint ck_customer_sensitive_request_action check (action_type = 'CREATE_CUSTOMER')
);

create index ix_customer_sensitive_request_status on customer_sensitive_request(status, requested_at desc);
