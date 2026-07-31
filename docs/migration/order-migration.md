# Order module migration

This record follows [`module-migration-guide.md`](module-migration-guide.md)
and the `migrate-dotnet-feature` skill. It records only module-specific facts,
decisions, deviations, and history. The migration was planned by the
migration council (Claude orchestrator, Opus reviewer, Codex/GPT) on
2026-07-30/31; Joe decided all contested points on 2026-07-31.

## Status

`implementation`

Phase 1 (council brainstorming, analysis, and Joe's decisions) is complete.
Phase 2 implements the sub-tickets listed under "Ticket cut". Phase 3
verification has not run; nothing here is `complete`.

## Task parameters

Target module:

`Order`

Source feature:

The legacy Order behavior is spread over three folders of
`../voenix-shop/backend/Voenix.Api`:

- `Features/Order` — `Domain/Order.cs`, `Domain/OrderItem.cs`,
  `Domain/OrderStatus.cs`, EF configurations, `Services/PaidOrderProcessor.cs`,
  `Controllers/PdfController.cs` (PDF/SFTP infrastructure itself is already
  migrated to the Kotlin `production` module);
- `Features/Checkout` — `CheckoutService.cs` owns order **creation** and the
  customer read paths (`CheckoutController.cs`); only the order-owned parts
  migrate now, the checkout orchestration itself is Wave 3;
- `Features/Auth/Services/GuestDataClaimService.cs` — the order-claim
  behavior; `Features/Cart/Services/CartService.cs` (`ReorderOrderItemAsync`)
  — the reorder behavior.

Target package:

`backend/modules/order/src/shop/voenix/order`

Analysis checkpoint:

`wait-for-approval` — approval obtained 2026-07-31 (see decision log).

Known consumers:

- `production` module: consumes `ProductionSource` (an app-owned stub until
  this migration binds it; since T7 the app-owned `LateBoundProductionSource`
  carries the real implementation).
- `email` module: consumes the order-confirmation branch of the app-owned
  `AggregatedQueuedEmailSource`.
- `account` module: consumes the order branch of the app-bound
  `GuestDataClaims` implementation.
- `cart` module: consumes the new `OrderItemReader` capability for reorder.
- Payment (Wave 2) will call `markPaid`; Checkout (Wave 3) will call the
  placement operation. Both stay `internal` until their consumer exists.
- Frontend: `../voenix-shop/frontend/src/stores/shop/orders.ts`,
  `checkout.ts`, `cart.ts` (adaptation recorded in
  [`order-post-migration.md`](order-post-migration.md), created by ticket T9).

Approved deviations from current behavior:

- See the deviation log below; all rows marked `approved 2026-07-31`.

Explicitly deferred work:

- Checkout orchestration (cart load, totals via `CartTotals`,
  `carts.status = 'CHECKED_OUT'` write path, address validation against the
  country list, pre-payment promotion re-check, promotion reservation by
  in-flight orders): Wave 3 Checkout.
- Mollie payment, `payments` table (`payments.order_id`, not
  `orders.payment_id`), `paymentStatus` in order responses: Wave 2 Payment.
- Frontend adaptation to the new `/api/orders` contract: owner frontend work,
  recorded in `order-post-migration.md`.

## Analysis deliverable

### 1. Behavior–evidence–classification–verification matrix

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| Order snapshots addresses (billing falls back to shipping), email, phone, amounts, promotion id at placement; items snapshot names and prices | `CheckoutService.cs:101-124` | Required | Placement operation copies all values; input carries precomputed amounts | Service integration test asserting stored snapshots |
| Items additionally snapshot `supplier_article_number` and the five print/document measurements at placement (not read at production time) | Joe decision 2026-07-27, `article-post-migration.md` §3 | Required | Placement resolves `ArticleCatalog.find` and stores the `CatalogVariant` fields | Production-source test: catalog change after placement does not alter stored measurements |
| Initial status `PENDING`; transitions `PENDING→PAID` (paid processing) and `PENDING→CANCELLED` (payment-creation failure) | `PaidOrderProcessor.cs:80`, `CheckoutService.cs:149` | Required | `OrderStatus` enum + DB CHECK; `markPaid` writes `PENDING→PAID`. `PENDING→CANCELLED` is *not* written here: only a failed payment creation cancels an order, and that is the Wave-2 Payment migration's flow — see `order-post-migration.md`, "Own the cancellation write path". The schema and the read paths already treat `CANCELLED` as a normal status. | Service tests for `markPaid` (including the refusal to pay a `CANCELLED` order); schema test for the status CHECK |
| `SHIPPED` status exists but is never written | whole-repo search | Incidental | Dropped from the status set | Schema test: CHECK rejects it |
| Paid processing: row lock, idempotent when already `PAID`, redeem promotion (eligibility + limits only, no activity-window check), status change, production + confirmation side effects | `PaidOrderProcessor.cs:23-103`; `promotion-post-migration.md` warning | Required | One Exposed transaction: `FOR UPDATE` on orders, `redeem(promotionId, orderId, userId)` joining the transaction, `UPDATE`, `ProductionOutbox.request`, `EmailOutbox.enqueue` | Integration tests: idempotency, rollback leaves no redemption/production/email row, two concurrent `markPaid` |
| Side effects fire only for a committed paid order (legacy: after commit via channel + enqueue) | `PaidOrderProcessor.cs:98-101` | Required (timing incidental) | Outbox rows written inside the same commit (approved improvement D11) | Rollback test: no `production_requests`/`email_jobs` row |
| Paid processing on an unknown order logs and does nothing | `PaidOrderProcessor.cs:31-35` | Required | `PaidOrderResult.NotFound`, warning naming the order id | Service test asserting the result and the logged warning |
| `markPaid` on a `CANCELLED` order: legacy would silently pay it | code inspection | Proposed deviation (D15) | `PaidOrderResult.Cancelled`, warning naming the order id, no status change | Service test asserting the result and the logged warning |
| Customer order history sorted newest first | `CheckoutServiceTests` (`SortedNewestFirst`) | Required | `ORDER BY created_at DESC, id DESC` | Flow test with fixtures whose creation order opposes id order is impossible (both monotonic); fixture manipulates `created_at` so that created-at order opposes id order |
| Single-order lookup authorized by guest token only | `CheckoutService.cs:164-166` | Proposed deviation (D3/K1) | `user_id` match OR (`user_id IS NULL` AND guest-token match); miss is always `404` | Route tests: own user without cookie, foreign token, claimed order via old token |
| Production PDF: address page + one page per unit (quantity expanded), measurements with 239×99 mm fallback | `PdfService.cs`, `PdfDocument.cs`; already migrated | Required (already verified in `production`) | `ProductionSource` supplies stored snapshots + image paths; rendering unchanged | `OrderProductionSourceTest` |
| PDF endpoint is anonymous (commented-out admin attribute) | `PdfController.cs:11` | Incidental (security defect) | Admin-only routes (D1), split per supplier (D2) | Route security tests |
| Order claims at login/registration by guest token AND case-insensitive email match on unassigned orders | `GuestDataClaimService.cs` | Required, with D21 restriction | `OrderGuestData.claim(guestToken?, email, userId)`: token match plus `LOWER(email)` match on `user_id IS NULL` rows; email claim only on login | Claim integration tests incl. case-insensitivity and confirmed-email gating |
| Reorder creates a new cart line from an old order item | `CartService.cs:151-192` | Required, with D13/D14 | `cart` consumes `OrderItemReader`; line is a normal add-to-cart at current catalog price | Cart flow test |
| Reorder without a print image → error `ORDER_IMAGE_UNAVAILABLE` | `CartService.cs`, cart-migration deviation 11 | Required | `409` + `ApiError.code = "ORDER_IMAGE_UNAVAILABLE"`; same code when the stored file is missing on disk | Route test |
| Confirmation email data: recipient, addresses, items, shipping, total | `EmailService.cs`, `QueuedEmail.OrderConfirmation` | Required | Resolver reads stored order values per attempt; email model extended per D12 | Resolver tests incl. changed recipient between attempts, `Europe/Berlin` order date on both sides of midnight |
| Legacy subtotal in the email is `total − shipping`, discount invisible; model invariant `total >= shipping` throws for 100% coupons | `EmailRenderer.kt:96-97`, `QueuedEmail.kt:24` | Proposed deviation (D12, blocker) | Model gains `subtotalInCents`/`discountInCents`; invariant relaxed to `total >= 0` | Renderer test with 100% coupon |
| No checkout idempotency: two parallel requests create two orders | code inspection (no key, no transaction without promotion) | Proposed deviation (D10) | Partial unique index `orders(cart_id) WHERE status <> 'CANCELLED'`; repository returns `AlreadyPlaced(order)` on `23505` | Concurrency test: two parallel placements, one row wins |
| In-flight pending orders reserve promotion capacity for 15 minutes | `PromotionApplicationService.cs:107-128` | Required behavior of the **checkout** flow | Not built here (Wave 3); schema keeps `promotion_id`, `status`, `created_at` queryable so Checkout can implement it | Recorded in `promotion-post-migration.md`; nothing to verify in Order |
| `custom_data` jsonb on order items | Only `{}` is ever written in production paths | Incidental | Dropped (D6, consistent with cart) | — |
| Raw guest token written to the log on order creation | `CheckoutService.cs:124` | Incidental (security defect) | Not ported (D17) | Log assertion in service test |

### 2. Operation contract

All customer routes live under `/api/orders` (guest-capable protection),
admin routes under `/api/admin/orders` (admin protection). The two subtrees
are deliberately disjoint route nodes. Every answer an order handler produces
sets `Cache-Control: no-store` — the order views, the PDF list, and the PDF
itself. The `401`/`403` rejections come from the shared route protection
before any handler runs; they carry no order data and therefore no such
header.

| Operation | Required input | Required success value | Required errors | Ordering |
| --- | --- | --- | --- | --- |
| List `GET /api/orders` | session/guest cookie | direct `List<OrderView>` | none (empty list without identity) | `created_at DESC, id DESC` |
| Get `GET /api/orders/{id}` | id + ownership | `OrderView` | `404` (unknown **and** foreign) | n/a |
| Admin PDF list `GET /api/admin/orders/{id}/production-pdfs` | id | `List<ProductionPdfInfo>` (supplierId, fileName) | `404` unknown order, `409`/`502`-style mapped generation failures per `ProductionPdfError` | first appearance of supplier in item order |
| Admin PDF `GET /api/admin/orders/{id}/production-pdfs/{supplierId}` | ids | `application/pdf`, `Content-Disposition: attachment; filename="ORD-{id}.pdf"` | `404` | n/a |
| Reorder `POST /api/cart/order-items/{orderItemId}` (owned by `cart`) | id + ownership | `CartView` | `404` foreign/unknown item, `409` + `ORDER_IMAGE_UNAVAILABLE`, cart validation errors | n/a |
| Placement (internal, no HTTP) | `PlaceOrderInput` | `Success(OrderView)` / `AlreadyPlaced` | `Invalid`, `UnknownArticleReference`, `UnknownPrintImage` | items keep input order via `position` |
| markPaid (internal, no HTTP) | order id | `Paid` / `AlreadyPaid` | `NotFound`, `Cancelled`, `PromotionRefused` (still paid, see decision) | n/a |

Example response body (`GET /api/orders/{id}`; the list returns the same
shape as a direct array — one representation for list and detail):

```json
{
  "orderId": 42,
  "createdAt": "2026-07-30T09:12:44Z",
  "status": "PAID",
  "subtotal": 3980,
  "shippingCost": 490,
  "discountAmount": 400,
  "total": 4070,
  "items": [
    {
      "orderItemId": 7,
      "articleId": 3,
      "variantId": 9,
      "articleName": "Tasse Klassik",
      "variantName": "Weiß/Blau",
      "quantity": 2,
      "price": 1490,
      "promptPrice": 500,
      "imageId": 12
    }
  ]
}
```

There is no request body on any Wave-1 route, so the module registers no
`validateOrderRequests` yet; the placement input is validated by its pure
`validate()` through the service seam only. `paymentStatus` is absent until
Wave 2. No wrapper objects: the list is a direct JSON array.

### 3. Material ambiguities and their resolution

All were decided by Joe on 2026-07-31; see the decision log. The
source/product contradiction about the confirmation-email trigger
(`email-post-migration.md`, "Order-confirmation trigger and composition")
is resolved: the trigger is `PAID`, enqueued inside the `markPaid`
transaction.

### 4. Kotlin operation interface and production type map

| File (one top-level type each) | Visibility | Meaning |
| --- | --- | --- |
| `OrderModule.kt` (+ `createOrderModule`, `installOrderModule`) | `public class`, factory `internal` | Runtime handle; exports `productionSource`, `orderConfirmations`, `guestData`, `orderItems` (reorder reader) |
| `Orders.kt`, `OrderItems.kt` | `internal object` | Exposed tables |
| `OrderStatus.kt` | `internal enum` | `PENDING`, `PAID`, `CANCELLED` |
| `OrderOperations.kt` | `internal interface` | Route/service seam and route-test seam; returns shared `OperationResult<T>` |
| `OrderService.kt`, `OrderRepository.kt`, `OrderRoutes.kt` | `internal` | Service rules, single table access + transactions, thin routes |
| `OrderView.kt`, `OrderLineView.kt` | `internal @Serializable` | One representation for list and detail |
| `PlaceOrderInput.kt` (nested `Address`, `Line`) | `internal` | Placement input with precomputed amounts and validated addresses |
| `OrderWriteResult.kt` | `internal sealed` | `Stored`, `AlreadyPlaced(order)`, `Invalid(errors)`, `UnknownArticleReference`, `UnknownPrintImage`. `Invalid` was added in T5: placement validates its own input through the service seam, and without a variant for it the caller could not tell a field error from a missing reference |
| `PaidOrderResult.kt` | `internal sealed` | `Paid`, `AlreadyPaid`, `NotFound`, `Cancelled`, `PromotionRefused(reason: PromotionCodeResult)` — the refusal carries what the promotion module said, so the warning log names the limit that was actually hit |
| `OrderGuestData.kt` | `public class` | Claim capability (guest token + lowercase email match) |
| `OrderItemReader.kt` | `public fun interface` (planned as a class) | Ownership-checked reorder snapshot (`articleId`, `variantId`, `promptId`, `printImageId`); returns `null` for both unknown and foreign items. It became a `fun interface` in T5 for the reason `ArticleCatalog` is one: the cart has to prove what it *does* with an ordered line, so it fakes the capability, while the ownership rule is proven here against real rows |

`ProductionSource` and `QueuedEmailSource` are existing `fun interface`s and
are implemented by module-assembled lambdas — no new pass-through types.

Two more shapes arrived with the implementation and are recorded here rather
than rediscovered later: `installOrderModule` (public install plus the
`internal` route-test overload) landed with T6, and its `productionPdfs` and
`guestTokens` parameters with it, because they are what the *routes* need and
the operations do not; `printImages` arrived with T7, when
`PrivateImageStorage.originalPaths` existed to consume. `PrintImages.kt`,
`StoredOrder.kt`, and `ProductionPdfInfo.kt` are the three types the plan did
not name: the print-image table the placement checks and the production source
reads names from, the inside view of an order both workers share, and the list
entry of the admin PDF route.
Twelve-plus types is a review signal: the simplification review must apply
the deletion test to `OrderWriteResult`, `PaidOrderResult`, and the
`OrderView`/`OrderLineView` split.

### 5. Runtime composition

- `OrderModule` is `public` (the composition root passes its exported
  capabilities onward after install); `createOrderModule` is `internal`.
- `installOrderModule(database, articles, promotions, productionOutbox,
  emailOutbox, imageOriginals, pdfGenerator, guestTokens)` plus an
  `internal` overload taking `OrderOperations` as the route-test seam
  (cart pattern).
- Consumed capabilities: `ArticleCatalog.find` (placement snapshot and live
  supplier resolution at production time — supplier id is deliberately not
  stored), extended `PromotionCodes.redeem`, `ProductionOutbox.request`,
  `ProductionPdfGenerator`, `EmailOutbox.enqueue`, new
  `PrivateImageStorage.originalPaths`, platform auth/HTTP/result/database
  infrastructure.
- The email/production ↔ order composition cycle is broken with one
  app-owned `LateBoundProductionSource` that behaves exactly like today's
  stub until bound (workers treat it as retryable `SOURCE_UNAVAILABLE`).

### 6. Application-composition and Flyway changes

Composition root (`app`): install order after email/production, bind
`order.productionSource` into the late-bound source, bind
`order.orderConfirmations` into `AggregatedQueuedEmailSource` (replacing the
`error(...)` branch), pass `order.orderItems` into `installCartModule`, and
extend the `GuestDataClaims` lambda with the order claim (cart and order
claims must run independently; a failure in one must not skip the other).

Flyway `V16__create_orders.sql` (nothing exists yet; next free number):

- `orders`: identity PK; `cart_id NOT NULL` FK `carts` RESTRICT;
  `guest_session_token text NULL`; `user_id` FK `users` SET NULL;
  `promotion_id` FK `promotions` **RESTRICT**; `status` CHECK
  (`PENDING|PAID|CANCELLED`); typed shipping/billing snapshot columns as in
  `users` (country `varchar(2)`, no FK to `countries`); `email varchar(255)`,
  `phone text NULL`; `subtotal_cents`, `shipping_cost_cents`,
  `discount_cents`, `total_cents`, all non-negative with CHECK
  `total = subtotal + shipping - discount`; owner CHECK (guest token or user
  id present); `created_at`/`updated_at`.
- Indexes: partial unique `ux_orders_live_cart` on `(cart_id)` where
  `status <> 'CANCELLED'`; `(user_id, created_at DESC)`;
  `(guest_session_token)`; `LOWER(email) WHERE user_id IS NULL`;
  `(promotion_id)`.
- `order_items`: FK `orders` CASCADE; `position > 0` with
  `UNIQUE (order_id, position)`; `article_id`/`variant_id` **without**
  catalog FK (orders must survive catalog deletion — deliberate asymmetry to
  `cart_items`); name snapshots; `supplier_article_number NULL`; five
  measurement columns (positive-or-NULL CHECKs); `quantity 1..99`;
  non-negative price columns; `prompt_id` FK `prompts` SET NULL;
  `print_image_id` FK `print_images` RESTRICT.
- `promotion_redemptions`: add `order_id bigint NOT NULL`, `UNIQUE`, FK
  `orders` RESTRICT (stronger than legacy's nullable column — no redemption
  without an order exists in the Kotlin schema).
- `production_requests`: add the deferred FK on `order_id` → `orders`
  RESTRICT.
- Update the V15 comment that justified `SET NULL` for `carts.promotion_id`
  with the "second restricting reference" argument (T2 generalizes the
  delete result instead).

### 7. Test plan

Files follow the cart test matrix; every named fixture shape is part of the
plan, not decoration:

- `OrderInputValidationTest` — full field-rule matrix of `PlaceOrderInput`
  (pure).
- `OrderPlacementIntegrationTest`, `OrderPaymentIntegrationTest`, and
  `OrderAccessIntegrationTest` (on the shared `OrderServiceTestBase`) —
  placement snapshots, catalog-change isolation, `markPaid` idempotency,
  `Cancelled` refusal, `PromotionRefused`-still-paid semantics, rollback leaves
  no redemption/production/email row, `CancellationException` rethrow, no raw
  guest token in logs, the ownership rule, and the claims.
- `OrderConcurrencyIntegrationTest` — two parallel `markPaid` on one order;
  two parallel placements for one `cart_id` (one `Stored`, one
  `AlreadyPlaced`); redemption-limit race via the promotion row lock.
- `OrderRouteSecurityAndValidationTest` — rejection before operation
  invocation on all routes; ownership matrix: own user without guest cookie
  (allowed), foreign guest token (404), claimed order via old guest token
  (404), admin routes closed to non-admins.
- `OrderFlowIntegrationTest` — end-to-end wire shapes and status codes;
  history ordering with `created_at` fixtures whose order opposes id order.
- `OrderSchemaIntegrationTest` — every CHECK/FK/unique violated by a seed
  that can only violate the rule under test, including the partial unique
  index (a `CANCELLED` order must not block a new placement).
- `OrderProductionSourceTest` — snapshot fidelity, missing supplier →
  `supplierId = null` (retryable), missing image file → `imagePath = null`,
  item order by `position`.
- Email resolver tests — stored-value resolution per attempt, changed
  recipient between attempts, `Europe/Berlin` order date across midnight,
  100% coupon renders (after D12).
- App composition test — the four bindings (production source, mail
  resolver, guest claims incl. independence of cart/order branches, cart
  reorder reader) proven against the real composition.
- Fakes for suspending capabilities must suspend where the real ones do
  (cart lesson).

### 8. Deferred work and owners

See "Explicitly deferred work" above; durable cross-module items live in
[`order-post-migration.md`](order-post-migration.md), which T9 created and
which also owns the frontend adaptation list and the Wave-2/Wave-3 hooks.

## Ticket cut (Phase 2)

Serial execution, one `council-opus-implementer` per ticket, orchestrator
runs the full gate between tickets:

1. **T1 — Schema**: `V16__create_orders.sql`, Exposed tables,
   `OrderSchemaIntegrationTest`, module skeleton (`module.yaml`,
   `project.yaml`, empty handle).
2. **T2 — Promotion prerequisites**: `redeem(promotionId, userId, orderId)`
   joins the caller's transaction (`ProductionOutbox` pattern,
   `checkNotNull(TransactionManager.currentOrNull())`), delete result
   `Redeemed` → `InUse`, V15 comment fix, tests. Blocked by T1.
