# Authentication and authorization

This guide explains how the Kotlin backend decides **who a caller is**, **what
that caller may do**, and **whether a state-changing request is safe to
process**. It is written for developers who are still learning Kotlin and Ktor.

Application-wide authentication lives in
[`shop.voenix.auth`](../../../backend/modules/platform/src/shop/voenix/auth). Shared JSON and
exception-to-response behavior lives in
[`shop.voenix.http`](../../../backend/modules/platform/src/shop/voenix/http). Module routes use
those modules but do not implement their security behavior.

The current HTTP runtime uses normal canonical Ktor routes and a small shared
JSON error for CSRF failures. The auth cookie format, session lifetime, exact
role rule, token checks, cookie flags, key derivation, and configuration keys
remain unchanged.

## Three terms that sound similar

- **Authentication** answers: "Who is making this request?" Ktor reads an
  encrypted session cookie and creates a `UserPrincipal`.
- **Authorization** answers: "May that user use this endpoint?" The current
  admin policy requires the exact role string `ADMIN`.
- **CSRF protection** answers: "Did the signed-in user intentionally make this
  state-changing request?" Admin writes require an additional token in a
  request header.

Authentication happens before authorization. CSRF protection is a separate
check after both of them.

## Where sessions come from

The auth module **validates and uses** a `UserSession` but never verifies
credentials itself, and it does not query a user database during
authentication. The trusted component that verifies credentials and creates
`UserSession` values is the Account package: a successful
`POST /api/auth/login` is the one production code path that sets the session
(see [`account-package.md`](account-package.md)). The session stores the
user's numeric database id as a string plus the roles granted at login time.

The `/test/sign-in` endpoints found in tests are fixtures. They create a session
directly so a test can exercise protected routes. They are not installed by
[`Application.kt`](../../../backend/app/src/shop/voenix/Application.kt) and must not
be copied into production code.

## The five-minute mental model

```mermaid
flowchart LR
    Request["HTTP request"]
    Match["Ktor matches<br/>a canonical route"]
    Cookie["AuthModule reads and decrypts<br/>voenix.auth"]
    Principal["Create UserPrincipal"]
    Role["AdminRouteProtection<br/>requires ADMIN"]
    Csrf["For writes: AdminRouteProtection<br/>validates X-XSRF-TOKEN"]
    Handler["Route handler<br/>IDs · body · operation"]

    Request --> Match
    Match --> Cookie
    Cookie -->|"missing, invalid, or expired"| Unauthorized["401 Unauthorized"]
    Cookie -->|"valid"| Principal
    Principal --> Role
    Role -->|"ADMIN missing"| Forbidden["403 Forbidden"]
    Role -->|"ADMIN present"| Csrf
    Csrf -->|"write with bad token"| BadRequest["400 Bad Request"]
    Csrf -->|"read, or valid token"| Handler
```

For a matched protected module route that writes data, the detailed order is:

```text
authenticated session -> ADMIN role -> CSRF -> path-value conversion
                      -> JSON binding -> RequestValidation -> operation
```

Steps that do not apply are skipped. A create has no ID to convert, and an admin
read has no CSRF or request body.

Order matters. An anonymous `POST` with invalid JSON receives `401`; the body is
not bound first. The ordinary `/{id}` route matches a value such as
`not-a-number`, so authentication and role checks run before the route handler
converts it to `Long`. An admin then receives `400 Invalid country id` without
a country operation running.

A non-canonical path does not match a route at all. Paths are case-sensitive and
an extra trailing slash is not accepted.

## How startup is divided

Startup begins in
[`Application.module`](../../../backend/app/src/shop/voenix/Application.kt). It
loads database and auth settings, connects the database, and installs three
separate concerns:

```kotlin
installHttpRuntime()
install(RequestValidation) {
    validateCountryRequests()
    validateVatRequests()
    validateSupplierRequests()
    validatePricingRequests()
}
installAuthModule(authSettings)
val countries = installCountryModule(database)
val vats = installVatModule(database)
installSupplierModule(database, countries)
installPricingModule(database, vats)
```

The ownership is visible in that order:

1. [`HttpRuntime`](../../../backend/modules/platform/src/shop/voenix/http/HttpRuntime.kt)
   installs application-wide JSON Content Negotiation and `StatusPages`.
