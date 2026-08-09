# Account post-migration to-do list

This file owns work that is intentionally deferred until after the standalone
Account migration. The migration itself is defined in
[`account-migration.md`](account-migration.md). Do not implement these items
inside the Account migration, and do not create placeholder Cart or frontend
infrastructure to complete them early.

## Frontend adaptation

Joe approved two observable contract changes on 2026-07-23: the shared
ApiError conventions replace the legacy `AuthResponse { success, message,
code }` envelope, and CSRF protection now covers the authenticated auth
mutations. The Vue frontend in `frontend/` must follow before it is
pointed at the Kotlin backend.

Done with issue #89 (part of the frontend migration, issue #84).

- [x] Rework `postAuth` in `src/stores/shared/auth.ts`: success is the HTTP
  status (mutations return `204 No Content` without a body); failures carry
  the shared error body instead of `success`/`message`/`code` fields.
- [x] Replace the `result.code === 'EMAIL_NOT_CONFIRMED'` branch in
  `LoginView.vue` with a check on the 403 status; distinguish lockout via 429
  and bad credentials via 401.
- [x] Send the `X-XSRF-TOKEN` header (obtained from `GET
  /api/antiforgery/token`, as the admin and cart flows already do) on
  `PUT /api/auth/profile`, `POST change-email`, `POST change-password`, and
  `POST logout`. The store sends the anonymous routes with
  `skipAntiforgery`, because they live outside the CSRF-protected subtree.
- [x] `updateProfile` keeps expecting the profile JSON on 200 but must read
  errors from the shared error shape.
- [x] Surface the new 502 outcome of `register` and `change-email` (required
  confirmation mail could not be delivered) as a retryable error; the resend
  flows stay the retry path. A registration that answers 502 has already
  stored the account, so registering again would only answer 409 — the
  retry is `POST /api/auth/resend-confirmation`, offered by the shared
  `components/auth/ResendConfirmationAlert.vue`.

## Guest data claim (owner: Cart and Order migrations)

The legacy `Features/Auth/Services/GuestDataClaimService.cs` transfers guest
data to the account on login and registration. The Kotlin account module lands
without this behavior; the Cart migration owns the seam because carts and
generated images arrive with that slice, and the Order migration adds the order
rows.

The Cart migration delivered the seam and the cart half of the claim on
2026-07-30 (`GuestDataClaims` port in the account module, bound by the
composition root to the cart's `CartGuestData`); see
[`cart-migration.md`](cart-migration.md). The Order migration completed it on
2026-07-31; see [`order-migration.md`](order-migration.md).

- [x] Reimplement the claim on login and registration: carts and print images
  by guest token, orders by guest token **and** by case-insensitive e-mail
  match. The port changed shape for it — `claim(userId, guestToken: String?,
  email: String?)` — because an order can be found under an address alone, so a
  missing guest cookie is no longer a reason to skip the claim.
- [x] Two rules came with the second half, and both are security decisions
  rather than plumbing. The e-mail handle is passed **on login only**
  (`LoginResult.SignedIn` carries the account's stored address), because a
  registration proves nothing about the address it was made with and claiming
  by it would be an account takeover (deviation D21). And the branches run
  **independently**: the app-owned `IndependentGuestDataClaims` catches per
  branch, so a cart that cannot be moved never costs the customer their order
  history (`IndependentGuestDataClaimsTest`).
- [x] Design the seam so that Account does not depend on Cart: the account
  module defines the `GuestDataClaims` port, the routes call it best effort
  after a successful login and registration, and the composition root binds
  it. There is no account→cart compilation dependency.
- [x] Decide the legacy gap that MagicCoins balances are never claimed on
  login or registration. `magic_coins` enforces exactly one owner (guest XOR
  user) with a unique `user_id`, so merging a guest balance into an existing
  user balance needs an explicit domain decision, not just a claim call.
  Decided by Joe on 2026-08-04: no claim and no merge — a guest cannot buy
  coins, so the guest balance is deliberately lost on login (see
  [`all-post-migration.md`](all-post-migration.md) and issue #77).
