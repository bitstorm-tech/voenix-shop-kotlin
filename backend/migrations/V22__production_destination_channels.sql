-- Per-channel destination detail tables (issue #205, T08;
-- docs/adr/0002-production-fulfillment-channels.md, decisions 1 and 3).
--
-- `production_destinations` was written when there was exactly one way to reach a
-- producer: push a PDF over SFTP. Every SFTP-shaped column therefore sat directly in
-- the table and was `NOT NULL`. A second channel (SPOD, the print-on-demand API that
-- produces t-shirts) does not have a host, a port, or a remote path — it has an
-- environment and an access token. Making all of those columns nullable in one wide
-- table would give up exactly the guarantee the `NOT NULL`s buy: that a configured
-- destination is completely configured.
--
-- So the destination is split the same way the article schema splits a mug from a
-- t-shirt: the base table keeps identity, supplier, channel, label, enabled, and the
-- notification fields, and one detail table per channel holds that channel's shape,
-- every column `NOT NULL` in its own table. The link is the composite foreign key
-- `(id, channel)` against the new alternate key of the base table, with a constant
-- `channel` column and a CHECK on each detail table. That pair is what makes the
-- wrong combination unrepresentable: an SFTP detail row can only ever attach to a
-- base row whose channel is `SFTP`, and switching a destination's channel is only
-- possible together with its detail row.
--
-- The existing SFTP rows are copied into `production_destination_sftp` before the
-- columns are dropped, so no configured destination loses its credentials.

-- The alternate key the detail tables reference. `id` alone is already the primary
-- key; this pair adds "and its channel", which is what makes the composite foreign
-- key below able to pin a detail row to one channel.
ALTER TABLE production_destinations
    ADD CONSTRAINT ux_production_destinations_id_channel UNIQUE (id, channel);

CREATE TABLE production_destination_sftp (
    id bigint NOT NULL,
    channel varchar(32) NOT NULL DEFAULT 'SFTP',
    host varchar(255) NOT NULL,
    port integer NOT NULL DEFAULT 22,
    username varchar(255) NOT NULL,
    password varchar(255) NOT NULL,
    host_key_fingerprint varchar(255) NOT NULL,
    remote_path varchar(1024) NOT NULL DEFAULT '/',
    timeout_seconds integer NOT NULL,
    CONSTRAINT pk_production_destination_sftp PRIMARY KEY (id),
    CONSTRAINT ck_production_destination_sftp_channel CHECK (channel = 'SFTP'),
    CONSTRAINT fk_production_destination_sftp_destination
        FOREIGN KEY (id, channel)
        REFERENCES production_destinations (id, channel)
        ON DELETE CASCADE,
    CONSTRAINT ck_production_destination_sftp_port_range
        CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_production_destination_sftp_timeout_range
        CHECK (timeout_seconds BETWEEN 1 AND 3600)
);

-- Every destination that exists today is an SFTP destination, and it keeps working
-- across this migration: the row moves, it is not recreated by an admin.
INSERT INTO production_destination_sftp (
    id, channel, host, port, username, password, host_key_fingerprint,
    remote_path, timeout_seconds
)
SELECT id, 'SFTP', host, port, username, password, host_key_fingerprint,
       remote_path, timeout_seconds
FROM production_destinations
WHERE channel = 'SFTP';

-- The range CHECKs of these columns go with them; PostgreSQL drops a constraint
-- together with the column it depends on.
ALTER TABLE production_destinations
    DROP COLUMN host,
    DROP COLUMN port,
    DROP COLUMN username,
    DROP COLUMN password,
    DROP COLUMN host_key_fingerprint,
    DROP COLUMN remote_path,
    DROP COLUMN timeout_seconds;

ALTER TABLE production_destinations
    DROP CONSTRAINT ck_production_destinations_channel;

ALTER TABLE production_destinations
    ADD CONSTRAINT ck_production_destinations_channel
        CHECK (channel IN ('SFTP', 'SPOD'));

CREATE TABLE production_destination_spod (
    id bigint NOT NULL,
    channel varchar(32) NOT NULL DEFAULT 'SPOD',
    -- The two environments `shop.voenix.production.spod.SpodEnvironment` knows. The
    -- base URL of each one is derived from this value in code and is deliberately
    -- not a column: no admin input may point fulfillment at an arbitrary host.
    environment varchar(32) NOT NULL,
    access_token varchar(512) NOT NULL,
    timeout_seconds integer NOT NULL,
    CONSTRAINT pk_production_destination_spod PRIMARY KEY (id),
    CONSTRAINT ck_production_destination_spod_channel CHECK (channel = 'SPOD'),
    CONSTRAINT fk_production_destination_spod_destination
        FOREIGN KEY (id, channel)
        REFERENCES production_destinations (id, channel)
        ON DELETE CASCADE,
    CONSTRAINT ck_production_destination_spod_environment
        CHECK (environment IN ('PRODUCTION', 'STAGING')),
    CONSTRAINT ck_production_destination_spod_timeout_range
        CHECK (timeout_seconds BETWEEN 1 AND 3600)
);

-- A supplier is reached through exactly one SPOD account at a time: the submission
-- worker picks the enabled SPOD destination of the job's supplier, and two of them
-- would make that pick ambiguous. Disabled rows are exempt, so an admin can prepare
-- the successor account before switching over.
CREATE UNIQUE INDEX ux_production_destinations_enabled_spod
    ON production_destinations (supplier_id)
    WHERE enabled AND channel = 'SPOD';