2. [`AuthModule`](../../../backend/modules/platform/src/shop/voenix/auth/AuthModule.kt)
   installs sessions, authentication, renewal, and the antiforgery endpoint.
3. The module installation functions create their internal services and
   install only their module routes. Country and VAT return narrow reader
   capabilities that the app passes to Supplier and Pricing.

A focused test application that uses protected module routes calls
`installHttpRuntime()` and `installAuthModule(...)` explicitly before
installing the product module.

## The public auth interface

Protected modules use the small auth-owned routing interface:

- `installAuthModule(settings)` constructs and installs that handle into the
  receiving Ktor application;
- `AuthRouting.PROVIDER` is the Ktor authentication-provider name used by
  `authenticate(...)`;
- `AuthRouting.CSRF_HEADER` is the established `X-XSRF-TOKEN` header name;
- [`installAdminRouteProtection()`](../../../backend/modules/platform/src/shop/voenix/auth/AdminRouteProtection.kt)
  is called on an authenticated admin route. It enforces the exact `ADMIN`
  policy and automatically validates CSRF for `POST`, `PUT`, `PATCH`, and
  `DELETE` requests.
- [`installAuthenticatedRouteProtection()`](../../../backend/modules/platform/src/shop/voenix/auth/AuthenticatedRouteProtection.kt)
  is called on an authenticated route for signed-in users of any kind. It has
  no role requirement: every authenticated user passes. It validates CSRF for
  the same mutating methods as the admin variant.
- [`installGuestCapableRouteProtection()`](../../../backend/modules/platform/src/shop/voenix/auth/GuestCapableRouteProtection.kt)
  is called on a route subtree that serves guests and signed-in users alike. It
  requires no authentication, but it enforces CSRF for the same mutating
  methods. A signed-in caller must still use a token that was issued for that
  user.

All three protections share the same fail-closed core in
[`RouteProtection`](../../../backend/modules/platform/src/shop/voenix/auth/RouteProtection.kt):
a rejected request is answered before the route handler runs, and a request
without the expected principal is rejected with `401` even when the plugin was
installed outside an `authenticate` block by mistake.

Module code does not decrypt cookies, inspect CSRF sessions, compare tokens, or
construct auth rejection payloads. It also does not repeat security guards in
each handler. Those details stay inside the auth module.

## How authentication is installed

The internal `AuthModule.install` function adds four pieces of behavior:

1. `Sessions` reads and writes the authentication and CSRF cookies.
2. `Authentication` turns a valid `UserSession` into a `UserPrincipal`.
3. A sliding-renewal application plugin renews eligible active sessions.
4. `GET /api/antiforgery/token` issues a CSRF token.

The names in this code are useful Ktor vocabulary:

- An **application plugin** adds behavior to Ktor's request pipeline.
- An **authentication provider** describes one authentication strategy. This
  provider is named `voenix-session`.
- A **principal** is the validated identity available to route handlers.

The antiforgery endpoint is a normal Ktor `get` route:

```kotlin
routing {
    get("/api/antiforgery/token") {
        // Create and return a token.
    }
}
```

Only that exact canonical path is supported. For example,
`/API/ANTIFORGERY/TOKEN` and `/api/antiforgery/token/` do not match.

## The three authentication data classes

### `UserSession`: data stored in the auth cookie

[`UserSession.kt`](../../../backend/modules/platform/src/shop/voenix/auth/UserSession.kt) contains:

```kotlin
@Serializable
data class UserSession(
    val userId: String,
    val roles: Set<String>,
    val issuedAtEpochSeconds: Long = Instant.now().epochSecond,
    val expiresAtEpochSeconds: Long = issuedAtEpochSeconds + 24L * 60L * 60L,
) {
    constructor(
        userId: String,
        role: String,
    ) : this(userId = userId, roles = setOf(role))
}
```

For a Kotlin beginner:

- `data class` is intended primarily to hold values. Kotlin generates helpers
  such as `copy`, value-based `equals`, and a readable `toString`.
- `val` makes a property read-only after construction.
- `Set<String>` stores unique role names.
- `@Serializable` allows the value to be converted to and from the cookie's
  serialized representation.
- The second `constructor` is a convenience overload for callers with one role.
- Epoch seconds count seconds since 1970-01-01T00:00:00Z and avoid local-time
  ambiguity.

