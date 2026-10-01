create table product_process_card_template (
    id uuid primary key,
    template_no varchar(64) not null unique,
    product_id uuid not null,
    version varchar(64) not null,
    status varchar(24) not null,
    engineering_parameters clob not null,
    operation_parameters clob not null,
    created_by varchar(64) not null,
    published_by varchar(64),
    created_at timestamp with time zone not null,
    published_at timestamp with time zone,
    constraint fk_process_template_product foreign key (product_id) references engineering_product(id),
    constraint uq_process_template_product_version unique(product_id, version)
);

create index ix_process_template_product_status on product_process_card_template(product_id, status, created_at desc);
