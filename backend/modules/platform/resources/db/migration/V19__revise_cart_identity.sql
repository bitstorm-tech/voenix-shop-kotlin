-- Cart identity revised (issue #77, Joe's decision of 2026-08-04).
--
-- V15 made the guest session token the identity of a cart, always — even for a
-- signed-in customer (deviation 14 of docs/migration/cart-migration.md). That
-- decision is superseded: a signed-in request now finds its active cart by
-- `user_id`, and the guest token identifies anonymous carts only.
--
-- The reason is the login rotation shipped with the same issue. A login now
-- replaces the `voenix.guest` cookie, so the token a visitor browsed with stops
-- being a handle on anything they leave behind on a shared browser. With the
-- token as the cart's only identity, that rotation would orphan the very cart
-- the login had just claimed.
--
-- Three changes follow, and this migration is all three:
--
--   1. `guest_session_token` becomes nullable, and a cart carries exactly one
--      identity: the token while it is anonymous, the user id from the claim on.
--      Carrying both would mean a signed-out browser could still reach the
--      customer's cart through the token it kept.
--   2. `carts` gains a second partial unique index, so "at most one active cart
--      per user" is a database rule and not a preliminary read. It is what makes
--      two logins racing each other safe, exactly as the guest-token index makes
--      two concurrent first adds safe.
--   3. `MERGED` joins the status values. It is what a guest cart becomes when a
--      login merged its lines into the cart the customer already had: not
--      `CHECKED_OUT`, because nothing was bought, and not deleted, because an
--      order may still reference it as the evidence of what was ordered.

-- Nothing writes this column for a signed-in customer any more.
ALTER TABLE carts ALTER COLUMN guest_session_token DROP NOT NULL;

ALTER TABLE carts DROP CONSTRAINT ck_carts_status;
ALTER TABLE carts
    ADD CONSTRAINT ck_carts_status
    CHECK (status IN ('ACTIVE', 'CHECKED_OUT', 'MERGED'));

-- The old model allowed one active cart per *token*, so one customer could hold
-- several — one per device. Only the newest survives as the active one; the
-- others are retired exactly as a merge retires a guest cart.
UPDATE carts
SET status = 'MERGED'
WHERE status = 'ACTIVE'
  AND user_id IS NOT NULL
  AND id <> (
      SELECT max(newest.id)
      FROM carts AS newest
      WHERE newest.user_id = carts.user_id
        AND newest.status = 'ACTIVE'
  );

-- A cart that already belongs to a user is identified by that user from now on,
-- so the token it still carries is a dead second handle and is dropped.
UPDATE carts SET guest_session_token = NULL WHERE user_id IS NOT NULL;

-- One identity, never two. Both columns may be NULL at the same time, and that
-- state has exactly one cause: `fk_carts_user` is ON DELETE SET NULL, so
-- deleting an account leaves its carts behind, unreachable, as the evidence the
-- orders that reference them need.
ALTER TABLE carts
    ADD CONSTRAINT ck_carts_single_owner
    CHECK (guest_session_token IS NULL OR user_id IS NULL);

-- The two halves of "at most one active cart per owner". Retired and
-- checked-out carts fall outside both, so an owner may accumulate any number of
-- those and the customer's next add starts a fresh one.
DROP INDEX ux_carts_active_guest_session_token;

CREATE UNIQUE INDEX ux_carts_active_guest_session_token
    ON carts (guest_session_token)
    WHERE status = 'ACTIVE' AND guest_session_token IS NOT NULL;

CREATE UNIQUE INDEX ux_carts_active_user_id
    ON carts (user_id)
    WHERE status = 'ACTIVE' AND user_id IS NOT NULL;
