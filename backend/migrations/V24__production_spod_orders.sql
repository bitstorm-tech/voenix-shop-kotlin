-- The remote lifecycle of a print-on-demand job (issue #205, T10;
-- docs/adr/0002-production-fulfillment-channels.md, decisions 2 and 4).
--
-- An SFTP job's whole lifecycle fits in `production_jobs`: render the document, push it,
-- report the shipment. A SPOD job has no document. What it has instead is a conversation
-- with a remote API — designs are uploaded, an order is created in state `NEW`, and only a
-- confirm call makes it real — and every step of that conversation has to survive a crash,
-- because the partner offers no idempotency mechanism at all: `POST /orders` cannot be
-- repeated safely, there is no order list, and an order can only be fetched by the id the
-- creation answered with. If that id is lost, the order is lost with it.
--
-- So the id gets its own row, written in its own transaction the moment it arrives and
-- before anything else happens. `production_spod_orders` is that row, one per job:
--
--   * `external_reference` is the SPOD order id. It is `NULL` until creation answered, and
--     unique once it is set, so the same remote order can never be claimed by two jobs.
--   * `create_state` is the creation half of the protocol. `PENDING` means "no order id is
--     known"; `CREATED` means "the id in `external_reference` is ours"; `OUTCOME_UNKNOWN`
--     means the job is quarantined and a human has to look at the SPOD backoffice.
--   * `create_ambiguous_count` counts the creations whose outcome nobody knows — a timeout,
--     a reset connection, a 5xx after the request went out. An order that was created but
--     never confirmed is inert (it produces nothing and charges nothing), so exactly one
--     automatic re-create is allowed; the second ambiguity flips `create_state` to
--     `OUTCOME_UNKNOWN` instead of risking a third orphan.
--   * `confirmed_at` and `remote_state` are the far half: when the confirm call succeeded,
--     and the last state the partner reported for this order (the webhook of T12 writes
--     `NEEDS_ACTION` and `CANCELLED` here).
--   * `attempt_count` and `last_error_code` are the same retry bookkeeping every other
--     production table carries. The error code is bounded and written by this backend; no
--     provider text, token, or URL is ever stored in it.
--
-- The foreign key is `RESTRICT`: a job whose remote order exists must not disappear from
-- under it. The uploaded designs hang off this row and cascade with it, because a design id
-- is only meaningful together with the order it was uploaded for.

CREATE TABLE production_spod_orders (
    production_job_id bigint NOT NULL,
    external_reference varchar(128),
    create_state varchar(32) NOT NULL DEFAULT 'PENDING',
    create_ambiguous_count integer NOT NULL DEFAULT 0,
    attempt_count integer NOT NULL DEFAULT 0,
    last_error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    confirmed_at timestamptz,
    remote_state varchar(32),
    CONSTRAINT pk_production_spod_orders PRIMARY KEY (production_job_id),
    CONSTRAINT fk_production_spod_orders_job
        FOREIGN KEY (production_job_id)
        REFERENCES production_jobs (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_production_spod_orders_create_state
        CHECK (create_state IN ('PENDING', 'CREATED', 'OUTCOME_UNKNOWN')),
    CONSTRAINT ck_production_spod_orders_remote_state
        CHECK (remote_state IS NULL OR remote_state IN ('CONFIRMED', 'NEEDS_ACTION', 'CANCELLED')),
    -- "Created" and "we know the id" are the same fact; the state may not claim one without
    -- the other, because the confirm step has nothing to address otherwise.
    CONSTRAINT ck_production_spod_orders_created_has_reference
        CHECK (create_state <> 'CREATED' OR external_reference IS NOT NULL),
    CONSTRAINT ck_production_spod_orders_ambiguous_count
        CHECK (create_ambiguous_count >= 0),
    CONSTRAINT ck_production_spod_orders_attempt_count
        CHECK (attempt_count >= 0),
    -- A confirmed order is one whose id is known; the timestamp cannot exist without it.
    CONSTRAINT ck_production_spod_orders_confirmed_has_reference
        CHECK (confirmed_at IS NULL OR external_reference IS NOT NULL)
);

-- One remote order belongs to exactly one job. `NULL` is exempt: every job starts without an
-- id, and a partial index lets any number of them wait at once.
CREATE UNIQUE INDEX ux_production_spod_orders_external_reference
    ON production_spod_orders (external_reference)
    WHERE external_reference IS NOT NULL;

-- One uploaded design per item line of the job, keyed by the line's 1-based position inside
-- the job — the very position `production_job_items` uses. The row is what makes an upload
-- happen once: a re-scan after a crash finds the design id here and skips straight to the
-- order creation instead of uploading the same PNG again.
CREATE TABLE production_spod_designs (
    production_job_id bigint NOT NULL,
    position integer NOT NULL,
    design_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_production_spod_designs PRIMARY KEY (production_job_id, position),
    CONSTRAINT fk_production_spod_designs_order
        FOREIGN KEY (production_job_id)
        REFERENCES production_spod_orders (production_job_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_production_spod_designs_position CHECK (position > 0)
);