The primary constructor defaults `issuedAtEpochSeconds` to now and
`expiresAtEpochSeconds` to 24 hours later.

### `UserPrincipal`: identity for one request

[`UserPrincipal.kt`](../../../backend/modules/platform/src/shop/voenix/auth/UserPrincipal.kt)
contains the same identity and lifetime values, but it has a different job. It
exists only after Ktor has accepted the session:

```kotlin
val principal = call.principal<UserPrincipal>()
```

Keeping `UserSession` and `UserPrincipal` separate makes a useful boundary
visible: a cookie contains a **claim**, while a principal is the application's
**validated identity for this request**.

### `CsrfSession`: token and owning user

[`CsrfSession.kt`](../../../backend/modules/platform/src/shop/voenix/auth/CsrfSession.kt) stores:

```kotlin
data class CsrfSession(
    val token: String,
    val userId: String?,
)
```

The nullable type `String?` means `userId` may be a string or `null`. It is
`null` when an anonymous caller requests a token.

## What happens to the auth cookie

The authentication cookie is named `voenix.auth`. On a protected request, Ktor
and `AuthModule` perform these steps:

1. The Sessions plugin reads the cookie.
2. `SessionTransportTransformerEncrypt` verifies and decrypts its value with
   keys derived from `Auth.SessionSecret`.
3. Ktor deserializes the value as a `UserSession`.
4. The `voenix-session` provider checks
   `session.expiresAtEpochSeconds > now`.
5. A valid session is copied into a `UserPrincipal`.
6. An invalid, expired, or missing session triggers the provider's challenge.

The challenge retains the established `401 Unauthorized` `AuthResponse`:

```json
{
  "success": false,
  "message": "Authentication required",
  "code": null
}
```

The cookie is stored by value: identity, roles, and timestamps are inside the
encrypted and signed cookie rather than in a server-side session table.
Encryption prevents a caller from reading its values. Signing prevents a caller
from changing them without knowing the secret.

Encryption and signing key derivation remains compatible with auth cookies
created before authentication was extracted into its own package. Auth and CSRF
use separate cryptographic purposes, so a value created for one purpose cannot
be reused for the other.

## How authorization works

A module's route adapter wraps protected routes with Ktor's authentication
block:

```kotlin
authenticate(AuthRouting.PROVIDER) {
    route("/api/admin/countries") {
        installAdminRouteProtection()

        // Protected handlers live here.
    }
}
```

The `authenticate` block proves that there is a valid principal.
`AdminRouteProtection` then runs after authentication and before any handler.
It checks whether the exact role `ADMIN` is in the principal's role set.
Matching is case-sensitive: `ADMIN` works, but `admin` does not. A user may have
other roles as well; `{CUSTOMER, ADMIN}` is authorized.

Routes for signed-in users without an admin requirement use the second
protection instead:

```kotlin
authenticate(AuthRouting.PROVIDER) {
    route("/api/account") {
        installAuthenticatedRouteProtection()

        // Handlers for any signed-in user live here.
    }
}
```

`AuthenticatedRouteProtection` skips the role check entirely. An anonymous
request still receives the established `401` response, and mutating requests
still require a valid CSRF token. There is no `403` case because there is no
role to lack.

Installing the protection once on the parent route protects every child route.
This is safer than relying on every handler author to remember the same guard.
If the expected principal is unexpectedly absent, the plugin fails closed with
the established `401` response. The `authenticate` block is still required: it
validates the configured session and creates that principal.

An authenticated user without `ADMIN` retains the established `403 Forbidden`
`AuthResponse`:

```json
{
  "success": false,
  "message": "Admin access required",
  "code": null
}
```

| Status | Meaning |
| --- | --- |
| `401 Unauthorized` | The application could not authenticate the caller. |
| `403 Forbidden` | The caller is authenticated but lacks the required role. |

Despite its historical HTTP name, `401 Unauthorized` is the authentication
failure, while `403 Forbidden` is the authorization failure.

## Which routes require which checks

