-- Fulfillment retry bounds, SPOD permanent-failure alert guard, and Mollie refund/chargeback tracking
-- (go-live packet 5: C19 and D2).
--
-- 1. `email_jobs`:
--    * `next_attempt_at` holds exponential backoff for transient delivery failures.
--    * `failed_permanently_at` marks a dead-lettered mail (permanent 4xx except 408/429, or
--      exhausted retry cap) so the worker stops hammering Sweego every minute.
--    * `ix_email_jobs_open` is narrowed to jobs that are neither sent nor permanently failed.
-- 2. `production_spod_orders`:
--    * `blocked_alerted_at` remembers that a `SUBMISSION_BLOCKED` ops alert was already enqueued
--      for this job, suppressing repeated alerts if permanent and transient errors alternate.
-- 3. `payments`:
--    * `refunded_amount_cents` and `charged_back_amount_cents` track the cumulative amounts Mollie
--      reported on a paid payment so webhooks record `PAYMENT_REFUNDED` / `PAYMENT_CHARGEBACK`
--      events idempotently and operations can surface chargebacks as findings.

ALTER TABLE email_jobs
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN failed_permanently_at timestamptz,
    ADD CONSTRAINT ck_email_jobs_terminal_exclusive
        CHECK (sent_at IS NULL OR failed_permanently_at IS NULL);

DROP INDEX IF EXISTS ix_email_jobs_open;
CREATE INDEX ix_email_jobs_open
    ON email_jobs (id)
    WHERE sent_at IS NULL AND failed_permanently_at IS NULL;

ALTER TABLE production_spod_orders
    ADD COLUMN blocked_alerted_at timestamptz;

ALTER TABLE payments
    ADD COLUMN refunded_amount_cents integer NOT NULL DEFAULT 0,
    ADD COLUMN charged_back_amount_cents integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_payments_refunded_amount_nonnegative CHECK (refunded_amount_cents >= 0),
    ADD CONSTRAINT ck_payments_charged_back_amount_nonnegative CHECK (charged_back_amount_cents >= 0);
