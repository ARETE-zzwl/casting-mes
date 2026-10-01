alter table resource_asset add column ownership_type varchar(32) not null default 'COMPANY_OWNED';
alter table resource_asset add column owner_name varchar(160);

alter table resource_asset add constraint ck_resource_asset_ownership_type
    check (ownership_type in ('COMPANY_OWNED', 'CUSTOMER_OWNED'));

alter table mold_request add column mold_ownership_type varchar(32);
alter table mold_request add column mold_owner_name varchar(160);
