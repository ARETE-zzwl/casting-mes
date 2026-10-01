create table inventory_balance_creation_lock (
    id integer primary key,
    lock_name varchar(64) not null
);

insert into inventory_balance_creation_lock (id, lock_name)
values (1, 'INVENTORY_BALANCE_CREATION');
