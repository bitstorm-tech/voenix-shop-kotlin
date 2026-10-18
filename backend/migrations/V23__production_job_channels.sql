-- Channel-aware job lifecycle (issue #205, T09;
-- docs/adr/0002-production-fulfillment-channels.md, decisions 1 and 2).
--
-- A production job used to have exactly one shape: render a PDF, push it over SFTP, let a human
-- report the shipment. `generated_at` therefore meant two different things at once — "the immutable
-- document exists" and "this job is far enough along to be shipped". A print-on-demand job has no
-- document at all: it is prepared by creating and confirming an order through the partner's API.
--
-- So the two meanings are separated. `generated_at` keeps the first one and stays exactly what it
-- was: the timestamp of the PDF whose digest sits next to it. `prepared_at` carries the second one
-- for every channel — set together with `generated_at` on an SFTP job, set when the remote order is
-- confirmed on a SPOD job — and it is what the guarded ship update reads from now on.
--
-- `fulfillment_channel` is the job's own copy of how it is produced, decided when the split worker
-- creates it from the supplier's enabled destinations. Routing stays live master data; the job's
-- channel is frozen the moment the job exists, so a destination reconfigured mid-flight cannot make
-- a running job change its lifecycle halfway through.
--
-- Every job that exists today is an SFTP job that was prepared exactly when its PDF was generated,
-- which is precisely the backfill below.

ALTER TABLE production_jobs
    ADD COLUMN fulfillment_channel varchar(32),
    ADD COLUMN prepared_at timestamptz;

UPDATE production_jobs
SET fulfillment_channel = 'SFTP',
    prepared_at = generated_at;

ALTER TABLE production_jobs
    ALTER COLUMN fulfillment_channel SET NOT NULL;

-- Deliberately without a DEFAULT: the channel is a decision the split makes from the supplier's
-- destinations, and a default would let a forgotten write silently produce an SFTP job.
ALTER TABLE production_jobs
    ADD CONSTRAINT ck_production_jobs_fulfillment_channel
        CHECK (fulfillment_channel IN ('SFTP', 'SPOD'));

-- The shipping-consistency CHECK gains the channel-neutral readiness rule that used to live in the
-- application's `generated_at IS NOT NULL` guard alone: shipping data exists only for a shipped
-- job, and a job can only be shipped once it was prepared. The database now refuses the state the
-- guarded UPDATE refuses, instead of merely the half of it that is about the carrier columns.
ALTER TABLE production_jobs
    DROP CONSTRAINT ck_production_jobs_shipping_metadata_consistent;

ALTER TABLE production_jobs
    ADD CONSTRAINT ck_production_jobs_shipping_metadata_consistent
        CHECK (
            CASE
                WHEN shipped_at IS NULL THEN
                    shipped_by_user_id IS NULL
                        AND shipping_carrier IS NULL
                        AND tracking_number IS NULL
                ELSE prepared_at IS NOT NULL
            END
        );
