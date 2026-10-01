alter table piecework_entry add column settlement_date date;

update piecework_entry
set settlement_date = cast(occurred_at as date)
where settlement_date is null;

alter table piecework_entry alter column settlement_date set not null;

create index ix_piecework_settlement_date on piecework_entry(settlement_date, status);
