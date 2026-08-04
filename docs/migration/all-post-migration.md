# All-migrations post-migration to-do list

This list collects work that is intentionally deferred until **the complete
.NET-to-Kotlin migration is finished** — points that span several modules or
only make sense once no further migration can change the picture. Module- or
feature-specific deferred work stays in the per-module
`<module>-post-migration.md` files; this file holds only the cross-cutting
items.

When a migration retrospective produces a finding whose action must wait for
the end of the whole migration, record the finding in the module record as
usual and move the *action* here with a pointer back to its origin.

## Shared `databaseOperation` helper (done)

The service-level "catch `SQLException`, rethrow `CancellationException`, log,
return `UnexpectedFailure`" helper is copied per module instead of living in
`platform`:

- byte-identical `databaseOperation` in `VatService`, `SupplierService`,
  `PromotionService`, `ProductionDestinationService`, four Article services
  (`MugArticleService`, `PublicMugService`, `ArticleCategoryService`,
  `ArticleSubcategoryService`), and five Prompt services (`PromptService`,
  `PromptCategoryService`, `PromptSubcategoryService`, `PromptSlotService`,
  `PromptSlotVariantService`) — the Prompt migration of 2026-07-28 added the
  eighth module without changing the decision, the Cart migration of
  2026-07-30 the ninth (`CartService.databaseOperation`), and the Order
  migration of 2026-07-31 the tenth (`OrderService.databaseOperation`, which
  wraps only the two read operations — placement and payment confirmation let
  the failure surface as an exception on purpose) — Cart is also the
  first module carrying *two* of them, because its promotion operation answers
  with `CartPromotionResult` instead of an `OperationResult` and therefore has
  its own `promotionOperation` copy with a different fallback value;
- the same shape under other names in `PriceService`
  (`withUnexpectedFailureHandling`) and `MagicCoinsService`
  (`withFailureFallback`);
- `CountryService` and `AccountService` inline the same `try`/`catch` per
  operation.

History: the Promotion retrospective recorded the duplication and Joe declined
the move on 2026-07-26, naming "a seventh copy" as the trigger to revisit. The
Article migration (T10, 2026-07-28) is that seventh module and reopened the
question; see the retrospective table in
[`article-migration.md`](article-migration.md).

Proposal, unchanged: put the helper next to `OperationResult` in `platform`
(for example `suspend fun <T> Logger.databaseOperation(message) { ... }`) and
delete the copies. Known constraint: `AccountService` returns module-specific
result types (`RegisterResult`, `LoginResult`, `ChangeEmailResult`) whose own
`UnexpectedFailure` variants an `OperationResult`-shaped helper cannot
produce, so a shared version must be generic in the result type or will only
serve part of the repository. `CartService.promotionOperation` is the same case
and shows the cost of not being generic: one module then carries two copies.

- [x] Decided by Joe on 2026-08-04: the helper moves to `platform`, generic in
  the result type so the module-specific result types are served too, and every
  copy is replaced in one sweep — issue #76.
