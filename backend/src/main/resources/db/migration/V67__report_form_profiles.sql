create table operation_report_form_profile (
    operation_code varchar(64) primary key,
    show_photo boolean not null default true,
    require_photo boolean not null default false,
    show_device boolean not null default false,
    require_device boolean not null default false,
    show_workstation boolean not null default false,
    require_workstation boolean not null default false,
    updated_by varchar(64) not null,
    updated_at timestamp not null
);
