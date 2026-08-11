# Backend Account package

This guide explains the Kotlin code in
[`backend/modules/account/src/shop/voenix/account`](../../../backend/modules/account/src/shop/voenix/account).

## What this package does

The Account package gives the shop real user accounts. Visitors register with
e-mail and password, confirm their address via a mailed link, and sign in to a
cookie session. Customers manage their profile (shipping address, optional
separate billing address), change their e-mail with re-confirmation, change
their password, and recover access with a password reset link.

The package is the **trusted credential verifier** the platform auth module
was still missing: a successful login is the one production code path that
creates a `UserSession`. Session mechanics, CSRF, and the fail-closed route
protections stay platform-owned and are reused, never reimplemented (see
[`authentication-and-authorization.md`](authentication-and-authorization.md)).

The package touches nothing outside the account itself. A login and a
registration store, verify, and mail — they never move another module's rows
and never mint or renew the visitor's `voenix.guest` cookie (issue #110).

The migration decisions behind this package are recorded in
[`account-migration.md`](../../migration/account-migration.md); the remaining
deferred follow-ups (frontend adaptation; the guest-data claim that record
still tracks was removed again by issue #110) live in
[`account-post-migration.md`](../../migration/account-post-migration.md).

## Package structure

The root package `shop.voenix.account` holds the orchestration surface a
reader reaches for first: the module wiring (`AccountModule`), the HTTP layer
(`AccountRoutes`), the `AccountOperations` seam and its `AccountService`
implementation, the operation-result types, the profile and domain values
(`AccountProfile`, `UserAccount`, `Address`, `UserRoles`), and the
cross-cutting helpers (`PasswordHasher`, `AccountMailer`, `AccountSettings`).

The rest is grouped by responsibility:

| Package | Responsibility |
| --- | --- |
| `shop.voenix.account.api` | The request DTOs sent by the frontend (all `@Serializable`) and the `AccountFieldRules` that validate them. |
| `shop.voenix.account.persistence` | The Exposed tables (`Users`, `AccountTokens`), the `AccountRepository`, and its `UserWriteResult`. |

These packages organize the implementation; they are not separate Kotlin
modules. The `account` compilation module remains the actual visibility
boundary, so its `internal` declarations collaborate across all three packages
but cannot be imported by other modules.

## The five-minute mental model

```mermaid
flowchart TB
    Client["Shop frontend"]
    Http["HttpRuntime<br/>JSON · StatusPages"]
    Validation["Shared RequestValidation<br/>validateAccountRequests()"]
    Routes["AccountRoutes<br/>HTTP mapping · session create/clear"]
    Protection["installAuthenticatedRouteProtection()<br/>platform capability"]
    Operations["AccountOperations<br/>internal seam"]
    Service["AccountService<br/>orchestration · tokens · mail policy · lockout"]
    Hasher["PasswordHasher<br/>PBKDF2-HMAC-SHA256"]
    Mail["UserEmailSender<br/>email module capability"]
    Repository["AccountRepository<br/>Exposed · atomic token consumption"]
    Tables[("PostgreSQL<br/>users · user_roles · account_tokens")]

    Client --> Http --> Validation --> Routes
    Routes --> Protection
    Routes --> Operations
    Operations --> Service
    Service --> Hasher
    Service --> Mail
    Service --> Repository
    Repository --> Tables
```

A clock is injected into the module (`java.time.Clock`, system clock in
production). Token expiry, lockout release, and the stored creation timestamp
all read this clock, which is why the integration tests can travel through
time instead of sleeping.

## HTTP API

All routes stay under `/api/auth` because the existing frontend calls these
paths. Mutations without a meaningful payload answer `204 No Content`;
failures use the shared `ApiError` shape (an approved deviation from the
legacy `{ success, message, code }` envelope — the frontend follow-up is
recorded in the post-migration list).

Anonymous endpoints:

| Method and path | Success | Failure notes |
| --- | --- | --- |
| `POST /api/auth/register` | `204`, confirmation mail sent | `400` invalid input, `409` e-mail exists, `502` the provider did not accept the confirmation mail, `500` a failure of our own |
| `POST /api/auth/login` | `204` + session cookie | `400` invalid input, `401` bad credentials (uniform), `403` e-mail not confirmed, `429` locked out |
| `POST /api/auth/confirm-email` | `204` | `400` invalid input, `400` + `"code": "INVALID_LINK"` for an invalid/expired link |
| `POST /api/auth/resend-confirmation` | always `204` | only `400` invalid input — enumeration-safe |
| `POST /api/auth/forgot-password` | always `204` | only `400` invalid input — enumeration-safe |
| `POST /api/auth/reset-password` | `204` + notification mail | `400` invalid input, `400` + `"code": "INVALID_LINK"` for an invalid/expired link |
| `POST /api/auth/confirm-change-email` | `204`, login e-mail replaced | `400` + `"code": "INVALID_LINK"` for an invalid/expired link, `409` e-mail taken meanwhile |

The three link flows — confirm-email, reset-password, confirm-change-email —
answer an invalid or expired link with `400` and the machine-readable
`"code": "INVALID_LINK"` in the `ApiError` body, so the frontend can show
link-specific copy without matching on the message text. The code says nothing
about *why* the link failed: an unknown, an already-used, and an expired token
are still indistinguishable to the caller. Validation failures on the same
endpoints carry no `code`.

Authenticated endpoints (session required; mutations additionally require the
`X-XSRF-TOKEN` header — stricter than the legacy app, an approved deviation):

| Method and path | Success | Failure notes |
| --- | --- | --- |
| `GET /api/auth/me` | `200` profile | `401` |
| `PUT /api/auth/profile` | `200` updated profile | `400`, `401` |
| `POST /api/auth/change-email` | `204`, confirmation to the new address, notification to the old | `400`, `401` wrong password, `409`, `502` the provider did not accept the confirmation mail, `500` a failure of our own |
| `POST /api/auth/change-password` | `204` + notification mail | `400`, `401` wrong current password |
| `POST /api/auth/logout` | `204`, session cleared | `401`, `400` CSRF |

The profile representation is one type, `AccountProfile`, returned by both
`me` and `profile`: id, e-mail, roles, optional shipping and billing
`Address`, `hasSeparateBillingAddress`, and the ISO-8601 creation timestamp.

## Security behavior worth knowing

- **Uniform login failures.** Unknown e-mail and wrong password produce the
  same `401` response, and the service verifies a placeholder hash for
  unknown users so both paths cost a hash comparison. Account existence is
  not observable.
- **Enumeration-safe flows.** `resend-confirmation` and `forgot-password`
  always answer `204`. A failed mail delivery — or even a database error —
  after validation is logged and never changes the response.
- **Lockout.** 15 failed logins lock the account for 10 minutes (`429`).
  Locking resets the counter (Identity semantics), a successful login resets
  it too, and the increment runs under `SELECT … FOR UPDATE` so concurrent
  failures cannot lose an attempt.
- **Single-use tokens.** Confirmation, reset, and change-e-mail links carry a
  random token whose SHA-256 hash is stored in `account_tokens`. A token is
  valid for 24 hours, consuming it deletes the row, and issuing a new token
  replaces the previous one of the same purpose — the database enforces this
  with a unique `(user_id, purpose)` rule, so only the latest link counts.
- **Password hashing.** `PasswordHasher` uses JDK PBKDF2-HMAC-SHA256 with a
  versioned encoding (`v1$<iterations>$<salt>$<hash>`). Verification reads
  the iteration count from the encoding, so the configured work factor
  (`account.pbkdf2Iterations`, production default 600 000) can change without
  invalidating stored hashes, and tests can run fast without weakening
  production.
- **Changing the password keeps sessions valid.** Platform cookie sessions
  are self-contained, so neither the current nor other sessions are logged
  out — this matches the legacy behavior and is documented in the migration
  record.

## Account mails

Account mails are sent **directly** through the email module's
`UserEmailSender` capability — never queued as `email_jobs` rows. The rules
come from the Email migration's Auth contract
([`email-post-migration.md`](../../migration/email-post-migration.md)):

- Required deliveries (registration confirmation, change-e-mail confirmation)
  surface a failure as `502`; the customer retries via the resend flow.
- `502` means *the provider*, and only the provider. `AccountMailer` catches
  the email module's `EmailDeliveryException` and nothing wider, so only that
  one exception becomes a delivery result. Anything else a send can throw — a
  rendering failure, a malformed link, any other bug of ours — is not caught
  there. It reaches the service's `databaseOperation` guard, which logs it and
  returns the operation's `UnexpectedFailure`, and the route answers a plain
  `500 Internal server error`. The response carries no exception text, no
  recipient address, and no provider detail; the stack trace stays in the
  server log. The distinction matters to the customer: `502` says "try the
  resend", `500` says "this cannot succeed until we fix it".
  Because the catch is narrow, cancellation needs no special handling in these
  two paths — a `CancellationException` is not an `EmailDeliveryException`, so
  it simply passes through.
- Best-effort notifications (password changed, e-mail change notice) are
  logged on failure and never fail the operation — deliberately including our
  own bugs, which is why their `catch` stays broad (with an explicit
  `CancellationException` rethrow). The password change or address change they
  announce is already stored; a broken notification must not undo it.
- The module builds and percent-encodes the complete links itself:
  `{frontend.baseUrl}/confirm-email?userId=…&token=…`,
  `{frontend.baseUrl}/reset-password?email=…&token=…`, and
  `{frontend.baseUrl}/confirm-change-email?userId=…&newEmail=…&token=…`.

`frontend.baseUrl` is required at startup and must be HTTPS outside local
environments (`localhost` may use HTTP). It is one application-wide setting,
not an account-specific one: the order confirmation builds its permanent order
link from the same value (issue #110). The rules live in the platform value type
`FrontendBaseUrl`; the account module only receives it.

## Persistence

Flyway migration
[`V11__create_users.sql`](../../../backend/modules/platform/resources/db/migration/V11__create_users.sql)
creates three tables:

- `users` — the e-mail doubles as the login name; a unique index on
  `LOWER(email)` makes uniqueness case-insensitive and concurrency-safe.
  Address fields are flat columns; `has_separate_billing_address` controls
  whether billing fields are used. Lockout state lives in
  `failed_login_count` and `locked_until`.
- `user_roles` — plain text roles (`ADMIN`, `CUSTOMER`), primary key
  `(user_id, role)`. No ASP.NET Identity ballast (no claims, external
  logins, 2FA, phone columns, or stamps) was migrated.
- `account_tokens` — SHA-256 token hash, purpose, optional pending new
  e-mail, expiry; unique per `(user_id, purpose)`.

The migration also adds the foreign key `magic_coins.user_id → users.id`
(`ON DELETE CASCADE`) that the MagicCoins migration deliberately deferred.

As everywhere in this backend, the database is the authority for uniqueness:
repositories map SQL state `23505` to a typed conflict via
`executePostgresWrite` and never inspect constraint names (see
[`persistence-error-handling.md`](persistence-error-handling.md)). The
change-e-mail confirmation consumes the token and updates the e-mail in one
transaction, so a unique violation rolls the token consumption back.

## Bootstrapping the first administrator

The application seeds no roles and no users. To grant the first `ADMIN` role
on a fresh deployment, register the account normally (registration always
assigns `CUSTOMER`), confirm the e-mail, and then add the role directly in
the database:

```sql
INSERT INTO users_schema.user_roles (user_id, role)
SELECT id, 'ADMIN'
FROM users_schema.users
WHERE LOWER(email) = LOWER('admin@example.com')
ON CONFLICT DO NOTHING;
```

Replace `users_schema` with the configured search path (the default is
`voenix`) and the e-mail with the administrator's address. The role becomes
effective on the next login because the session stores its roles when it is
created.

## Runtime composition

`Application.kt` wires the module once:

```kotlin
val userEmails = installEmailRuntime(database, emailSettings, productionSettings, source)
installAccountModule(database, accountSettings, userEmails)
```

That is the whole wiring: a database, the settings, and the platform's user
mail sender. The module needs no guest token, because it neither reads nor
writes the `voenix.guest` cookie, and it has no ordering requirement against
the cart or the order module.

`AccountModule`, `createAccountModule`, and the operations-based
`installAccountModule` overload follow the standard runtime-handle
convention ([`module-architecture.md`](module-architecture.md)). The handle
and factory are `internal`: no other module needs the assembled instance, and
the package exports no capability — it consumes one. `AccountSettings`,
`installAccountModule(database, …)`, and `validateAccountRequests()` are the
only public surface.

## Tests

- `AccountInputValidationTest`, `PasswordHasherTest` — pure unit tests for
  the complete field-rule matrix and the hash encoding.
- `AccountServiceIntegrationTest` — service against real PostgreSQL:
  registration, token lifecycle, lockout with a mutable clock, profile
  replace semantics, change-e-mail incl. late conflicts, failing-sender
  behavior.
- `AccountRouteSecurityAndValidationTest` — rejected requests (no session,
  bad CSRF, invalid bodies) never reach the operation.
- `AccountFlowIntegrationTest` — full journeys over HTTP; confirmation and
  reset links are extracted from the recorded mails, never read from the
  database. One of its journeys pins that neither a registration nor a login
  answers with a `voenix.guest` cookie.
- `AccountSchemaIntegrationTest` — the Flyway migration on an empty database
  and its constraints.
