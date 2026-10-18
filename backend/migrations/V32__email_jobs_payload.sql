-- The email outbox stores the rendered mail instead of a business reference (issue #248).
--
-- The owning module renders the mail when it enqueues it, inside its own transaction, and the
-- worker sends `payload` exactly as stored: the email module never asks another module to resolve
-- a reference. `payload` holds `kind`, `recipient`, `recipientName`, `subject`, `html`, and `text`.
-- Without a reference there is nothing for `(email_kind, source_id)` to deduplicate, and the kind
-- lives in the payload, so both columns go with their constraints. A business event that must
-- produce one mail guards that in its own transaction.

ALTER TABLE email_jobs
    DROP CONSTRAINT uq_email_jobs_reference,
    DROP CONSTRAINT ck_email_jobs_kind,
    DROP CONSTRAINT ck_email_jobs_source_id_positive,
    DROP COLUMN email_kind,
    DROP COLUMN source_id,
    ADD COLUMN payload jsonb NOT NULL;
