# Auth, CSRF, and Admin Route

## Result

The Kotlin port now has a proof-only Ktor auth route set backed by Postgres:

- `POST /auth/login` validates email/password against `auth_users`, requires confirmed email, sets a signed cookie session, and returns an `X-CSRF-Token` header.
- `POST /auth/logout` clears the cookie session.
- `GET /admin/proof` requires an authenticated admin user.
- `POST /admin/proof` requires authenticated admin role plus matching `X-CSRF-Token`.

This is intentionally not a broad auth subsystem port. It proves the integration points: Ktor sessions/auth, DB-backed credential lookup, role checks, CSRF validation, and lockout-shaped user state.

## User State Shape

`auth_users` includes:

- `email`
- `password_hash`
- `role`
- `email_confirmed`
- `password_reset_token`
- `password_reset_expires_epoch_seconds`
- `access_failed_count`
- `lockout_end_epoch_seconds`

Login rejects unconfirmed users, locked users, bad passwords, and missing users with the same `401` result. Bad passwords increment `access_failed_count` and set a 15 minute lockout after 5 failed attempts.

## ASP.NET Identity Replacement Recommendation

Do not translate ASP.NET Identity directly.

Recommended shape for the Kotlin port:

- Keep Ktor authentication/session plumbing thin.
- Put auth rules in an application-owned user service.
- Use a vetted password hashing library for production, preferably Argon2id or bcrypt, not the proof PBKDF2 helper.
- Model roles/claims explicitly per current product needs.
- Store email confirmation, reset token, failed login count, and lockout expiry as first-class user fields.
- Use Testcontainers for auth integration tests because cookie auth, migrations, and persistence behavior need to be proven together.

Revisit later:

- Session storage: signed client cookie vs server-side session storage.
- CSRF delivery: header from login is enough for the proof; real UI should use a consistent hidden-field/header pattern.
- Token lifecycle: reset token hashing, single-use enforcement, expiry cleanup.
- Lockout policy: thresholds, duration, audit trail, and admin unlock behavior.