3. **T3 — Account prerequisites**: `GuestDataClaims.claim(userId,
   guestToken: String?, email)`, no early return without cookie, email claim
   only on login, app lambda + tests. Blocked by T2 (serial chain).
4. **T4 — Email contract**: `QueuedEmail.OrderConfirmation` gains
   `subtotalInCents`/`discountInCents`, invariant relaxed to `total >= 0`,
   template shows the discount line, renderer tests incl. 100% coupon.
   Blocked by T3 (serial chain).
5. **T5 — Order core**: domain, repository, service, placement, `markPaid`,
   `OrderGuestData`, `OrderItemReader`, service/concurrency integration
   tests. Blocked by T1, T2, T4.
6. **T6 — HTTP**: customer routes, admin PDF routes, route security and flow
   tests. Blocked by T5.
7. **T7 — Bindings**: `PrivateImageStorage.originalPaths(Set<String>):
   OperationResult<Map<String, Path>>` in `image`, `ProductionSource`
   implementation, mail resolver, app composition with
   `LateBoundProductionSource`, guest-claims order branch, composition
   tests. Blocked by T5, T3, T4.
8. **T8 — Reorder**: cart route `POST /api/cart/order-items/{id}` consuming
   `OrderItemReader`, current-price line creation,
   `ORDER_IMAGE_UNAVAILABLE`, tests. Blocked by T5, T7.