| Method and path | Session | `ADMIN` role | CSRF token | Owner |
| --- | --- | --- | --- | --- |
| `GET /api/countries` | No | No | No | Country |
| `GET /api/antiforgery/token` | No | No | No | Auth |
| `GET /api/admin/countries` | Yes | Yes | No | Country |
| `GET /api/admin/countries/{id}` | Yes | Yes | No | Country |
| `POST /api/admin/countries` | Yes | Yes | Yes | Country |
| `PUT /api/admin/countries/{id}` | Yes | Yes | Yes | Country |
| `DELETE /api/admin/countries/{id}` | Yes | Yes | Yes | Country |
| `GET /api/admin/vat` | Yes | Yes | No | VAT |
| `GET /api/admin/vat/{id}` | Yes | Yes | No | VAT |
| `POST /api/admin/vat` | Yes | Yes | Yes | VAT |
| `PUT /api/admin/vat/{id}` | Yes | Yes | Yes | VAT |
| `DELETE /api/admin/vat/{id}` | Yes | Yes | Yes | VAT |
| `GET /api/admin/suppliers` | Yes | Yes | No | Supplier |
| `GET /api/admin/suppliers/{id}` | Yes | Yes | No | Supplier |
| `POST /api/admin/suppliers` | Yes | Yes | Yes | Supplier |
| `PUT /api/admin/suppliers/{id}` | Yes | Yes | Yes | Supplier |
| `DELETE /api/admin/suppliers/{id}` | Yes | Yes | Yes | Supplier |

Reads are treated as safe HTTP operations, so admin `GET` requests do not need
a CSRF token. Operations that create, change, or delete data do.

## How CSRF protection works

A browser automatically attaches matching cookies to requests. Without another
check, a malicious site could try to make a signed-in browser send an unwanted
write. CSRF protection requires a secret value that the malicious site cannot
supply in a custom request header.

The client flow is:

1. Call `GET /api/antiforgery/token` after signing in.
2. Read `requestToken` from the JSON response.
3. Keep the cookies returned by the server.
4. Send the token in the `X-XSRF-TOKEN` header on an admin write.

An example token response is:

```json
{
  "requestToken": "a-random-URL-safe-token"
}
```

An example write is:

```http
POST /api/admin/countries HTTP/1.1
Cookie: voenix.auth=...; XSRF-TOKEN=...
X-XSRF-TOKEN: a-random-URL-safe-token
Content-Type: application/json

{"name":"Denmark","countryCode":"DK"}
```

The antiforgery endpoint generates 32 cryptographically random bytes and
encodes them as URL-safe Base64. It returns the token in JSON and stores an
encrypted `CsrfSession` in the `XSRF-TOKEN` cookie.

For a protected write, `AdminRouteProtection` requires all of these:

- an authenticated `UserPrincipal`;
- a readable `CsrfSession` cookie;
- the same user ID in the principal and CSRF session; and
- an `X-XSRF-TOKEN` header equal to the stored token.

`GuestCapableRouteProtection` requires the same CSRF cookie and header, but no
principal: a visitor without any account passes with a token that was issued
anonymously. When the request carries a user session, the stored `CsrfSession`
must belong to that user, so a token minted for somebody else, or before
signing in, is rejected. Guest-capable routes usually sit outside an
`authenticate` block, so the check reads the user through
`currentUserSession()` rather than through the principal.

The token bytes are compared with `MessageDigest.isEqual`, which avoids the
obvious timing differences of a character-by-character early-exit comparison.

A failed check returns `400 Bad Request` as `application/json` using the shared
[`ApiError`](../../../backend/modules/platform/src/shop/voenix/http/ApiError.kt):

```json
{
  "message": "Invalid CSRF token",
  "errors": {}
}
```

The response is deliberately small and contains no internal exception or
request-tracing fields. The auth module writes the entire response, so module
routes cannot accidentally create a different CSRF contract.

The token is bound to a **user ID**, not to one particular authentication
cookie. Consequently:

- a token requested anonymously cannot be used after sign-in;
- switching from one user ID to another invalidates the previous token;
- signing in again as the same user ID does not invalidate it;
- requesting a replacement token invalidates the previous token; and
- the CSRF session has no independent timestamp.

Both auth and CSRF cookies are `HttpOnly`. Browser JavaScript therefore obtains
the CSRF token from the JSON response, not by reading the cookie.

## Cookie settings and session lifetime

[`SameAsRequestCookieTransport.kt`](../../../backend/modules/platform/src/shop/voenix/auth/SameAsRequestCookieTransport.kt)
applies the same transport settings to both cookies:

| Setting | Value | Why it matters |
| --- | --- | --- |
| Name | `voenix.auth` or `XSRF-TOKEN` | Separates authentication and CSRF state. |
| Path | `/` | Sends the cookie to every application route. |
| `HttpOnly` | `true` | Prevents browser JavaScript from reading it. |
| `SameSite` | `Lax` | Limits many cross-site cookie requests. |
| `Secure` | HTTPS requests only | Prevents sending the cookie over plain HTTP when Ktor sees HTTPS. |
| `Max-Age` / `Expires` | Not set | Makes it a browser-session cookie. |

`Secure` is selected from the request's origin scheme as seen by Ktor. Local
HTTP development receives a non-secure cookie; production should use HTTPS and
must pass the correct request scheme to the application.

The absence of browser cookie expiration does not make an auth session valid
forever. The encrypted `UserSession` has its own server-checked expiry:

- a new session lasts 24 hours;
- after more than half of that lifetime has elapsed, any request carrying the
  still-valid session renews it for another 24 hours; and
- an expired session is rejected and is not renewed.

This is a **sliding session**: continued activity moves the expiry forward. The
renewal plugin is application-wide, so a public request can also renew a valid
auth session.

Because roles are read from the cookie and are not reloaded from a database,
role changes and account revocation are not noticed during a session's
lifetime. There is no server-side session revocation list. Rotating the session
secret invalidates every existing cookie at once.

## Session-secret configuration

[`AuthSettings.kt`](../../../backend/modules/platform/src/shop/voenix/auth/AuthSettings.kt)
requires `Auth.SessionSecret` to contain at least 32 UTF-8 bytes. With ordinary
ASCII text, that means at least 32 characters. Startup fails when the setting is
missing, blank, or too short.

[`application.yaml`](../../../backend/app/resources/application.yaml) maps the
setting to this environment variable:

```text
AUTH_SESSION_SECRET
```

Use a cryptographically random production secret, keep it out of source control
and logs, and keep it stable across application instances. All instances need
the same value to accept one another's cookies. Changing it deliberately signs
every current user out because old cookies can no longer be verified and
decrypted.

The secret is used to derive keys; it is never sent to the browser.

## Guest identity

Some shop features, such as the Magic Coins balance, must recognize a visitor
who has no account. The
[`GuestTokens`](../../../backend/modules/platform/src/shop/voenix/auth/GuestTokens.kt)
capability lives next to `AuthModule` and provides that identity:

- `getOrCreate(call)` returns the visitor's guest token. When the request
  carries no readable guest cookie, it generates a random 48-byte token,
  encrypts it, and appends the `voenix.guest` cookie to the response.
- `tryGet(call)` returns the token of an existing readable cookie, or `null`.
  It never appends a cookie, so read-only paths and background lookups do not
  turn a passing visitor into a stored guest.
- `rotate(call)` replaces the cookie of the request with a freshly minted token
  and returns it. When the request carries no readable cookie it changes
  nothing and returns `null`: a rotation renews an existing guest, it never
  creates one.
- The cookie is `HttpOnly`, `SameSite=Lax`, limited to path `/api`, lives for
  30 days, and is `Secure` on HTTPS requests. The browser only ever sees the
  encrypted value; the plain token exists in the backend and the database.
- A tampered or undecryptable cookie is treated exactly like a missing one:
  the visitor becomes a fresh guest instead of receiving an error.

The encryption reuses the session cookies' crypto foundation:
[`SessionCookieEncryption.kt`](../../../backend/modules/platform/src/shop/voenix/auth/SessionCookieEncryption.kt)
derives purpose-specific AES and HMAC keys from the one `Auth.SessionSecret`.
Rotating the secret therefore also turns every visitor into a fresh guest.

### The guest token's lifetime around a login

A successful login rotates the guest token — the account module calls
`rotate(call)` right after the guest-data claim has run, and only when that
claim reported success. The order matters in both directions: the claim still
needs the old token to find the visitor's rows, and once it has run those rows
belong to the customer, so throwing the old token away costs nothing. What it
buys is that the value the visitor browsed with anonymously stops being a handle
on anything: on a shared browser the next person cannot pick up the previous
customer's guest half of an ownership check.

