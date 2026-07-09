alter table orders
    add column mollie_payment_id varchar(64);

create unique index ux_orders_mollie_payment_id
    on orders(mollie_payment_id)
    where mollie_payment_id is not null;

create table payment_side_effects (
    id serial primary key,
    order_id integer not null references orders(id),
    type varchar(32) not null,
    status varchar(32) not null,
    attempts integer not null default 0,
    next_attempt_epoch_seconds bigint not null default 0,
    idempotency_key varchar(160) not null unique,
    last_error text
);

create index ix_payment_side_effects_due
    on payment_side_effects(status, next_attempt_epoch_seconds);