9. **T9 — Documentation**: `order-package.md`, `module-architecture.md`
   (graph, table, layout, capabilities, composition), roadmap re-wave,
   `order-post-migration.md` (frontend work), update the stale passages in
   `promotion-post-migration.md`, `image-post-migration.md` (incl. the
   "do not import paths" precision: construct no paths, know no roots),
   `cart-migration.md`, `production-migration.md`,
   `email-post-migration.md` (trigger resolved), `account-post-migration.md`,
   and the package guides of every touched module. Blocked by T6, T7, T8.

## Decision log

### 2026-07-27 — Snapshot obligation (inherited)

Joe decided in the Article migration that `order_items` snapshots
`supplier_article_number` and the five measurement columns at placement
(`article-post-migration.md` §3).

### 2026-07-30 — WebP unblocked (inherited)

The former Order blocker "WebP originals in production PDFs" was resolved in
the Cart phase 3 (ticket T2b, `LosslessFactory.createFromImage`), proven by
`ProductionPdfWebpSourceTest`.

### 2026-07-31 — Council synthesis (rebuttal round)

Consensus after one rebuttal round: strict read authorization (guest token
counts only while `user_id IS NULL`); `orders.promotion_id` RESTRICT with the
`InUse` generalization; partial unique `ux_orders_live_cart` with
`AlreadyPlaced(order)` on conflict; reorder route stays in `cart`, `order`
exports `OrderItemReader`. The only conflict left open for Joe was the image
read path (paths vs. bytes).

