create table trusted_identity_binding (
    issuer varchar(512) not null,
    subject varchar(255) not null,
    employee_code varchar(64) not null references organization_member(employee_code),
    primary key (issuer, subject)
);

create table stored_attachment (
    file_path varchar(512) primary key,
    owner_code varchar(64) not null references organization_member(employee_code),
    content_type varchar(100) not null,
    created_at timestamp with time zone not null
);

create table attachment_reader (
    file_path varchar(512) not null references stored_attachment(file_path),
    employee_code varchar(64) not null references organization_member(employee_code),
    primary key (file_path, employee_code)
);
