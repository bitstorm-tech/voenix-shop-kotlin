-- Channel-reported shipping and the ops alert mail (issue #205, T11;
-- docs/adr/0002-production-fulfillment-channels.md, decision 5).
--
-- Until now a shipment was always reported by a human: `shipped_by_user_id` names the supplier
-- login or the administrator who pressed the button. A print-on-demand job is different — the
-- partner reports the shipment by webhook, and there is no user behind it. Two columns carry that:
--
--   * `shipped_by_channel` names the fulfillment channel that reported the shipment. It is the
--     exact counterpart of `shipped_by_user_id`: a shipped job was reported either by a person or
--     by a channel, and the CHECK below refuses a row that claims both. Only the print-on-demand
--     channel reports shipments, hence the bounded single-value list; the SFTP side has no callback
--     and stays human-reported.
--
--     The CHECK is deliberately "never both" rather than the exclusive-or it looks like. The
--     foreign key of `shipped_by_user_id` is `ON DELETE SET NULL` (V11): deleting a supplier login
--     empties the column of every job that login shipped, and a strict XOR would turn that delete
--     into a constraint violation. A shipped job whose reporter was deleted is a job whose history
--     is thinner than it was — not a reason to refuse deleting the login.
--   * `shipping_carrier_reported` is the carrier string the partner sent, stored verbatim and shown
--     to administrators only. The customer's mail keeps building its tracking link from the bounded
--     `shipping_carrier` enum alone (decision J2 of issue #119): a name the partner chooses may
--     never decide where a link in a mail sent under the shop's name points. The raw name exists so
--     that an operator can see what "OTHER" actually was.
--
-- Both columns follow the same rule as the rest of the shipping half: they stay NULL until the job
-- is shipped.

ALTER TABLE production_jobs
    ADD COLUMN shipped_by_channel varchar(32),
    ADD COLUMN shipping_carrier_reported varchar(128);

ALTER TABLE production_jobs
    ADD CONSTRAINT ck_production_jobs_shipped_by_channel
        CHECK (shipped_by_channel IS NULL OR shipped_by_channel IN ('SPOD'));

-- The shipping-consistency CHECK gains the two new columns: NULL while the job is unshipped, and —
-- once it is — no more than one reporter.
ALTER TABLE production_jobs
    DROP CONSTRAINT ck_production_jobs_shipping_metadata_consistent;

ALTER TABLE production_jobs
    ADD CONSTRAINT ck_production_jobs_shipping_metadata_consistent
        CHECK (
            CASE
                WHEN shipped_at IS NULL THEN
                    shipped_by_user_id IS NULL
                        AND shipped_by_channel IS NULL
                        AND shipping_carrier IS NULL
                        AND shipping_carrier_reported IS NULL
                        AND tracking_number IS NULL
                ELSE prepared_at IS NOT NULL
                    AND NOT (shipped_by_user_id IS NOT NULL AND shipped_by_channel IS NOT NULL)
            END
        );

-- The ops alert of the print-on-demand channel: one mail per production job, sent to the configured
-- operations address when the partner cancels an order, flags it as needing action, or when the
-- submission stage quarantines a job whose creation outcome nobody knows. The outbox's unique
-- `(email_kind, source_id)` rule is what makes "one per job" true no matter how often the partner
-- redelivers the same event.
ALTER TABLE email_jobs
    DROP CONSTRAINT ck_email_jobs_kind;

ALTER TABLE email_jobs
    ADD CONSTRAINT ck_email_jobs_kind CHECK (
        email_kind IN (
            'ORDER_CONFIRMATION',
            'PRODUCER_PDF_NOTIFICATION',
            'SHIPPING_NOTIFICATION',
            'SPOD_OPS_ALERT'
        )
    );