### 2026-07-31 — Joe's decisions (Phase-1 checkpoint approval)

1. Paid order whose promotion limit is exhausted: order becomes `PAID`
   without a redemption, warning logged (legacy left it `PENDING` forever —
   money taken, goods never delivered).
2. Confirmation-email trigger: **`PAID`**, enqueued inside the `markPaid`
   transaction. This resolves the documented contradiction with the
   2026-07-16 statement in `email-post-migration.md`; that file's open
   trigger items are to be updated (T9).
3. Email contract change approved: `subtotalInCents`/`discountInCents`,
   invariant relaxed (otherwise the first 100% coupon retries forever).
4. Reorder uses the current catalog price, not the historical snapshot.
5. Production-PDF download is built, admin-only, two routes (list + per
   supplier).
6. `GET /api/orders` is guest-capable (guests list their unclaimed orders).
7. `supplier_id` is not stored; the production source resolves it live so a
   missing assignment stays repairable.
8. Image read path: the `image` module returns ready `Path` values
   (`originalPaths`, set-in/map-out); `ProductionItem.imagePath` stays.
9. Address country columns are `varchar(2)` without an FK to `countries`
   (order is an immutable record; validity is a checkout rule). Joe asked
   about an FK; declined after reviewing the four counter-arguments
   (frozen master data, order_items precedent, 23503 ambiguity on a
   multi-FK insert, checkout-time semantics).
