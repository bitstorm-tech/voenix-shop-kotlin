create table customers (
    id serial primary key,
    email varchar(320) not null unique,
    display_name varchar(160),
    notes text
);

create table orders (
    id serial primary key,
    customer_id integer not null references customers(id),
    status varchar(32) not null,
    customer_reference varchar(120)
);

create index ix_orders_customer_id on orders(customer_id);