- [x] Done (issue #76): `Logger.databaseOperation` now lives next to
  `OperationResult` in `platform`
  ([`DatabaseOperation.kt`](../../backend/modules/platform/src/shop/voenix/operation/DatabaseOperation.kt)),
  takes the fallback result as a parameter, and every copy, differently named
  variant, and inline `try`/`catch` listed above calls it instead. See
  [`operation-results.md`](../dev/backend/operation-results.md) for the pattern.

## MagicCoins guest-balance claim (done)

The legacy backend never claims MagicCoins guest balances on login or
registration — a known gap the Account migration handed to Cart. The Cart
council (2026-07-29) deliberately deferred it again: closing the gap is a
behavior *extension*, and it first needs a rule for the case where a guest
balance and a user balance both exist (adding is abusable, discarding can
destroy bought or earned balance) plus protection against repeated claims of
the same token.

- [x] Decided by Joe on 2026-08-04: no claim and no merge will be built. A
  guest cannot buy coins, so the guest balance is deliberately lost on login;
  with the login rotation of the guest token (issue #77) the old balance
  becomes unreachable, which is the intended outcome. The `GuestDataClaims`
  port stays without a MagicCoins branch (origin:
  [`cart-migration.md`](cart-migration.md), decision log 2026-07-29).
- [x] Done (issue #77): nothing was built, which is the point — no claim, no
  merge, and no MagicCoins branch in `IndependentGuestDataClaims`. The login
  rotation shipped with the same issue makes a guest balance unreachable
  afterwards; the reasoning is written down for readers of the code in
  [`magic-coins-package.md`](../dev/backend/magic-coins-package.md), section
  "No balance merge when a guest signs in".

## Guest token lifetime across login and logout (done)

The `voenix.guest` cookie is minted once per browser and then never touched
again by the authentication flow: it is not rotated on login, and `AccountRoutes`
clears only the `UserSession` on logout. So after signing out, the claimed cart
and its print images stay reachable for the cookie's remaining 30 days — the
guest half of the ownership check still matches. On a shared browser the next
person can see them.

Since the Order migration (2026-07-31) the surface is wider but the exposure is
not: an order's guest token stops opening it the moment the order is claimed
(`user_id IS NULL` is part of the read predicate), so a signed-out browser sees
only orders that were never claimed. The one case where a claimed order becomes
token-visible again is a deleted account (`orders.user_id ON DELETE SET NULL`,
deviation D25), and no deletion feature exists yet.

This is **not** a defect of the Cart migration. Legacy behaves exactly this way,
and deviation 14 approved the legacy adoption semantics as a whole; the cart is
simply the first migrated surface with real customer content behind that token.
It is recorded here because the answer is cross-cutting: it belongs to the guest
token in `platform` and the session lifecycle in `account`, not to any single
module.

- [x] Decided by Joe on 2026-08-04: the guest token is rotated on login and
  kept on logout — anonymous continuity of the same browser is deliberately
  preserved — issue #77. Origin: [`cart-migration.md`](cart-migration.md),
  phase-3 verification 2026-07-30.
- [x] Done (issue #77): `GuestTokens` in `platform` gained
  [`rotate(call)`](../../backend/modules/platform/src/shop/voenix/auth/GuestTokens.kt),
  which replaces the `voenix.guest` cookie of the request with a freshly minted
  token — and returns `null` without touching anything when the request carries
  no readable cookie, so a rotation renews an existing guest but never creates
  one. `AccountRoutes` calls it on the login route *after* the guest-data claim:
  before the claim it would throw away the handle the claim needs, after it the
  claimed rows belong to the customer and the old token is worth nothing to
  them. A registration does not rotate, because it starts no user session — the
  address has to be confirmed first. The logout is unchanged and still clears
  the `UserSession` only. Documented in
  [`authentication-and-authorization.md`](../dev/backend/authentication-and-authorization.md),
  section "The guest token's lifetime around a login", and in
  [`account-package.md`](../dev/backend/account-package.md).
- [x] Done (issue #77), the part the ticket did not foresee: the rotation
  forced the cart's identity model to change with it, because a cart could only
  be found by its guest token — deviation 14 of
  [`cart-migration.md`](cart-migration.md), now marked superseded there. Joe
  decided Option B on 2026-08-04: a signed-in request finds its cart by
  `user_id`, the token identifies anonymous carts only, and the claim became
  claim-**or**-merge so a customer who already has a cart loses nothing. The
  schema change is
  [`V19__revise_cart_identity.sql`](../../backend/modules/platform/resources/db/migration/V19__revise_cart_identity.sql),
  the rules are documented in
  [`cart-package.md`](../dev/backend/cart-package.md). With that, the rotation
  protects the cart on a shared browser too, which is what the item asked for.

## Abuse protection for the anonymous, cost-incurring generation endpoint (done)

`POST /api/generator/generate` calls the paid fal.ai API and may be used without
an account. The only thing standing between a visitor and unlimited provider
cost is the Magic Coin balance — and that balance hangs on the `voenix.guest`
cookie. Deleting the cookie produces a fresh guest, and a fresh guest gets the
initial grant of 10 coins, so the same browser can pay for the same free
generations again and again. There is no rate limit anywhere in the backend.

This is **not** a defect of the Generator migration: the legacy application has
exactly the same gap, and the Generator council (2026-07-30) deliberately kept
the legacy state rather than inventing a policy inside a migration. It is
recorded here because the answer is cross-cutting — the guest token lives in
`platform`, the grant in `magic-coins`, and the cost in `generator` — and
because it interacts with the guest-token questions above.

- [x] Decided by Joe on 2026-08-04: a per-IP rate limit protects the endpoint;
  the coin system and the initial grant stay unchanged — issue #78. Origin:
  [`generator-migration.md`](generator-migration.md), decision log point 5
  (2026-07-30).
- [x] Done (issue #78): `POST /api/generator/generate` — and only that endpoint —
  carries a per-IP limit of **20 generations per hour**, answered with `429` and
  a `Retry-After` header in the shared `ApiError` shape once it is used up. The
  limit is `platform`'s policy, not the Generator's: the counting lives in
  [`ClientIpRateLimiter.kt`](../../backend/modules/platform/src/shop/voenix/ratelimit/ClientIpRateLimiter.kt)
  and the route plugin in
  [`ClientIpRateLimit.kt`](../../backend/modules/platform/src/shop/voenix/ratelimit/ClientIpRateLimit.kt),
  while the Generator only installs it on its generation route and the
  composition root builds it. It sits *after* the guest-capable CSRF protection,
  so a request rejected without a token spends no slot. The coin system and the
  initial grant are untouched, and anonymous try-out still works. Counting is a
  fixed one-hour window per IP, kept in memory: correct for the current
  single-instance deployment, and the one class to replace with shared state
  (Redis or a table) the day the backend is scaled out — two instances would
  otherwise grant 20 generations each. The counted address is the connection's
  peer address; the `X-Forwarded-For` header is used only when the new
  `RateLimit.TrustForwardedFor` key (`RATE_LIMIT_TRUST_FORWARDED_FOR`, default
  `false`) enables it, and then its **last** entry — the one the trusted proxy
  appended — because the leading entries are client-supplied and spoofable. See
  [`rate-limiting.md`](../dev/backend/rate-limiting.md).

Related, same attack surface: the generator's multipart reader bounds how many
file-part bytes it *processes* per request (20 MiB), but it cannot cut the
*transfer* off — an abandoned Ktor multipart read never lets the call finish,
so every refusal still drains the remaining body (deviation D-F in
[`generator-migration.md`](generator-migration.md)). Legacy had Kestrel's
30,000,000-byte connection abort for this. The equivalent here is an
engine-level request-size limit, which is application-wide and therefore
cross-cutting.

- [x] Decided by Joe on 2026-08-04: the engine gets an application-wide
  request-size limit of ~30 MB, parity with the legacy Kestrel bound — issue
  #79. Origin: [`generator-migration.md`](generator-migration.md), phase-3
  verification (2026-07-30).
- [x] Done (issue #79): the shared HTTP runtime in
  [`HttpRuntime.kt`](../../backend/modules/platform/src/shop/voenix/http/HttpRuntime.kt)
  installs Ktor's `RequestBodyLimit` with `MAX_REQUEST_BODY_BYTES = 30_000_000`,
  so every route of the application inherits the bound and no module configures
  its own. A request that announces more is refused with `413` in the shared
  `ApiError` shape before any handler runs; a chunked body without a
  `Content-Length` is counted while a handler receives it and refused as soon
  as the arriving bytes pass the bound (a route that never reads its body is
  not counted — there the transfer is bounded by Netty backpressure instead,
  without a `413`; see the phase-3 follow-ups below). The refusal really does
  cut the transfer off — measured against the real Netty engine, a client
  announcing 60 MB gets its `413` after about 1.4 MB of socket buffer instead
  of after 60 MB, which is exactly what deviation D-F could not do from inside
  the multipart reader. Ktor's Netty engine has no request-size
  option of its own, so the bound sits in the platform HTTP runtime — the one
  place every application composition passes through — and still below every
  module's multipart processing. The module limits (10 MiB per image, 20 MiB of
  file parts, 10 MiB per stored image) are unchanged and stay the inner
  processing bounds. See
  [`request-size-limits.md`](../dev/backend/request-size-limits.md).

## Transaction-local PostgreSQL timeouts (done)

The backend's database work is bounded only by the Hikari connection timeout
and the JDBC driver's socket behavior; no transaction sets `lock_timeout` or
`statement_timeout`. The Payment phase-3 review (Codex, 2026-08-01) raised
this against the `NonCancellable` compensation phase in `PaymentService.start`:
an insert waiting on an uncommitted unique-index competitor is bounded only
transitively, by the competitor's own transaction finishing. The council kept
the payment module unchanged — the concern is not payment-specific, and a
`withTimeout` nested inside a `NonCancellable` region would read as a
contradiction — and recorded the idea here as possible application-wide
hardening (origin: [`payment-migration.md`](payment-migration.md), deviation
D20 follow-up).

- [x] Decided by Joe on 2026-08-04: transactions get PostgreSQL-side bounds
  (`lock_timeout`/`statement_timeout`), set app-wide at the datasource in one
  sweep — issue #80.
- [x] Done (issue #80): the Hikari pool in
  [`DatabaseFactory`](../../backend/modules/platform/src/shop/voenix/db/DatabaseFactory.kt)
  starts every connection with `-c lock_timeout=10s -c statement_timeout=30s`,
  so every module inherits the bounds and none configures its own. Flyway now
  opens its own connections from the plain JDBC URL instead of borrowing the
  pool, which keeps migration statements unbounded; the advisory migration lock
  already used a plain `DriverManager` connection. A fired timeout arrives as an
  `SQLException` and travels the ordinary unexpected-failure path of issue #76.
  See [`persistence-error-handling.md`](../dev/backend/persistence-error-handling.md).

## Generated aspect ratio `16:9` for mug printing (open product question for Joe)

Every generation asks fal.ai for `aspect_ratio = "16:9"`. The value is carried
over unchanged from the legacy application, where it is a constant in
`GeneratorService.cs`, and it is now the constant `ASPECT_RATIO` in
`FalImageGenerator`. Nobody has confirmed that a 16:9 image is the right shape
for a mug print: the print area of a mug is not widescreen, so the generated
image is likely cropped, letterboxed, or distorted somewhere between generation
and production.

Keeping the value was the right call for the migration — changing it would have
been a product change disguised as a port — but the question outlives the
migration and touches the print pipeline, so it belongs here rather than in the
module record.

- [x] Decided by Joe on 2026-08-04: `16:9` stays and is accepted permanently
  as a product decision; no print test is planned. Origin:
  [`generator-migration.md`](generator-migration.md), decision log point 6
  (2026-07-30).

## Shipping-country policy (done)

A checkout accepted any two-letter country code. The `countries` table existed
and was administrable, but nothing consulted it when an address was submitted:
the checkout checked the *shape* of `shippingAddress.country` and nothing else,
and `orders.shipping_country` is a `varchar(2)` with no foreign key to
`countries`.

That is faithful to the legacy application, which imported `Country.Domain` in
its checkout and never used it — the address DTO carried a plain string — so
keeping it was a port rather than a product change (deviation D10 of the Checkout
migration, confirmed by Joe on 2026-08-02). It is also the reason the frontend
gets away with hardcoding `'DE'` in `createEmptyAddress()`.

That hardcoded `'DE'` is now a **pointer for the frontend migration**, not a
blocker: it still works, because `DE` is one of the eight countries the first
migration seeds, but the checkout form should offer the administrable list
(`GET /api/countries` already answers it) and let the customer pick, instead of
sending a default the backend may refuse.

The question the migration deliberately did not answer is what the shop wants:

- [x] Decided by Joe on 2026-08-04: the shipping country must be an active row
  of `countries` (the list stays administrable), the *billing* address stays
  unrestricted, and existing orders stay valid when a country is deactivated
  later — an order is a frozen snapshot. Issue #81.
- [x] Where the rule lives, same decision: not in `CheckoutRequest.validate()`
  — the country module exports a narrow capability the checkout service
  consults, answering a field error on `shippingAddress.country`. Details in
  issue #81. Origin: [`checkout-migration.md`](checkout-migration.md),
  decision log point 4 (2026-08-02).
- [x] Done (issue #81): the country module exports
  [`ShippableCountries`](../../backend/modules/country/src/shop/voenix/country/ShippableCountries.kt)
  with the single member `isShippable(countryCode)`, implemented by
  `CountryRepository` as one indexed lookup on `countries.country_code`;
  `CheckoutService` asks it after the cart guards and **before the first
  commit**, so a refused destination reserves no coupon and writes no order, and
  the cart stays `ACTIVE`. The refusal is `400` in the exact body the Request
  Validation plugin produces — `{"message":"Validation failed","errors":
  {"shippingAddress.country":["We do not ship to this country"]}}` — and
  deliberately carries no `code`: the customer still has the form on screen, so
  the sentence belongs on the field, not in a branch of the frontend's error
  switch. Only the shipping address is checked; a billing address may name any
  country. No foreign key was added: `orders.shipping_country` stays plain text,
  and a composition test states the frozen-snapshot property directly — after
  the admin deletes a country, the order already placed keeps its country, stays
  readable, and stays payable, while the next checkout to it is refused.
  **The table has no `active` column**, so "active row" means "row that exists":
  the country admin opens a destination by creating the row and closes it by
  deleting it. The capability is named for the question it answers, not for a
  column, so adding a real activation flag later changes only the repository.
  See [`checkout-package.md`](../dev/backend/checkout-package.md) and
  [`country-package.md`](../dev/backend/country-package.md).

## Follow-ups from the hardening batch's phase-3 verification (open)

The council verification of PR #83 (2026-08-04) confirmed the batch and fixed
its findings, and left exactly four follow-ups — none a blocker, all
cross-cutting enough to live here:

- [ ] **An activation flag on `countries` instead of the destructive delete.**
  Today the only way to close a shipping destination is deleting the row, which
  also nulls `suppliers.country_id` irreversibly (`ON DELETE SET NULL`) and
  shrinks the public `GET /api/countries` list the address form is rendered
  from — both side effects are documented in
  [`country-package.md`](../dev/backend/country-package.md). A real flag plus
  an admin field would separate "we do not ship there right now" from "this
  country does not exist"; `ShippableCountries` was named so that only the
  repository changes. Origin: phase-3 review of issue #81 (Codex finding,
  accepted as follow-up).
- [ ] **`413` semantics for chunked bodies on routes that never read them.**
  The `RequestBodyLimit` plugin counts bytes only while a handler receives the
  body; a chunked request to a bodyless route (e.g. the payment retry) is
  bounded by Netty backpressure but gets no `413`. Enforcing a status there
  would need engine-level work; the rejected `HttpObjectAggregator` approach
  and the reasoning are recorded in
  [`request-size-limits.md`](../dev/backend/request-size-limits.md). Origin:
  phase-3 review of issue #79.
- [ ] **The two `databaseOperation` stragglers.** `PublicPromptService.list`
  and `PaymentService.confirm` still carry the pre-#76 inline
  `try`/`catch` pattern; converting them is mechanical. Origin: phase-3 review
  of issue #76.
- [ ] **Machine-readable `code` fields for `413` and `429`.** Both responses
  carry only a message today; the storefront cannot branch on them the way it
  branches on `INSUFFICIENT_MAGIC_COINS`. Decide together with the frontend
  migration whether a `code` is worth adding. Origin: phase-3 review of issues
  #78/#79.
