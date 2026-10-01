create table operation_sop_key_parameter (
    id uuid primary key,
    sop_id uuid not null,
    parameter_no integer not null,
    parameter_name varchar(80) not null,
    parameter_value varchar(500) not null,
    constraint fk_sop_key_parameter foreign key (sop_id) references operation_sop(id),
    constraint uk_sop_key_parameter unique (sop_id, parameter_no)
);

create index ix_sop_key_parameter_sop on operation_sop_key_parameter(sop_id, parameter_no);