The condition is the other half of the same argument. A claim is best effort and
can fail — the database is busy, a lock times out — and then the rows it did not
move are still reachable through one thing only: that token. Rotating it there
would orphan them forever, so a login whose token-based claim failed keeps the
cookie and the next login claims again. The port says so in its answer:
`GuestDataClaims.claim` returns whether every branch that depends on the guest
token got through.

The cart is the one place where this rotation forced a second change. A cart
used to be identified by its guest token even after a login, so rotating the
token would have orphaned the cart the claim had just moved. Since issue #77 a
signed-in request finds its cart by user id and the token identifies anonymous
carts only — which is what makes the rotation a complete fix for the cart as
well. The [cart package guide](cart-package.md#who-a-cart-belongs-to) describes
the model and the merge that comes with it.

Three more deliberate consequences:

- **Logging out does not clear the cookie.** The logout route clears the
  `UserSession` only. Anonymous continuity of the same browser — a cart started
  before signing in, for instance — is worth keeping, and the rotated token
  points at nothing the customer left behind: their cart is theirs by user id,
  and the token that once identified it was replaced at the login.
- **A guest MagicCoins balance is lost at login.** The balance sits on the old
  token and nothing moves it, because guests cannot buy coins and there is no
  balance merge (see
  [MagicCoins package](magic-coins-package.md#no-balance-merge-when-a-guest-signs-in)).
- **A registration does not rotate.** It signs nobody in — the address has to be
  confirmed first — so no user session begins and the visitor keeps browsing
  with the same token.

Routes that serve both guests and signed-in users resolve the user first and
fall back to the guest token. The public helper `currentUserSession()` in
[`UserSession.kt`](../../../backend/modules/platform/src/shop/voenix/auth/UserSession.kt)
returns the current call's session only while it has not expired, without
requiring the route to sit inside an `authenticate` block. The MagicCoins
balance route was the first consumer; Cart and Generator follow the same
pattern, the Generator through the shared
`ApplicationCall.magicCoinsOwner(guestTokens)` helper.

## Adding another protected route

Install shared HTTP behavior and auth once during application composition.
Product modules then apply the auth interface at their routing seam.

Put related admin routes below one authenticated parent route and install
the admin protection on that parent:

```kotlin
authenticate(AuthRouting.PROVIDER) {
    route("/api/admin/example") {
        installAdminRouteProtection()

        get {
            call.respond(exampleService.load())
        }

        post {
            val input = call.receive<ExampleInput>()
            call.respond(exampleService.create(input))
        }
    }
}
```

The plugin always checks the role. It checks CSRF automatically for `POST`,
`PUT`, `PATCH`, and `DELETE`, before a handler binds a body or calls a service.
Safe `GET` requests do not need a CSRF token.

For routes that any signed-in user may call, use
`installAuthenticatedRouteProtection()` in the same position instead. For a
subtree that guests may use as well, install
`installGuestCapableRouteProtection()` on the parent route without an
`authenticate` block:

```kotlin
route("/api/cart") {
    installGuestCapableRouteProtection()

    get { /* guests and signed-in users */ }
    post { /* still requires a valid CSRF token */ }
}
```

Choose exactly one of the three protections per route subtree.

Declare one canonical route. Do not copy cookie, role, token, or error-response
logic into the module. Do not call the plugin's internal guards from module
handlers. If another real application policy is needed, add an intentional
auth-owned route plugin.

## Shared HTTP behavior

[`HttpRuntime`](../../../backend/modules/platform/src/shop/voenix/http/HttpRuntime.kt) installs
two application-wide Ktor plugins:

1. **Content Negotiation** converts serializable values to and from
   `application/json`. Shared kotlinx.serialization settings include explicit
   nulls, encoded defaults, and ignored unknown input properties.
2. **StatusPages** converts common binding exceptions to the shared `ApiError`:
   unsupported media is `415`, invalid request bodies are `400`, and an
   unexpected exception is a logged generic `500`.

The final exception handler always rethrows `CancellationException`, because
coroutine cancellation must not become an HTTP response.

`HttpRuntime` does not change Ktor path matching. Product and auth modules use
normal case-sensitive routes without optional trailing slashes.

`ApiError` carries a `message`, field `errors`, and an optional machine-readable
`code`. The `code` field is annotated with `@EncodeDefault(NEVER)`, so it
disappears from the JSON body whenever it is `null`: error bodies that do not
set a code look exactly as they did before the field existed.

The `ApiError` shape is shared by CSRF and module HTTP errors. Authentication
and role failures keep `AuthResponse` because those existing client-facing
contracts were not changed.

## Tests that define the behavior

Auth behavior is tested through a small Ktor application rather than through a
country service stub:

| Test | Main responsibility |
| --- | --- |
| [`AuthModuleTest.kt`](../../../backend/modules/platform/test/shop/voenix/auth/AuthModuleTest.kt) | Authentication, exact admin policy, expiry, renewal, cookies, canonical antiforgery issuance, identity binding, and the CSRF `ApiError` |
| [`AuthenticatedRouteProtectionTest.kt`](../../../backend/modules/platform/test/shop/voenix/auth/AuthenticatedRouteProtectionTest.kt) | The role-free protection: fail-closed `401` for anonymous callers, any-role access, and CSRF enforcement for mutating methods only |
| [`GuestCapableRouteProtectionTest.kt`](../../../backend/modules/platform/test/shop/voenix/auth/GuestCapableRouteProtectionTest.kt) | The guest-capable protection: guests read and write with a valid CSRF pair, missing or invalid tokens fail before the handler, and a signed-in caller with a foreign or anonymous CSRF session is rejected |
| [`GuestTokensTest.kt`](../../../backend/modules/platform/test/shop/voenix/auth/GuestTokensTest.kt) | Guest-cookie issuance, the read-only `tryGet`, which never sets a cookie, and `rotate`, which replaces an existing cookie but creates none |
| [`ApiErrorTest.kt`](../../../backend/modules/platform/test/shop/voenix/http/ApiErrorTest.kt) | The shared error body, including the omitted optional `code` |
| [`AuthCookieCompatibilityTest.kt`](../../../backend/modules/platform/test/shop/voenix/auth/AuthCookieCompatibilityTest.kt) | Preserving serialized session field names and accepting a representative `voenix.auth` cookie created before the auth package extraction |
| [`AuthSettingsTest.kt`](../../../backend/modules/platform/test/shop/voenix/auth/AuthSettingsTest.kt) | Application configuration, missing and blank values, and the UTF-8 byte minimum |
| [`CountryRouteSecurityAndValidationTest.kt`](../../../backend/modules/country/test/shop/voenix/country/CountryRouteSecurityAndValidationTest.kt) | Cross-module security ordering, canonical country routes, ID conversion, body binding, and request validation |
| [`CountryAdminCrudIntegrationTest.kt`](../../../backend/modules/country/test/shop/voenix/country/CountryAdminCrudIntegrationTest.kt) | A complete authenticated and CSRF-protected country workflow against PostgreSQL |
| [`VatRouteSecurityAndValidationTest.kt`](../../../backend/modules/vat/test/shop/voenix/vat/VatRouteSecurityAndValidationTest.kt) | VAT route-subtree protection, CSRF ordering, and validation before operation calls |
| [`VatAdminCrudIntegrationTest.kt`](../../../backend/modules/vat/test/shop/voenix/vat/VatAdminCrudIntegrationTest.kt) | A complete authenticated and CSRF-protected VAT workflow against PostgreSQL |
| [`SupplierRouteSecurityAndValidationTest.kt`](../../../backend/modules/supplier/test/shop/voenix/supplier/SupplierRouteSecurityAndValidationTest.kt) | Supplier route-subtree protection, security ordering, ID conversion, binding, and validation before operation calls |
| [`SupplierAdminCrudIntegrationTest.kt`](../../../backend/modules/supplier/test/shop/voenix/supplier/SupplierAdminCrudIntegrationTest.kt) | A complete authenticated and CSRF-protected Supplier workflow against PostgreSQL |

The auth test application installs the same shared layers explicitly:

```kotlin
installHttpRuntime()
installAuthModule(AuthSettings("a-test-secret-at-least-32-bytes"))
routing {
    // Minimal public and protected test routes.
}
```

Its `/test/sign-in` route bypasses credential verification on purpose. The test
client installs `HttpCookies`, which acts like a small browser cookie jar and
returns cookies on later requests.

Run the backend quality gate from `backend/`:

```sh
./kotlin check
```

## File map

### Application composition

- [`Application.kt`](../../../backend/app/src/shop/voenix/Application.kt) loads
  settings and installs `HttpRuntime`, `AuthModule`, and product modules as
  separate concerns.

### Authentication

- [`AuthModule.kt`](../../../backend/modules/platform/src/shop/voenix/auth/AuthModule.kt)
  contains the internal runtime handle that configures sessions, authenticates
  cookies, checks the admin role, enforces CSRF, creates tokens, and renews
  sessions.
- [`AuthRouting.kt`](../../../backend/modules/platform/src/shop/voenix/auth/AuthRouting.kt)
  exposes only the provider and CSRF-header names required by product routes
  and HTTP tests.
- [`RouteProtection.kt`](../../../backend/modules/platform/src/shop/voenix/auth/RouteProtection.kt)
  holds the shared fail-closed plugin core used by all route protections.
- [`AdminRouteProtection.kt`](../../../backend/modules/platform/src/shop/voenix/auth/AdminRouteProtection.kt)
  is the route protection requiring the exact `ADMIN` role.
- [`AuthenticatedRouteProtection.kt`](../../../backend/modules/platform/src/shop/voenix/auth/AuthenticatedRouteProtection.kt)
  is the route protection for any signed-in user without a role requirement.
- [`GuestCapableRouteProtection.kt`](../../../backend/modules/platform/src/shop/voenix/auth/GuestCapableRouteProtection.kt)
  is the CSRF-only protection for subtrees that guests may use.
- [`AuthSettings.kt`](../../../backend/modules/platform/src/shop/voenix/auth/AuthSettings.kt)
  loads and validates the session secret.
- [`UserSession.kt`](../../../backend/modules/platform/src/shop/voenix/auth/UserSession.kt) is the
  serializable auth-cookie payload and exposes the `currentUserSession()`
  helper for routes that accept both guests and signed-in users.
- [`GuestTokens.kt`](../../../backend/modules/platform/src/shop/voenix/auth/GuestTokens.kt)
  issues and reads the encrypted `voenix.guest` cookie for visitors without an
  account.
- [`SessionCookieEncryption.kt`](../../../backend/modules/platform/src/shop/voenix/auth/SessionCookieEncryption.kt)
  derives the purpose-specific encryption and signing keys shared by the
  session and guest cookies.
- [`UserPrincipal.kt`](../../../backend/modules/platform/src/shop/voenix/auth/UserPrincipal.kt) is
  the validated identity visible to a handler.
- [`CsrfSession.kt`](../../../backend/modules/platform/src/shop/voenix/auth/CsrfSession.kt) is the
  serializable CSRF-cookie payload.
- [`SameAsRequestCookieTransport.kt`](../../../backend/modules/platform/src/shop/voenix/auth/SameAsRequestCookieTransport.kt)
  defines cookie flags and request-aware `Secure` behavior.
- [`AuthResponse.kt`](../../../backend/modules/platform/src/shop/voenix/auth/AuthResponse.kt)
  defines the unchanged `401` and `403` bodies.
- [`AntiforgeryTokenResponse.kt`](../../../backend/modules/platform/src/shop/voenix/auth/AntiforgeryTokenResponse.kt)
  defines the token response.

### Shared HTTP runtime

- [`HttpRuntime.kt`](../../../backend/modules/platform/src/shop/voenix/http/HttpRuntime.kt)
  installs Content Negotiation and `StatusPages`.
- [`ApiError.kt`](../../../backend/modules/platform/src/shop/voenix/http/ApiError.kt) defines the
  small JSON error used by CSRF, request binding, and module routes.

## Summary

The application trusts only encrypted, signed, non-expired session cookies. A
valid cookie becomes a `UserPrincipal`; every admin handler then requires the
exact `ADMIN` role, while handlers behind the authenticated-route protection
accept any signed-in user. Protected writes additionally require a random CSRF
token bound to the same user ID. `AuthModule` owns those rules and their responses,
while `HttpRuntime` owns JSON conversion and shared exception mapping. Module
packages use those small interfaces and declare normal canonical Ktor routes.

Credential verification, production sign-in and sign-out, user lookup, and
server-side revocation remain outside the current authentication scope.
