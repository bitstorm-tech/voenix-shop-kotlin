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

## MagicCoins guest-balance claim (open decision for Joe)

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

## Guest token lifetime across login and logout (open decision for Joe)

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

## Abuse protection for the anonymous, cost-incurring generation endpoint (open decision for Joe)

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

## Shipping-country policy (open product question for Joe)

A checkout accepts any two-letter country code. The `countries` table exists and
is administrable, but nothing consults it when an address is submitted: the
checkout checks the *shape* of `shippingAddress.country` and nothing else, and
`orders.shipping_country` is a `varchar(2)` with no foreign key to `countries`.

That is faithful to the legacy application, which imported `Country.Domain` in
its checkout and never used it — the address DTO carried a plain string — so
keeping it was a port rather than a product change (deviation D10 of the Checkout
migration, confirmed by Joe on 2026-08-02). It is also the reason the frontend
gets away with hardcoding `'DE'` in `createEmptyAddress()`.

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
