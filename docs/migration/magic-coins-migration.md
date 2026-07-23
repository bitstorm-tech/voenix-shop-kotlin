# Module migration: magic-coins

This is the module-specific migration record based on
[`migration-base.md`](migration-base.md). The general workflow lives in
[`module-migration-guide.md`](module-migration-guide.md).

## Task parameters

Target module:

`magic-coins`

Source project:

`/Users/joe/projects/joto-ai/voenix-shop`

Source feature:

`/Users/joe/projects/joto-ai/voenix-shop/backend/Voenix.Api/Features/MagicCoins`

Additional source (guest identity, lives in the Cart feature in .NET):

`/Users/joe/projects/joto-ai/voenix-shop/backend/Voenix.Api/Features/Cart/Services/GuestTokenService.cs`

Target project:

`/Users/joe/projects/joto-ai/voenix-shop-kotlin`

Target package:

`/Users/joe/projects/joto-ai/voenix-shop-kotlin/backend/modules/magic-coins/src/shop/voenix/magiccoins`

Analysis checkpoint:

`continue-automatically`

Known consumers:

- Frontend store `frontend/src/stores/shop/magicCoins.ts`: reads
  `GET /api/magic-coins/balance` with response shape `{ "balance": <int> }`
  and `Cache-Control: no-store`.
- Future Generator module: will consume the internal spend logic through a
  capability exported at Generator-migration time (see deferred work).
- The Magic-Coins purchase plans (`starter`/`studio`/`reserve`) are
  frontend-only data in `frontend/src/lib/magicCoins.ts`; purchasing runs
  through Checkout/Payment and is not part of this module.

Approved deviations from current behavior (decided with Joe on 2026-07-23):

- **Guest identity moves to `platform`.** The encrypted guest cookie
  (`voenix.guest`, HttpOnly, SameSite=Lax, 30 days, path `/api`) is
  implemented as a small capability next to `AuthModule` in the `platform`
  compilation module instead of a Cart-owned service, reusing the same
  crypto foundation as the session cookie. Cart and Generator reuse it later.
- **`user_id` has no foreign key yet.** The Kotlin schema has no `users`
  table. `magic_coins.user_id` is a nullable `bigint` without FK; the FK and
  its cascade delete arrive with the Auth/User migration. The XOR owner check
  (`(guest_session_token IS NOT NULL) <> (user_id IS NOT NULL)`), the
  `balance >= 0` check, and both partial unique indexes are preserved now.
- **No exception hierarchy.** The .NET `MagicCoinsException` family and its
  handler mapping are replaced by the shared `OperationResult`. Observable
  difference: .NET maps `MagicCoinsUnavailableException` to `503`; the Kotlin
  route uses the standard `UnexpectedFailure` mapping. Approved.
- **Spend logic stays `internal`.** `HasEnoughForGeneration` /
  `TrySpendForGeneration` behavior (cost 1, atomic
  `UPDATE … WHERE balance >= 1`) is migrated and tested inside the module but
  not exported as a public capability until the Generator migration defines
  the real consumer.

Implementation decisions without observable difference:

- Get-or-create keeps the .NET semantics (reading the balance creates the row
  with initial balance 10), implemented as a single
  `INSERT … ON CONFLICT DO NOTHING` upsert plus select instead of the .NET
  multi-step race handling.
- `MagicCoinsOwner` becomes a sealed interface (`User(id: Long)` /
  `Guest(token: String)`) instead of a nullable-field record with runtime XOR
  checks.
- Constants stay module-internal: initial balance 10, generation cost 1.
- Owner resolution mirrors .NET: a session whose user id does not parse as a
  positive `Long` falls back to the guest path (`.NET long.TryParse`
  behavior; Kotlin `toLongOrNull`).
- No guest-to-user balance merge on sign-in exists in .NET; none is added.

Explicitly deferred work:

- FK `magic_coins.user_id → users(id) ON DELETE CASCADE` — with the Auth/User
  migration.
- Public spend capability and the `INSUFFICIENT_MAGIC_COINS` error contract —
  with the Generator migration.
- Coin purchase flow — with the Checkout/Payment migration.

## Required instructions and sources

See [`migration-base.md`](migration-base.md) — unchanged.

## Outcome, analysis deliverable, implementation, completion report

Follow [`migration-base.md`](migration-base.md) and the guide. The analysis
matrix must cover at least: content of the balance response, get-or-create
initial balance, atomic spend with insufficient-balance outcome, spend-failure
logging semantics, owner XOR invariant, guest cookie issuance on first
contact, and `Cache-Control: no-store` on the balance route.

## Migration retrospective

Completed 2026-07-23 after implementation, verification, and the
simplification review.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| The planned platform seams did not exist yet: the key derivation for encrypted cookies was private inside `AuthModule`, and no public way existed to read the current session outside an `authenticate` block | `GuestTokens` needed the session-secret key derivation; the balance route needed the signed-in user on an anonymous route; the antiforgery route carried the same inline expiry check | `platform` auth | When a migration record assigns a capability to `platform`, check during analysis whether the required building blocks are actually exported or still private | Extracted `SessionCookieEncryption` and added the public `currentUserSession()` helper in `UserSession.kt`; the antiforgery route now reuses the helper. Cart and Generator consume both later |
| Exposed 1.3.1 has deprecated the `SqlExpressionBuilder` receiver object with `-Werror`-style build failures; arithmetic update expressions such as `balance - cost` need the top-level operator imports (`org.jetbrains.exposed.v1.core.minus`) | First compile of `MagicCoinsRepository` failed on the pattern still shown in older Exposed examples | Any module writing SQL expression updates | Compile the module right after the repository exists instead of after the full slice | Recorded here; no guide change — the repository's existing modules had no arithmetic update to copy from, and the compiler message states the fix |
| No further reusable process finding | Behavior matrix, contract, type map, and test plan matched the final implementation without rework | — | — | — |
