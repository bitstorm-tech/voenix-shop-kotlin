# Account module migration

This record follows [`migration-base.md`](migration-base.md); the rules live in
[`module-migration-guide.md`](module-migration-guide.md).

## Status

`awaiting-approval`

All analysis artifacts (1–8) are complete. The behavior matrix, contract
table, and deviation log were approved by Joe on 2026-07-23; the spec is
[issue #5](https://github.com/bitstorm-tech/voenix-shop-kotlin/issues/5). The
design artifacts (type map, composition design, test plan) await Joe's
approval before implementation starts.

## Task parameters

Target module:

`account`

The module name is `account` (not `auth`) because the package
`shop.voenix.auth` already belongs to the `platform` module's session and
CSRF infrastructure, and because the feature owns accounts, profiles, and
addresses, not only authentication.

Source feature:

`../voenix-shop/backend/Voenix.Api/Features/Auth` plus
`Configuration/AuthConfiguration.cs` and the Auth mappings in
`ErrorHandling/DomainExceptionHandler.cs`

Binding prior decisions: the "Auth email composition" section in
[`email-post-migration.md`](email-post-migration.md) governs how this module
sends account mails — direct delivery through `UserEmailSender`, never
`email_jobs` rows; URL building, `frontendBaseUrl`, and token-lifetime
ownership move into this module.

Target package:

`backend/modules/account/src/shop/voenix/account`

Analysis checkpoint:

`wait-for-approval`

Known consumers:

- Vue frontend: `../voenix-shop/frontend/src/stores/shared/auth.ts` calls every
  `/api/auth/*` endpoint; `LoginView.vue` branches on the login failure kind.
  The frontend must be adapted for the approved response-format and CSRF
  deviations (see deferred work).
- `platform` module: consumes credentials verification indirectly — the account
  module is the missing trusted component that creates `UserSession` values
  (`docs/dev/backend/authentication-and-authorization.md`, "Important current
  limitation").
- `magic-coins` module: `magic_coins.user_id bigint` deliberately waits for the
  `users` table to add its foreign key
  (`backend/modules/platform/resources/db/migration/V10__create_magic_coins.sql`).
- Future Cart/Order/Checkout migrations: reference `users.id` and pick up the
  deferred guest-data claim.

Approved deviations from current behavior:

- Responses use the shared `ApiError` conventions instead of the legacy
  `AuthResponse { success, message, code }` shape.
- CSRF protection is extended to the authenticated mutations (`PUT profile`,
  `change-email`, `change-password`, `logout`); the legacy app protects only
  `admin` and `cart` routes.
- Confirmation, password-reset, and change-email tokens are persisted
  single-use rows instead of stateless ASP.NET DataProtection tokens.
- Password hashing uses JDK PBKDF2-HMAC-SHA256 (~600k iterations, per current
  OWASP guidance) instead of the Identity hasher format.
- ASP.NET Identity ballast is dropped: no username distinct from email, no
  claims, external logins, 2FA, or phone columns, no `security_stamp` /
  `concurrency_stamp`.
- The first `ADMIN` user is created manually via a documented SQL snippet in
  `docs/dev`; the application seeds nothing.

Explicitly deferred work:

- Guest-data claim on login/registration
  (`Features/Auth/Services/GuestDataClaimService.cs` transfers carts, orders,
  and generated images from the guest token, plus orders by e-mail match).
  Owner: Cart migration. That migration must also decide the legacy gap that
  MagicCoins balances are never claimed.
- Frontend adaptation to the new response format, the removed
  `EMAIL_NOT_CONFIRMED` code field (replaced by the 403 status), and the CSRF
  header on authenticated auth mutations. Owner: Joe / frontend follow-up after
  this migration; tracked in
  [`account-post-migration.md`](account-post-migration.md).

## Analysis deliverable

### 1. Behavior matrix

Source references are relative to `../voenix-shop/backend/Voenix.Api`.
`AuthController` = `Features/Auth/Controllers/AuthController.cs`,
`AuthConfiguration` = `Configuration/AuthConfiguration.cs`,
`ExceptionHandler` = `ErrorHandling/DomainExceptionHandler.cs`.

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| Registration creates an account from e-mail + password; e-mail doubles as the login name | `AuthController:32-68`; `User.UserName = request.Email` | Required | `users` row with a single `email` column; no separate username | Service + route integration test |
| New accounts get role `CUSTOMER` | `AuthController:54` | Required | Insert into `user_roles` in the same transaction | Integration test asserts role after registration |
| Password rule: minimum 8 characters, no other complexity rules | `RegisterRequest`, `ResetPasswordRequest`, `ChangePasswordRequest` (`MinLength(8)`); `AuthConfiguration:18-23` | Required | One shared password rule in the pure validator, used by register, reset, and change | Validator unit test (field-rule matrix) |
| E-mail format is validated on every input carrying an e-mail | DTO `[EmailAddress]` annotations | Required | Shared e-mail rule in the pure validators | Validator unit tests |
| Duplicate e-mail on registration → 409 | `AuthController:45-47`; `ExceptionHandler:87-91` | Required | Unique index on normalized e-mail; `executePostgresWrite(uniqueViolation = …)` → `OperationResult.Conflict` | Duplicate + concurrent-duplicate integration tests |
| Registration sends a confirmation mail with link `{frontendBaseUrl}/confirm-email?userId=…&token=…` | `AuthController:60-66` | Required | Create `account_tokens` row, send `UserEmail.AccountConfirmation` **directly** via `UserEmailSender` (no `email_jobs` row, per `email-post-migration.md`); URL built and percent-encoded in this module | Integration test with a recording sender asserts mail + URL shape and that no email job exists |
| A failed required confirmation/change-email delivery surfaces as an external dependency failure (502) | `SweegoClient` throws → `EmailSendingException` → 502; `email-post-migration.md` "Auth email composition" | Required | `UserEmailSender` failure in `register`/`change-email` → dedicated failure outcome → 502; user retries via resend | Integration test with failing sender |
| Login is refused with 403 until the e-mail is confirmed | `AuthConfiguration:31` (`RequireConfirmedEmail`); `AuthController:88-89`; `ExceptionHandler:92-96` | Required | `email_confirmed` check during credential verification → dedicated operation outcome → 403 | Route integration test |
| Login failure (unknown e-mail or wrong password) → uniform 401 | `AuthController:74-92`; `ExceptionHandler:97-101` | Required | Same outcome for both paths; no enumeration via timing shortcut (hash comparison also for unknown users) | Route test: identical status/body for both causes |
| Lockout: 15 failed attempts lock the account for 10 minutes → 429; counter also applies to fresh accounts | `AuthConfiguration:25-27`; `ExceptionHandler:102-106` | Required | `failed_login_count` + `locked_until` columns, checked and updated inside the login transaction | Integration test: 15 failures lock, lock expires, success resets counter |
| Successful login establishes the 24h sliding cookie session | `AuthConfiguration:45-50`; platform already owns session mechanics | Required | Create `UserSession` (platform) with the user's id and roles; platform renewal keeps the sliding semantics | Route test: login sets session cookie; `me` works afterwards |
| Successful login resets the failure counter | ASP.NET Identity lockout semantics | Required | Reset `failed_login_count`/`locked_until` on success | Covered by lockout integration test |
| Logout clears the session | `AuthController:174-179` | Required | Clear the Ktor session | Route test |
| `confirm-email` with unknown user or invalid/expired token → 400 "invalid or expired" without distinguishing the cause | `AuthController:100-114` | Required | Token lookup by hash + purpose + expiry + unused; single failure outcome | Integration tests: wrong token, expired token, unknown user |
| `resend-confirmation` and `forgot-password` always answer success (user enumeration protection); mail is sent only when applicable | `AuthController:116-156`; `email-post-migration.md`: existence must not be observable through response shape, timing, or e-mail errors | Required | Always `Success`; conditional token + direct send; a delivery failure is logged and must not change the response | Integration tests for both branches, incl. failing sender |
| `resend-confirmation` sends nothing for already-confirmed accounts | `AuthController:121` | Required | Guard on `email_confirmed` | Integration test |
| `reset-password` validates token, sets new password, then sends `PasswordChangedNotification` | `AuthController:158-172` | Required | Consume token row, store new hash, best-effort direct notification | Integration test |
| Reset/confirmation/change-email tokens are valid 24h and single-use | Identity `AddDefaultTokenProviders` default lifetime; mail templates promise 24h | Required (lifetime) / token mechanics are an approved deviation | `account_tokens` rows: SHA-256 token hash, purpose, `expires_at = now + 24h`, `consumed_at` | Expiry + reuse integration tests |
| Issuing a new token invalidates prior tokens of the same purpose | Identity security-stamp behavior (stamp changes invalidate older tokens) | Required | Delete (or mark consumed) existing rows of the same user + purpose when issuing | Integration test: old link stops working after resend |
| `GET me` returns id, e-mail, roles, shipping/billing address, `hasSeparateBillingAddress`, `createdAt` | `MeResponse.cs`; frontend `auth.ts` `User` interface | Required | One serializable profile representation reused by `me` and `profile` | Route integration test |
| `PUT profile` replaces all shipping fields; when `hasSeparateBillingAddress` is false all billing fields become `null`; blank phone → `null` | `AuthController:194-250` | Required | Full-replace semantics in one update; normalization blank→null after validation | Service integration test |
| Address field rules: max lengths (100/100/200/20/10/100), country = 2 chars, phone matches `^\+?[\d\s\-().\/]+$` | `AddressDto.cs` | Required | Address rules once in the pure validator | Validator field-rule matrix test |
| `change-email` requires the current password (401 on mismatch) and rejects an already-used target e-mail (409) | `AuthController:252-280`; `ExceptionHandler:107` | Required | Password check + unique guard; DB unique index remains the concurrency-safe authority at confirm time | Integration tests |
| `change-email` sends confirmation to the new address and a notification to the old address | `AuthController:274-277` | Required | Two direct sends: `ChangeEmailConfirmation` (required, failure → 502) + `ChangeEmailNotification` (best effort) | Integration test asserts both sends |
| `confirm-change-email` is anonymous, validates token for user + new e-mail, then the new e-mail becomes the login e-mail | `AuthController:282-300` | Required | Token row stores the pending new e-mail; update `users.email` on confirm | Integration test incl. conflict when e-mail was taken meanwhile |
| `change-password` rejects a wrong current password with 401, other failures 400; sends `PasswordChangedNotification` | `AuthController:302-330`; `ExceptionHandler:107-113` | Required | Verify current hash, store new hash, best-effort direct notification | Integration test |
| Changing the password keeps the current session (and other sessions) valid | `AuthController:324-325` (`RefreshSignInAsync`) | Required (documented) | Nothing to do: platform cookie sessions are self-contained and unaffected by a hash change | Route test: `me` still works after password change |
| Failures of optional notification mails (`PasswordChangedNotification`, `ChangeEmailNotification`) do not fail the operation | `EmailService` swallows notification exceptions; `email-post-migration.md` leaves the policy to this module | Required (kept best effort) | Log and continue on direct-send failure; `CancellationException` is still rethrown | Integration test with failing sender |
| Response envelope `{ success, message, code }` on every auth endpoint | `AuthResponse.cs`; frontend `postAuth` | Approved deviation | Shared conventions: success payload or empty success, `ApiError` for failures; `EMAIL_NOT_CONFIRMED` is expressed by the 403 status alone | Route tests assert new shapes |
| Auth endpoints are exempt from CSRF | `Configuration/MutationAntiforgeryConvention.cs` (only `admin`/`cart`) | Approved deviation | Authenticated mutations (`profile`, `change-email`, `change-password`, `logout`) require the platform CSRF header; anonymous endpoints stay CSRF-free. Platform policy answers a missing/invalid token with 400 (`AuthModule.requireCsrf`) | Route security tests: missing header → 400 before operation |
| Identity PBKDF2 hash format (V3, 100k iterations) | Identity default hasher | Incidental | JDK PBKDF2-HMAC-SHA256, ~600k iterations, versioned encoding for future migration | Unit test: hash/verify roundtrip, tamper detection |
| Frontend link base URL falls back to the request host when `Email.FrontendBaseUrl` is unset | `AuthController:347-353` | Incidental | Explicit configuration only; fail fast at startup when missing | Startup/config test |
| Roles/claims machinery (`roles`, `user_claims`, `role_claims`, `user_logins`, `user_tokens` tables; 2FA/phone columns; security/concurrency stamps) | Identity schema; unused by any endpoint | Incidental | Not migrated: `user_roles(user_id, role text)` only | Schema test proves the lean schema |
| Roles `ADMIN`/`CUSTOMER` are seeded at startup | `Program.cs:102-110` | Incidental | No seeding: roles are plain text values; first `ADMIN` via documented SQL snippet | Documented in `docs/dev`; admin route tests use fixtures |
| Guest data claim on login/registration | `AuthController:56-58,94-95`; `GuestDataClaimService.cs` | Required, deferred | Not part of this module; owner: Cart migration | Recorded in deferred work |

### 2. Operation contract table

All routes stay under `/api/auth` for frontend compatibility. Success bodies
follow the approved ApiError-convention deviation: operations without a
meaningful payload return `204 No Content`; validation failures return the
shared field-error shape; other failures return `ApiError` with the statuses
below. Ordering is not applicable to any operation.

| Operation | Required input | Required success value | Required errors |
| --- | --- | --- | --- |
| `POST register` | e-mail, password | 204; confirmation mail sent | 400 invalid input; 409 e-mail exists; 502 confirmation delivery failed |
| `POST login` | e-mail, password | 204 + session cookie | 400 invalid input; 401 bad credentials; 403 e-mail not confirmed; 429 locked out |
| `POST logout` | session (+ CSRF) | 204; session cleared | 401 no session; 400 CSRF |
| `POST confirm-email` | userId, token | 204; account confirmed | 400 invalid input or invalid/expired link (indistinguishable) |
| `POST resend-confirmation` | e-mail | 204 always (enumeration-safe) | 400 invalid input (never 502 — enumeration-safe) |
| `POST forgot-password` | e-mail | 204 always (enumeration-safe); reset mail sent if account exists | 400 invalid input (never 502 — enumeration-safe) |
| `POST reset-password` | e-mail, token, new password | 204; password stored, best-effort notification | 400 invalid input or invalid/expired link |
| `GET me` | session | 200 profile (id, email, roles, shippingAddress?, billingAddress?, hasSeparateBillingAddress, createdAt) | 401 |
| `PUT profile` | session + CSRF, shipping address, `hasSeparateBillingAddress`, billing address? | 200 updated profile | 400 invalid input or CSRF; 401 |
| `POST change-email` | session + CSRF, new e-mail, current password | 204; confirmation sent, best-effort notification | 400 invalid input or CSRF; 401 wrong password; 409 e-mail exists; 502 confirmation delivery failed |
| `POST confirm-change-email` | userId, new e-mail, token | 204; login e-mail replaced | 400 invalid input or invalid/expired link; 409 e-mail taken meanwhile |
| `POST change-password` | session + CSRF, current password, new password | 204; best-effort notification | 400 invalid input or CSRF; 401 wrong current password |

Evidence per row: production code paths listed in the behavior matrix; the
only client is the Vue auth store (`auth.ts`), which reads HTTP status plus
(after its planned adaptation) the shared error body.

`confirm-change-email` returning 409 when the target e-mail was taken between
request and confirmation is stricter than the legacy 400 ("invalid or expired
link"): the unique index fires on the update. Recorded in the deviation log.

### 3. Material ambiguities and proposed deviations

No open material ambiguity remains. All observable deviations were approved by
Joe on 2026-07-23 (see decision log); the two remaining minor differences
(explicit `frontendBaseUrl`, 409 on late change-email conflict) use the
idiomatic default and are recorded in the deviation log.

### 4. Kotlin operation interface and production type map

The operation boundary is Ktor-free. Flows whose outcome set matches the
shared variants return `OperationResult<T>`; login, register, change-email,
and change-password have genuinely different outcomes (locked-out,
not-confirmed, wrong password, delivery failed) and use small module-specific
sealed results instead of forcing those into `Conflict` — deliberately not an
extension of the shared `OperationResult`, which would be a cross-module
change needing its own review.

```kotlin
internal interface AccountOperations {
    suspend fun register(input: RegisterInput): RegisterResult
    suspend fun login(input: LoginInput): LoginResult
    suspend fun confirmEmail(input: ConfirmEmailInput): OperationResult<Unit>
    suspend fun resendConfirmation(input: AccountEmailInput): OperationResult<Unit>
    suspend fun forgotPassword(input: AccountEmailInput): OperationResult<Unit>
    suspend fun resetPassword(input: ResetPasswordInput): OperationResult<Unit>
    suspend fun profile(userId: Long): OperationResult<AccountProfile>
    suspend fun updateProfile(userId: Long, input: ProfileInput): OperationResult<AccountProfile>
    suspend fun changeEmail(userId: Long, input: ChangeEmailInput): ChangeEmailResult
    suspend fun confirmChangeEmail(input: ConfirmChangeEmailInput): OperationResult<Unit>
    suspend fun changePassword(userId: Long, input: ChangePasswordInput): ChangePasswordResult
}
```

Mapping notes: invalid/expired links become `Invalid` (→ 400) so the cause
stays indistinguishable; the change-email confirm maps a unique violation to
`Conflict` (→ 409). Logout needs no operation — the route clears the session.
`LoginResult.SignedIn` carries `userId: Long` and `roles`; the route (the
only Ktor-aware layer) creates the platform `UserSession` from it, converting
the id to the session's string form.

Planned production types (one per file):

| Type | Kind | Justification (deletion test) |
| --- | --- | --- |
| `AccountModule` (+ `createAccountModule`, `installAccountModule`, `validateAccountRequests`) | handle | required runtime-handle convention |
| `AccountRoutes` | internal object | thin route → HTTP mapping |
| `AccountOperations` | internal interface | operation boundary |
| `AccountService` | internal class | validation, normalization, orchestration, mail policy |
| `AccountRepository` | internal class | Exposed access to all three tables |
| `Users`, `UserRoles`, `AccountTokens` | Exposed tables | schema ownership |
| `UserAccount` | internal data class | stored row incl. hash + lockout state; never serialized |
| `AccountProfile` | serializable | the one response representation (`me` + `profile`) |
| `Address` | serializable | shared shipping/billing value in input and output; owns the address field rules |
| `RegisterInput`, `LoginInput`, `ConfirmEmailInput`, `AccountEmailInput`, `ResetPasswordInput`, `ProfileInput`, `ChangeEmailInput`, `ConfirmChangeEmailInput`, `ChangePasswordInput` | serializable `Validatable` inputs | nine distinct external contracts; `AccountEmailInput` is shared by resend-confirmation and forgot-password (same field, same rule); `RegisterInput` and `LoginInput` stay separate because login must not shape-validate the password |
| `RegisterResult`, `LoginResult`, `ChangeEmailResult`, `ChangePasswordResult` | internal sealed | module-specific outcome sets (see above) |
| `UserWriteResult` | internal sealed | persistence outcomes `Stored`/`EmailTaken` via `executePostgresWrite` |
| `AccountTokenPurpose` | internal enum | `CONFIRM_EMAIL` / `RESET_PASSWORD` / `CHANGE_EMAIL` |
| `PasswordHasher` | internal class | PBKDF2 hash/verify with versioned, parameter-carrying encoding |
| `AccountSettings` | data class | `frontendBaseUrl` (required, HTTPS outside local), `pbkdf2Iterations` (default 600 000) |

That is more than the 12-type review signal — expected for twelve HTTP
operations against Country's five, and driven almost entirely by the nine
mandated input contracts. The post-migration simplification review applies
the deletion test to every type, with the four sealed results and the shared
`Address` first in line.

### 5. Runtime composition design

```kotlin
public fun Application.installAccountModule(
    database: Database,
    settings: AccountSettings,
    userEmails: UserEmailSender,
    clock: Clock = Clock.systemUTC(),
): Unit
```

- The handle and factory stay `internal` (Supplier/Pricing pattern): no other
  module needs the assembled instance, and the module exports no capability —
  the Cart migration adds its claim hook seam later; nothing speculative now.
  An `internal` `installAccountModule(operations)` overload is the route test
  seam. `AccountSettings` and the install/validate functions are the only
  `public` surface.
- The injected `java.time.Clock` (approved 2026-07-23) drives token expiry,
  lockout, and `created_at`. Platform session timestamps keep using real time;
  session mechanics are already platform-tested.
- Session bridge: login converts `users.id` to the string
  `UserSession.userId`; authenticated routes parse it back with
  `toLongOrNull()` (same pattern as MagicCoins' owner resolution).
- Authenticated-route protection: the platform gains a public
  `Route.installAuthenticatedRouteProtection()` next to
  `installAdminRouteProtection` — same fail-closed plugin shape, but requiring
  only an authenticated `UserPrincipal` (no role) plus CSRF on mutating
  methods. This keeps the policy auth-owned instead of hidden in the account
  module. The account module wraps `me`, `profile`, `change-email`,
  `change-password`, and `logout` in that subtree; the anonymous endpoints
  stay outside it.
- Error bodies: authentication-layer rejections (401/403/400-CSRF) keep the
  platform's existing responses; account operation failures use the shared
  `ApiError`/validation shapes via the normal route mapping.
- Email runtime composition (inherited, see section 6): the composition root
  loads `EmailSettings`, creates the app-owned `AggregatedQueuedEmailSource`,
  calls `installEmailModule(database, emailSettings, source)` once, passes
  `EmailModule.userEmails` to the account module, hands `EmailModule.outbox`
  to the production module (switching the composition root to the
  outbox-wired production installation), and binds
  `ProductionModule.producerNotifications` on the aggregate. The worker may
  start with this composition: every enqueueable reference kind then
  resolves, and an unbound moment is covered by the source's retryable
  `SOURCE_UNAVAILABLE` behavior.

### 6. Application composition and Flyway changes

- New Flyway migration `V11__create_users.sql`: `users` (identity, e-mail +
  unique normalized index, `email_confirmed`, `password_hash`, `created_at`,
  lockout columns, flat shipping/billing address columns,
  `has_separate_billing_address`), `user_roles`, `account_tokens`, plus the
  deferred foreign key `magic_coins.user_id → users.id` (`ON DELETE CASCADE`
  as in the legacy schema).
- Email runtime composition: `Application.kt` does not install the email
  module yet, and `email-post-migration.md` ("Application runtime
  composition") assigns that work to the first migration that needs Email at
  runtime — this one. The composition root must load `EmailSettings`, call
  `installEmailModule` exactly once with the app-owned aggregated
  `QueuedEmailSource` (the production resolver already exists), and pass only
  `EmailModule.userEmails` to the account module. Whether the queued worker
  can start with the composed sources, or direct delivery must be split from
  worker startup through the explicit seam, is decided in the design step.
- `Application.kt`: `installAccountModule(...)` wired with the database and
  `UserEmailSender`; `validateAccountRequests()` in the shared
  RequestValidation block.
- Configuration: `frontendBaseUrl` becomes account-module configuration (per
  `email-post-migration.md`, not an Email setting): required at startup,
  HTTPS outside local environments; the module builds and percent-encodes the
  complete confirmation/reset/change-email URLs before constructing
  `EmailActionUrl`.

### 7. Test plan

Seams approved by Joe on 2026-07-23: route-level Ktor tests with
Testcontainers PostgreSQL, a recording `UserEmailSender` fake (token links
are extracted from recorded mails, never read from the database), an injected
mutable clock, reduced PBKDF2 iterations via `AccountSettings`, pure
validator tests, and the Flyway schema test.

| Test class | Level | Covers |
| --- | --- | --- |
| `AccountInputValidationTest` | pure | complete field-rule matrix of all nine inputs: shared password rule (register/reset/change), e-mail formats, address max lengths, two-letter country, phone pattern, required flags; login does not shape-validate the password |
| `PasswordHasherTest` | pure | hash/verify roundtrip, wrong password, tampered encoding, unknown version rejected, iteration count read from the encoding |
| `AccountServiceIntegrationTest` | service + PostgreSQL | registration incl. role row; duplicate and concurrent-duplicate registration (unique index authority); token lifecycle — single use, 24h expiry via clock, reissue invalidates prior tokens of the same purpose; lockout — 15 failures lock, expiry via clock unlocks, success resets; profile replace semantics — billing cleared when flag off, blank phone → null, normalization after validation; change-email cycle incl. late-conflict → `Conflict`; enumeration-safe branches and best-effort notifications with a failing sender; required-mail failure → delivery-failed outcomes; `CancellationException` rethrown; unexpected SQL failure → `UnexpectedFailure` |
| `AccountRouteSecurityAndValidationTest` | route (fake operations) | rejected requests never reach the operation: 401 without session on `me`/`profile`/`change-email`/`change-password`/`logout`; 400 on missing/invalid CSRF for the four mutations; 400 invalid bodies via shared RequestValidation; GET `me` needs no CSRF |
| `AccountFlowIntegrationTest` | route + PostgreSQL + recording sender | full journeys over HTTP: register → confirm via mailed link → login → `me` → profile update → change-password (session stays valid) → change-email → confirm → login with new e-mail; login error paths 401 (uniform for both causes), 403 unconfirmed, 429 locked; logout ends the session; forgot/reset via mailed link; resend for unconfirmed only; 502 when required delivery fails; expired/reused links → 400 |
| `AccountSchemaIntegrationTest` | Flyway + PostgreSQL | migration on an empty database; `users` unique e-mail (case-insensitive); `account_tokens` constraints; `magic_coins.user_id` FK with cascade delete |
| Application composition test (extend existing app tests) | app | email module installed exactly once, `userEmails` reaches account, outbox reaches production, producer notifications bound, worker lifecycle stops on shutdown, startup fails on missing `frontendBaseUrl` |

The mutable clock lives in the account test sources unless `test-support`
already offers one; promotion to `test-support` only when a second module
needs it (guide rule against speculative shared infrastructure).

### 8. Deferred work and its owner

See "Explicitly deferred work" under task parameters.

## Decision log

### 2026-07-23 — Pre-analysis decisions (Joe)

Joe approved before the record was created:

- Module name `account`, package `shop.voenix.account`.
- Responses follow the shared ApiError conventions instead of the legacy
  `AuthResponse` envelope; the frontend will be adapted (deferred work).
- CSRF is extended to authenticated auth mutations; frontend adaptation is
  deferred work with Joe as owner.
- JDK PBKDF2-HMAC-SHA256 password hashing (~600k iterations).
- Persisted single-use `account_tokens` instead of stateless DataProtection
  tokens; 24h lifetime preserved.
- Lockout preserved as 15 attempts / 10 minutes / 429 with explicit columns.
- Addresses stay flat columns on `users`; one shared address value object in
  code.
- First `ADMIN` user via documented manual SQL; no role seeding.
- Change-password does not invalidate existing sessions (matches legacy).
- Guest-data claim deferred to the Cart migration.

### 2026-07-23 — Analysis revised against the Email migration's Auth contract

The first analysis draft assumed account mails go through the Email outbox.
The "Auth email composition" section in `email-post-migration.md` (decided
during the Email migration) requires direct `UserEmailSender` delivery with
no `email_jobs` rows, module-owned URL building and `frontendBaseUrl`, 502
for failed required deliveries, and silent best-effort behavior wherever
enumeration safety applies. The behavior matrix, contract table, and
composition plan were corrected accordingly. This migration also inherits the
still-open Email runtime composition in `Application.kt`.

### 2026-07-23 — Analysis checkpoint

Behavior matrix and contract table completed; status set to
`awaiting-approval`. Implementation, type map, and test plan wait for Joe's
approval of this analysis.

### 2026-07-23 — Analysis approved

Joe approved the revised analysis (including the email-composition
corrections and both post-migration files). Before implementation, a spec is
created as a GitHub issue via the to-spec skill.

### 2026-07-23 — Spec published and test seams approved

Spec: [issue #5](https://github.com/bitstorm-tech/voenix-shop-kotlin/issues/5)
(labelled `ready-for-agent`). Joe approved the test seams: route-level Ktor
tests with Testcontainers PostgreSQL, a recording `UserEmailSender` fake
(token links extracted from recorded mails, never from the database), pure
validator tests, and the Flyway schema test. Two additions approved: a clock
injected into `createAccountModule` (system clock default) for expiry and
lockout tests, and a configurable PBKDF2 iteration count in the account
settings (production default ~600k) so tests stay fast.

### 2026-07-23 — Design artifacts completed

Type map, runtime composition design, and test plan recorded (sections 4, 5,
7). Two findings from reading the platform implementation:

- CSRF rejections answer 400 (`AuthModule.requireCsrf`), not 403; the
  contract table and matrix were corrected. This matches the legacy ASP.NET
  antiforgery status, so it is not a deviation.
- The platform needs a public `installAuthenticatedRouteProtection()`
  (authenticated + CSRF, no role requirement) next to the existing admin
  variant, so the fail-closed policy stays auth-owned. This is the one
  planned platform change.

Status set to `awaiting-approval` for the design.

### 2026-07-23 — Preparatory work split into blocker issues

Joe decided to keep issue #5 as one vertical slice and split off the two
non-account parts as independent tickets, linked as native GitHub blockers
of #5:

- [#6 — Compose the email runtime in the application root](https://github.com/bitstorm-tech/voenix-shop-kotlin/issues/6)
  (inherited from the Email/Production migrations; section 6's email bullet
  is delivered there).
- [#7 — Platform: fail-closed route protection for authenticated routes](https://github.com/bitstorm-tech/voenix-shop-kotlin/issues/7)
  (the platform change from section 5).

The application-composition row of the test plan moves with #6; the account
flow tests in #5 then consume the already-composed email runtime.

## Deviation and uncertainty log

| Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| `AuthResponse { success, message, code }` envelope | `AuthResponse.cs`; frontend `postAuth` | Shared ApiError conventions; `EMAIL_NOT_CONFIRMED` becomes the 403 status | Approved deviation | Joe, 2026-07-23 | Frontend adaptation (deferred work) |
| Auth endpoints exempt from CSRF | `MutationAntiforgeryConvention.cs` | CSRF required on authenticated mutations | Approved deviation | Joe, 2026-07-23 | Frontend sends `X-XSRF-TOKEN` (deferred work) |
| Stateless DataProtection tokens | `AddDefaultTokenProviders` | Persisted single-use `account_tokens`, 24h | Approved deviation | Joe, 2026-07-23 | none |
| Identity PBKDF2 V3 hash format | Identity default hasher | JDK PBKDF2-HMAC-SHA256, ~600k iterations, versioned encoding | Approved deviation | Joe, 2026-07-23 | none |
| Identity schema ballast (claims, logins, 2FA, stamps, `user_tokens`) | `VoenixDbContext.cs`, unused by endpoints | Not migrated | Approved deviation | Joe, 2026-07-23 | none |
| Role seeding at startup | `Program.cs:102-110` | No seeding; text roles; manual SQL for first `ADMIN` | Approved deviation | Joe, 2026-07-23 | Document snippet in `docs/dev` |
| Frontend link base falls back to request host | `AuthController:347-353` | Explicit configuration required; fail fast when missing | Incidental (idiomatic default) | Guide default | none |
| Late change-email conflict answered 400 "invalid link" | `ChangeEmailAsync` failure path | 409 conflict from the unique index at confirm time | Proposed deviation (minor, stricter) | Recorded here; implicitly covered by ApiError approval | Confirm during design review |
| Guest data claim on login/registration | `GuestDataClaimService.cs` | Not implemented in this module | Required, deferred | Cart migration | Includes the unclaimed-MagicCoins gap |
| MagicCoins FK `ON DELETE CASCADE` | `MagicCoinsBalanceConfiguration.cs` | FK added by `V11` with cascade | Required | this migration | none |

## Migration retrospective

Pending — completed before the final completion report.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| _pending_ | | | | |
