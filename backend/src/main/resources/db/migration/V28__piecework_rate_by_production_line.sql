alter table piecework_rate add column route_type varchar(32) not null default 'MID_TEMP_WAX';
alter table piecework_rate drop constraint uk_piecework_rate;
alter table piecework_rate add constraint uk_piecework_rate_route unique (operation_code, route_type, version);

