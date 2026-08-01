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

## Shared `databaseOperation` helper (open decision for Joe)

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

- [ ] Joe decides whether the helper moves to `platform`; if yes, migrate all
  copies in one sweep after the last module migration, so no migration has to
  chase a moving architecture default.

## MagicCoins guest-balance claim (open decision for Joe)

The legacy backend never claims MagicCoins guest balances on login or
registration — a known gap the Account migration handed to Cart. The Cart
council (2026-07-29) deliberately deferred it again: closing the gap is a
behavior *extension*, and it first needs a rule for the case where a guest
balance and a user balance both exist (adding is abusable, discarding can
destroy bought or earned balance) plus protection against repeated claims of
the same token.

- [ ] Joe decides the merge rule; whichever migration or feature implements
  it extends the account module's `GuestDataClaims` port with a MagicCoins
  branch (origin: [`cart-migration.md`](cart-migration.md), decision log
  2026-07-29).

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

- [ ] Joe decides whether the guest token is rotated on login and/or cleared on
  logout. Rotating on login costs nothing once the claim has run (the rows
  already carry the user id); clearing on logout ends anonymous continuity of
  the same browser, which is a product question, not a technical one. Origin:
  [`cart-migration.md`](cart-migration.md), phase-3 verification 2026-07-30.

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

- [ ] Joe decides what protects the endpoint: a rate limit per IP or per guest
  token, a smaller or one-time initial grant, requiring an account for
  generation, or accepting the exposure with monitoring. Origin:
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

- [ ] Joe decides whether the Ktor engine gets a request-size limit (and its
  value); until then, transfer volume per request is bounded only by timeouts.
  Origin: [`generator-migration.md`](generator-migration.md), phase-3
  verification (2026-07-30).

## Transaction-local PostgreSQL timeouts (open decision for Joe)

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

- [ ] Joe decides whether transactions get PostgreSQL-side bounds
  (`lock_timeout`/`statement_timeout`, set app-wide at the datasource or per
  transaction policy) — and if yes, in one sweep, so no module invents its own
  values.

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

- [ ] Joe decides the aspect ratio the shop generates in, ideally against a real
  mug print. Changing it is a one-constant change in `FalImageGenerator` plus its
  adapter test. Origin: [`generator-migration.md`](generator-migration.md),
  decision log point 6 (2026-07-30).
