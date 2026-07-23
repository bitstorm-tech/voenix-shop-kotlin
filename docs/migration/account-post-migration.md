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
mutations. The Vue frontend in `../voenix-shop/frontend` must follow before it
is pointed at the Kotlin backend.

- [ ] Rework `postAuth` in `src/stores/shared/auth.ts`: success is the HTTP
  status (mutations return `204 No Content` without a body); failures carry
  the shared error body instead of `success`/`message`/`code` fields.
- [ ] Replace the `result.code === 'EMAIL_NOT_CONFIRMED'` branch in
  `LoginView.vue` with a check on the 403 status; distinguish lockout via 429
  and bad credentials via 401.
- [ ] Send the `X-XSRF-TOKEN` header (obtained from `GET
  /api/antiforgery/token`, as the admin and cart flows already do) on
  `PUT /api/auth/profile`, `POST change-email`, `POST change-password`, and
  `POST logout`.
- [ ] `updateProfile` keeps expecting the profile JSON on 200 but must read
  errors from the shared error shape.
- [ ] Surface the new 502 outcome of `register` and `change-email` (required
  confirmation mail could not be delivered) as a retryable error; the resend
  flows stay the retry path.

## Guest data claim (owner: Cart migration)

The legacy `Features/Auth/Services/GuestDataClaimService.cs` transfers guest
data to the account on login and registration. The Kotlin account module lands
without this behavior; the Cart migration owns it because carts, orders, and
generated images arrive with that slice.

- [ ] Reimplement the claim on login and registration: carts, orders, and
  generated edited images by guest token, plus orders matched by the account
  e-mail on login.
- [ ] Design the seam so that Account does not depend on Cart: the login and
  registration operations need a claim hook the composition root wires once
  the Cart module exists.
- [ ] Decide the legacy gap that MagicCoins balances are never claimed on
  login or registration. `magic_coins` enforces exactly one owner (guest XOR
  user) with a unique `user_id`, so merging a guest balance into an existing
  user balance needs an explicit domain decision, not just a claim call.
