create table identity_account (
    employee_code varchar(64) primary key references organization_member(employee_code),
    password_hash varchar(100) not null,
    must_change_password boolean not null default true,
    failed_attempts integer not null default 0,
    locked_until timestamp with time zone,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_identity_account_locked_until on identity_account(locked_until);