10. `orders.user_id` is `ON DELETE SET NULL`; the resulting guest-cookie
    re-visibility after account deletion is accepted and logged (no account
    deletion feature exists yet).

Module-change approvals: transactional `redeem` with order id and the
`InUse` delete result (promotion); `GuestDataClaims` carries the email with
a nullable guest token (account); the email claim runs only at login, which
requires a confirmed email — the legacy registration-time claim is an
account-takeover vector and is not ported.

### 2026-07-31 — Phase-2 acceptance notes (orchestrator)

Two contract details the plan left to the implementation were decided during
the acceptance of ticket T6 (issue #57) and are recorded here so phase 3
reviews them as decisions rather than as findings:

1. **`ProductionPdfError` → HTTP.** Three of the four reasons —
   `MISSING_IMAGE`, `UNREADABLE_IMAGE`, `INVALID_SOURCE` — are statements about
   the order's own production data: something an admin can repair, and a
   document that will exist once they have. They answer `409` with a stable
   `PRODUCTION_PDF_*` code. `RENDER_FAILURE` is nobody's data problem and stays
   a `500` whose details are in the log and never in the body.
2. **Unparsable route ids answer `404`, uniformly.** An id that is not a number
   never named an order, so it is not a `400`. The answer also does not say
   *which* of `{orderId}`/`{supplierId}` was unusable — anything else would
   tell a caller that the id space is numeric and where their probe went wrong.

A third decision was accepted with ticket T8 (issue #59) and is recorded as
deviation D27 below: a reorder adds quantity 1 instead of replaying the ordered
quantity.

## Deviation and uncertainty log

| # | Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- | --- |
| D1 | Anonymous `GET /api/orders/{id}/pdf` (IDOR) | `PdfController.cs:11` | Admin-only under `/api/admin/orders` | Security fix | Approved 2026-07-31 | — |
| D2 | One PDF per order | `PdfService.cs` | One PDF per supplier; list + fetch routes | Forced contract change (`ProductionPdfResult`) | Approved 2026-07-31 | Frontend/ops note in T9 |
| D3 | Single-order lookup by guest token only | `CheckoutService.cs:164` | `user_id` OR (`user_id IS NULL` AND token); always 404 on miss | Bug fix + hardening | Approved 2026-07-31 | — |
| D4 | History requires login | `CheckoutController.cs` | Guest-capable list of unclaimed orders | Extension | Approved 2026-07-31 | Shares the open guest-token-lifetime topic (`all-post-migration.md`) |
| D5 | `orders.payment_id` (no FK, implicit flush) | `CheckoutService.cs:155` | No column; Wave 2 builds `payments.order_id` | Structure + deferred | Approved 2026-07-31 | Payment migration |
| D6 | `custom_data jsonb NOT NULL` | Only `{}` ever written | Dropped | Incidental | Approved 2026-07-31 | — |
| D7 | `SHIPPED` status | Never written | Dropped from CHECK | Cleanup | Approved 2026-07-31 | A future shipping feature re-adds it with its workflow |
| D8 | Untyped text address columns, no indexes/FKs; free-text country | EF snapshot | Typed columns as `users`; `varchar(2)` country without countries-FK; FKs to `users`/`carts`/`promotions`; four indexes | Narrowing + integrity | Approved 2026-07-31 (each narrowing this row) | Checkout owns country validation |
| D9 | Only total + shipping persisted | `Order.cs` | `subtotal_cents` + `discount_cents` with consistency CHECK | Data quality | Approved 2026-07-31 | — |
| D10 | No checkout idempotency (double order) | No key, no transaction without promotion | Partial unique `(cart_id) WHERE status <> 'CANCELLED'`; `AlreadyPlaced(order)` on 23505 | Correctness | Approved 2026-07-31 | Wave 2 adds provider idempotency per order |
| D11 | Side effects after commit via channel (`TryWrite` unchecked) | `PaidOrderProcessor.cs:98` | Outbox rows inside the paid commit; latency of one worker scan | Improvement | Approved 2026-07-31 | — |
| D12 | Email subtotal = total − shipping; invariant `total >= shipping` | `EmailRenderer.kt:96`, `QueuedEmail.kt:24` | Model carries subtotal + discount; invariant `total >= 0` | Bug fix in `email` | Approved 2026-07-31 | T4. Implemented stricter than the approved wording: the mail model also requires `total == subtotal + shipping − discount` (exact arithmetic), the same statement the `orders` CHECK makes, so an inconsistent mail cannot be built at all |
| D13 | Reorder reactivates the historical price | `CartService.cs:187` | Current catalog price via normal add-to-cart | Pricing rule | Approved 2026-07-31 | — |
| D14 | Reorder requires login | `CartController` | Same ownership rule as D3 (guest-capable) | Extension | Approved 2026-07-31 | — |
| D15 | Paid processing ignores `CANCELLED` | `PaidOrderProcessor.cs` | `PaidOrderResult.Cancelled`, logged, no change | Bug fix | Approved 2026-07-31 | Wave 2 maps it |
| D16 | `updated_at` not set on payment | `PaidOrderProcessor.cs:80` | Set in `markPaid` | Bug fix | Approved 2026-07-31 | — |
| D17 | Raw guest token logged on order creation | `CheckoutService.cs:124` | Not ported | Security | Approved 2026-07-31 | — |
| D18 | Missing article → empty name snapshot | `CheckoutService.cs` (`?? ""`) | `UnknownArticleReference` rejects placement | Correctness | Approved 2026-07-31 | — |
| D19 | No `prompt_id` on order items (reorder loses the prompt) | `OrderItem.cs`, `CartService.cs` | `prompt_id` FK SET NULL snapshot | Correctness | Approved 2026-07-31 | — |
| D20 | Implicit item order (id) | EF model | `position` with `UNIQUE (order_id, position)` | Determinism | Approved 2026-07-31 | — |
| D21 | Email claim at registration and login | `GuestDataClaimService.cs` | Login only (confirmed email) | Security fix | Approved 2026-07-31 | — |
| D22 | Paid + exhausted promotion leaves order `PENDING` | `PaidOrderProcessor.cs` | `PAID` without redemption, warning | Money-affecting decision | Joe 2026-07-31 | Wave 3 builds the pre-payment reservation |
| D23 | Confirmation trigger nominally contradicted 2026-07-16 statement | `email-post-migration.md:129-139` | Trigger is `PAID` in `markPaid` | Product decision | Joe 2026-07-31 | T9 updates the email post-migration items |
| D24 | `supplier_id` at production time | `ProductionItem` KDoc (healable missing supplier) | Live resolution via `ArticleCatalog`; measurements stay snapshots | Deliberate hybrid | Joe 2026-07-31 | Article deleted before production → request stays retryable open |
| D25 | Account deletion vs. orders | No delete feature exists | `user_id ON DELETE SET NULL`; order becomes token-visible again | Accepted risk | Joe 2026-07-31 | A future deletion feature anonymizes orders first |
| D26 | Promotion delete result named `Redeemed` | `PromotionDeleteResult` | Generalized to `InUse` (orders also restrict now) | Neighbor-module change | Approved 2026-07-31 | T2 |
| D27 | Reorder replays the ordered quantity | `CartService.cs:151-192` | The new cart line always has quantity 1 | Implied by the decided `OrderItemReader` shape ("a normal add-to-cart", D13): the reader carries no quantity, so the price *and* the amount are decided now rather than replayed | Accepted by the orchestrator in T8's acceptance (issue #59, 2026-07-31) | Frontend note in `order-post-migration.md` |
| D28 | Every customer read minted a guest cookie (`GetOrCreateGuestToken`) | `CheckoutController.cs:38` | Reads use `GuestTokens.tryGet` and never mint one: a caller without a cookie stays without one and simply sees nothing | Privacy/behavior fix, and the same read-path policy the cart already follows (a token is created where data is *created*, not where it is read) | Accepted by the orchestrator in the phase-3 consolidation (2026-07-31) | Documented in the `OrderRoutes` KDoc, proven by `a read is answered for the caller's identity and never mints a guest cookie` |

## Migration retrospective

To be completed after Phase 3 verification and the simplification review.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| _pending_ | | | | |
