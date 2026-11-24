-- Go-live database hardening:
--
-- 1. Drop `ck_orders_owner` on `orders`. `fk_orders_user` uses `ON DELETE SET NULL`
--    so order records survive DSGVO account deletion, while orders placed by
--    registered customers have `guest_session_token IS NULL`. The check constraint
--    forbade both columns being NULL at once and therefore made `DELETE FROM users`
--    fail whenever the user had placed an order. Every order already carries its
--    own NOT NULL `access_token`, matching `carts` (which has no owner check).
-- 2. Add a partial index on open `email_jobs` rows (`WHERE sent_at IS NULL`) so
--    the outbox worker and operations stall scan do not sequentially scan all
--    historically sent payloads.

ALTER TABLE orders
    DROP CONSTRAINT IF EXISTS ck_orders_owner;

CREATE INDEX IF NOT EXISTS ix_email_jobs_open
    ON email_jobs (id)
    WHERE sent_at IS NULL;
