create table auth_users (
    id serial primary key,
    email varchar(320) not null unique,
    password_hash varchar(512) not null,
    role varchar(32) not null,
    email_confirmed boolean not null default false,
    password_reset_token varchar(160),
    password_reset_expires_epoch_seconds bigint,
    access_failed_count integer not null default 0,
    lockout_end_epoch_seconds bigint
);

create index ix_auth_users_role on auth_users(role);
create index ix_auth_users_reset_token on auth_users(password_reset_token);
