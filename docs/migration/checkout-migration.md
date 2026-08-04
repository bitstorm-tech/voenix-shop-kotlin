# Checkout migration

## Status

`complete`

Phase 1 (council brainstorming) is complete: three independent proposals
(orchestrator, Opus, Codex), one rebuttal round, and Joe's decisions of
2026-08-02 are recorded below.

Phase 2 is implemented on the `checkout-migration` branch: the six sub-tickets
T1–T6 — the promotion reservation lifecycle with `V18`, the cart's
`CheckoutCarts`, the order's `OrderPlacement` with the release hooks, the
payment's `PaymentStarter`, the checkout module itself with its composition, and
the cross-module test matrix plus the documentation sweep.

Phase 3 ran on 2026-08-03: three independent verification reviews
(orchestrator, Opus, Codex), one rebuttal round per contested finding, four fix
tickets (the terminal-notification hardening, the reservation-release paths,
the checkout test fixes, the order fixture fix), the canonical simplification
review (no unjustified types), and the migration retrospective below. The full
quality gate (`./kotlin check`) is green including all fixes. The consolidated
findings and outcomes are posted on PR #75. The who-loses ordering recorded in
D17 was confirmed by Joe on 2026-08-03; no council item remains open.

## Task parameters

Target module:

`checkout`

Source feature:

`../voenix-shop/backend/Voenix.Api/Features/Checkout` (controller, service,
DTOs, exceptions; ~440 lines), plus the checkout rows of
`ErrorHandling/DomainExceptionHandler.cs`, the checkout half of
`Features/Promotion/Services/PromotionApplicationService.cs`
(`ValidateForCheckoutAsync`, `ActiveReservationOrders`), and
`Features/Cart/Domain/CartTotalsCalculator.cs` as evidence for the already
ported `CartTotals`.

Target package:

`backend/modules/checkout/src/shop/voenix/checkout`

Analysis checkpoint:

`wait-for-approval` — satisfied: the council plan was approved by Joe on
2026-08-02 (decision log below).

Known consumers:

- Vue frontend `../voenix-shop/frontend`: `src/stores/shop/checkout.ts`
  (`submitCheckout` posts `{shippingAddress{…, email, phone}, billingAddress?}`
  to `POST /api/checkout`; `fetchOrderStatus` still calls the removed
  `/api/checkout/orders/{id}` and is already listed as open frontend work in
  [`order-post-migration.md`](order-post-migration.md)).
- The four modules it composes: cart, order, payment, promotion.

Approved deviations from current behavior:

- See the deviation log below (all approved 2026-08-02 unless noted).

Explicitly deferred work:

- Shipping-country policy (whether the shop refuses unsupported destination
  countries, and where) — deferred as an open product decision; see
  [`all-post-migration.md`](all-post-migration.md).
- Admin visibility of orphaned promotion reservations — belongs to the
  admin-dashboard anomaly page recorded in
  [`payment-post-migration.md`](payment-post-migration.md) (consequence of
  deviation D2).
- Frontend adaptation of `checkout.ts` (new field shapes, retry endpoint) —
  frontend work, tracked with the other frontend items in the post-migration
  files.

## Analysis deliverable

### Behavior matrix

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| Checkout requires an active cart with at least one line; otherwise 400 "Cart is empty" | `CheckoutService.CheckoutAsync` lines 34–45; `DomainExceptionHandler` 186 | Required | `CheckoutCarts.activeCart` null/empty → 400 `CART_EMPTY` | Route test |
| Subtotal is the sum of the cart's stored line snapshots `(price + promptPrice) × quantity` | `CheckoutAsync` line 47 | Required | Cart capability answers the priced snapshot; sum accumulated in `Long` (D13) | Unit + integration test |
| Shipping: 0 for subtotal ≤ 0 or ≥ 5000 cents, else 490 | `CartTotalsCalculator.CalculateShippingCost` | Required | Already ported: `CartTotals.shippingCents` | Existing unit tests |
| Discount against subtotal + shipping, percentage capped at 100, `AwayFromZero` rounding, capped at the base | `CartTotalsCalculator.CalculateDiscountAmount` | Required | Already ported: `CartTotals.discountCents` (`HALF_UP`) | Existing unit tests |
| The promotion is re-validated when the checkout starts, under a `FOR UPDATE` lock on the promotion row: active flag, activity window, customer eligibility, usage limits including in-flight capacity | `ValidateForCheckoutAsync`, `ActiveReservationOrders`; `CheckoutControllerTests.Checkout_RejectsPromotionThatBecameInvalid`, `…_RejectsPromotionReservedByInFlightOrder` | Required | `PromotionCodes.reserve(promotionId, cartId, userId)`: own transaction, row lock, window + limits counting redemptions **and** reservations, upsert keyed on the cart (D1) | PostgreSQL concurrency test: two carts race the last unit, exactly one 201 |
| In-flight capacity is counted by the cart apply path too, not only at checkout | `ValidateCoreAsync` counts `ActiveReservationOrders` unconditionally; the five reservation tests are `CartServiceTests` | Required (missing from the Kotlin cart today) | `PromotionCodes.validate` gains `reservationKey` and counts reservations, excluding the caller's own cart (D5) | Cart apply test: code held by another cart's reservation is rejected; re-apply by the holder is not |
| The redemption is recorded at payment time and re-checks only limits, never the window | `PaidOrderProcessor` lines 50–66; [`promotion-post-migration.md`](promotion-post-migration.md) | Required (already delivered by Order) | `redeem` stays limits-only and additionally consumes the order's own reservation atomically (D1). Unlike `PaidOrderProcessor` it counts the reservations of *other* carts against both limits — the mechanism behind D4's "competes for remaining capacity at redeem" (D17) | Redemption deletes the reservation; capacity not double-counted; `PromotionReservationsIntegrationTest` proves the counting |
| The order is created `PENDING` with frozen address/price snapshot; billing falls back to shipping | `CheckoutAsync` lines 70–120 | Required (already delivered by `OrderService.place`) | `OrderPlacement.place(PlaceOrderInput)` | Existing order tests; checkout integration test |
| `email`/`phone` are read from the shipping address only; the billing copies are never read | `CheckoutAsync` lines 93–94 | Required | Kept: contact fields live on `shippingAddress` (D11); billing input has no contact fields, extra JSON keys are ignored | Route test posting the exact frontend shape |
| Total 0: the order is confirmed paid immediately, no payment exists, `checkoutUrl` is `null` | `CheckoutAsync` lines 126–132; `Checkout_ConfirmsZeroTotalPromotionOrderWithoutPayment` | Required | `OrderPaymentGateway.confirm(orderId)`, then `markCheckedOut` (order swapped, D6) | Integration test: PAID, redemption present, no payment row, cart CHECKED_OUT |
| Total > 0: a payment is created and the customer receives `{orderId, checkoutUrl}` with 201 | `CheckoutAsync` lines 137–160 | Required | `PaymentStarter.start(PayableOrder)` | Integration test with Mollie stub |
| Payment-creation failure: the order is cancelled, the cart stays ACTIVE, the error surfaces | `CheckoutAsync` catch block; `Checkout_CancelsPromotionOrderWhenPaymentCreationFails` | Required | Compensation already lives inside the payment module (Payment D10); checkout answers 502 `PAYMENT_NOT_STARTED` without claiming the order state (D7) and does **not** mark the cart | Integration test: order CANCELLED, cart ACTIVE, reservation released (D3) |
| The cart becomes `CHECKED_OUT` exactly when the checkout succeeded | `MarkCartCheckedOutAsync` call sites | Required | `CheckoutCarts.markCheckedOut(cartId)` after a checkout URL exists / after the free-order confirm | Integration test both paths |
| A double-submitted checkout is harmless | Legacy: timing-dependent (second submit usually hits the checked-out cart); Kotlin: `ux_orders_live_cart` | Required | `AlreadyPlaced` is a success answering the winning order; `start` answers the existing URL without a provider call (D15) | Concurrency test: two simultaneous checkouts → one order, one payment row, identical 201 bodies |
| Checkout works for guests via the guest token | `GetGuestToken` in the controller | Required | Guest-capable CSRF-protected route subtree; token read, never minted (D8) | Route test without login; no `Set-Cookie` on checkout |
| Order id and guest token are logged on creation | `LogOrderCreated` | Incidental (token is a bearer credential) | Order id only (D9) | `CheckoutServiceTest`: a full checkout with a distinctive guest token, captured on the `shop.voenix.checkout` logger — the order id appears in a message, the token appears in none |
| `GET /api/checkout/orders`, `GET /api/checkout/orders/{id}` | Legacy routes | Already migrated | Delivered as `/api/orders` by the Order migration; the four read DTOs are not ported | n/a |
| Retry payment for an order whose payment ended terminally | No legacy equivalent; owed by [`payment-post-migration.md`](payment-post-migration.md) | Behavior extension | `POST /api/checkout/orders/{orderId}/payment` (D16) | Route + integration tests, incl. IDOR (foreign order → 404, no provider call) |

### Operation contract

| Operation | Required input | Required success value | Required errors | Ordering |
| --- | --- | --- | --- | --- |
| Checkout (`POST /api/checkout`) | `{shippingAddress{firstName, lastName, street, houseNumber, postalCode, city, country, email, phone?}, billingAddress?{postal fields only}}` | `201 {orderId, checkoutUrl\|null}`, `Location: /api/orders/{orderId}` | 400 validation; 400 `CART_EMPTY`; 400/403/409 `PROMOTION_*` (Cart's exact matrix); 409 `CART_ITEM_UNAVAILABLE`; 409 `CART_IMAGE_UNAVAILABLE`; 409 `CART_TOTAL_TOO_LARGE`; 502 `PAYMENT_NOT_STARTED`; 500 | n/a |
| Retry payment (`POST /api/checkout/orders/{orderId}/payment`) | none (path id only) | `200 {orderId, checkoutUrl}` | 404 unknown **and** foreign ids; 409 `ORDER_ALREADY_PAID`; 409 `ORDER_NOT_PAYABLE` (cancelled or free); 502 `PAYMENT_NOT_STARTED`; 500 | n/a |

Example request (the shape the Vue store sends today — billing contact fields
arrive and are ignored):

```json
{
  "shippingAddress": {
    "firstName": "Ada", "lastName": "Lovelace",
    "street": "Musterweg", "houseNumber": "12a",
    "postalCode": "80331", "city": "München", "country": "DE",
    "email": "ada@example.org", "phone": ""
  },
  "billingAddress": null
}
```

Example response:

```json
{ "orderId": 4711, "checkoutUrl": "https://www.mollie.com/checkout/select-method/abc123" }
```

Note the deliberate asymmetries: `phone: ""` in the request normalizes to
`null` (D12) and never round-trips; a free order answers the same shape with
`"checkoutUrl": null`; the retry endpoint answers the identical body with 200.

### Kotlin design

**New compilation module `checkout`** — stateless: no table, no Exposed
dependency, no exported capability. Flat package, 7 production types:

| File | Type |
| --- | --- |
| `CheckoutModule.kt` | internal handle + `createCheckoutModule` + `installCheckoutModule` (production overload public, operations overload as route-test seam) + `validateCheckoutRequests` |
| `CheckoutOperations.kt` | internal interface: `checkout(guestToken, userId, request)`, `startPayment(orderId, guestToken, userId)` |
| `CheckoutService.kt` | the orchestration; split into placement and settlement helpers rather than one long function |
| `CheckoutRequest.kt` | `@Serializable` input with nested `ShippingAddressInput` (postal + contact fields) and `AddressInput` (postal only); pure `validate()`; normalization maps blank optionals to `null` |
| `CheckoutResponse.kt` | `{orderId, checkoutUrl?}` — serves both routes |
| `CheckoutResult.kt` | internal sealed result (`Started`, `EmptyCart`, `PromotionRejected(reason)`, `ItemUnavailable`, `ImageUnavailable`, `TotalTooLarge`, `PaymentNotStarted`, `OrderNotFound`, `OrderNotPayable.AlreadyPaid`, `OrderNotPayable.NotPayable`, `Invalid`, `UnexpectedFailure`). `OrderNotPayable` is a nested sealed interface with exactly those two variants, because cancelled and free are one sentence to the customer; the order module's `PayableOrderResult` keeps the four-way distinction its own callers need |
| `CheckoutRoutes.kt` | the two routes under `/api/checkout`, guest-capable CSRF-protected subtree, `Cache-Control: no-store` |

**New public capabilities on existing modules:**

- cart — `CheckoutCarts` on the `CartModule` handle:
  `activeCart(guestToken): CheckoutCart?` — no user id, because a cart is found
  by its guest token alone — (cartId, promotionId, lines,
  `Long` subtotal, shipping, `discountCents(discount)` as a method so the
  arithmetic stays in `CartTotals`) and `markCheckedOut(cartId)` (idempotent,
  `ACTIVE → CHECKED_OUT`).
- order — `OrderPlacement` on the `OrderModule` handle:
  `place(input): OrderPlacementResult` and the retry read
  `payable(orderId, userId, guestToken): PayableOrderResult`
  (`Payable(PayableOrder)`, `NotFound` for unknown *and* foreign,
  `AlreadyPaid`, `Cancelled`, `Free`). `PlaceOrderInput` and its `Address`
  become public; the placement result's success variants carry the new public
  `PayableOrder` (orderId, totalCents, email, phone, both addresses) instead of
  the internal `OrderView`.
- payment — `PaymentStarter` on the `PaymentModule` handle:
  `start(order: PayableOrder): String?`. **`PaymentRequest` is deleted** (D14);
  the payment module consumes the order-declared exchange type, the pattern
  `OrderPaymentGateway` established.
- promotion — the reservation lifecycle on `PromotionCodes`:
  - `reserve(promotionId, cartId, userId)` — own transaction, promotion row
    lock, checks active flag + window + eligibility + limits (redemptions plus
    reservations, excluding this cart), upserts the reservation;
  - `release(cartId)` — joins the caller's transaction like `redeem`; one
    `DELETE`, idempotent;
  - `releaseAbandoned(cartId)` — the same `DELETE` in a transaction of its own,
    for callers that have none to join (Phase 3): the checkout module, which
    owns no database at all, and the cart's coupon removal;
  - `redeem(promotionId, orderId, cartId, userId)` — unchanged semantics
    (limits-only, caller's transaction) and additionally consumes this cart's
    reservation atomically with the redemption insert;
  - `validate(code, userId, reservationKey)` — now counts reservations,
    excluding `reservationKey` (D5);
  - the private `toApiError` mapping of `PromotionCodeResult` moves from
    `CartRoutes` into the promotion module; cart and checkout share it.
  - The cart is the reservation's identity, deliberately (V18's `cart_id`
    UNIQUE). One consequence is worth naming: a customer who swaps the cart's
    coupon between two overlapping submissions overwrites the hold, and the
    winning order's eventual `redeem` consumes whatever row the cart holds at
    that moment — accepted with the cart-is-identity decision.
- The release has four callers. Two of them join a transaction and use
  `release`: `OrderRepository.markCancelled` (the D10 compensation, D3) and the
  terminal-payment-end path (D4) — when the payment module applies a terminal
  status (`FAILED`/`EXPIRED`/`CANCELED`) to a stored payment, it notifies the
  order module (a new member on the order-declared gateway vocabulary, e.g.
  `OrderPaymentGateway.paymentEnded(orderId)`), which releases the reservation
  of that order's cart while leaving the order `PENDING` (Payment D9 untouched).
  The other two own no transaction and use `releaseAbandoned` (both added in
  Phase 3, because both are deterministic orphan paths a TTL-less reservation
  would block forever):
  - `CheckoutService.checkout` when the placement refuses terminally —
    `Invalid`, `UnknownArticleReference`, `UnknownPrintImage`. No order exists
    that could ever release the hold, and the refusal repeats on every retry, so
    the checkout gives back what it reserved a moment earlier. It runs under
    `NonCancellable`: the customer who closes the tab on the error is exactly
    the one who never comes back;
  - `CartService.removePromotion` (i.e. `setPromotion(owner, null)`) when the
    write succeeded — the customer taking the coupon off a cart whose earlier
    checkout left a hold behind. Applying or replacing a coupon releases
    nothing: the reservation is keyed on the cart, so the next `reserve`
    overwrites the same row. The write and the release are not one transaction,
    which is deliberate — the release is idempotent, and a failure between them
    leaves exactly the reservation that already existed.

**Flow of `POST /api/checkout`** (five independent commits, no distributed
transaction; every gap is covered by a designed mechanism):

1. Read the guest token (never mint, D8) and the active cart snapshot; empty →
   400. Totals accumulate in `Long`; a total that cannot fit `Int` cents → 409
   (D13).
2. Cart carries a promotion → `reserve` (T1: lock, check, upsert, commit).
   Failure → the `PROMOTION_*` answer.
3. `place` (T2: order module's own transaction; `23505` on
   `ux_orders_live_cart` → `AlreadyPlaced` = success with the winning order).
4. Total 0 → `confirm` (T3: order row lock, `redeem` joins — promotion lock,
   redemption insert, reservation delete — production request, mail), then
   `markCheckedOut`, answer `{orderId, null}`.
5. Total > 0 → `start` (provider call outside any transaction; insert under
   `ux_payments_live_order`; its own compensation cancels the order on refusal,
   and that cancellation now releases the reservation). `null` → 502 without
   marking the cart. URL → `markCheckedOut` (T5), answer `{orderId, url}`.

Lock order stays acyclic: `reserve` locks `promotions` and nothing else;
`confirm` locks `orders`, then `promotions` (the redemption). `cancel` and
`paymentEnded` lock `orders` and then delete `promotion_reservations` rows
*without* locking the promotion row — the graph stays acyclic because nothing
holds a reservation row and then wants `promotions`, and the READ-COMMITTED
consequence is conservative: a concurrent uncommitted release makes `reserve`
refuse rather than over-issue. `reserve` only counts other carts' rows.

**Validation boundary, decided:** the field rules live in
`CheckoutRequest.validate()` and run at the HTTP boundary through the shared
Request Validation plugin. `CheckoutService` deliberately does not re-run them:
`CheckoutOperations` is `internal`, its only production caller is the route
behind that plugin, and the one refusal left (`CheckoutResult.Invalid`) is by
design "the checkout assembled something the placement refuses" — this module's
own bug, never a client's.

**Composition root:** install between cart and account;
`validateCheckoutRequests()` joins the single `RequestValidation` block;
`order.payments` gains checkout as its second consumer (only `confirm`).
Register `modules/checkout` in `project.yaml` and `app/module.yaml`.

**Flyway `V18__create_promotion_reservations.sql`** (a promotion-owned table;
the file itself lives with every other migration in
`backend/modules/platform/resources/db/migration`):

- `id bigint identity PK`; `promotion_id bigint NOT NULL` FK → `promotions`
  `ON DELETE RESTRICT`; `cart_id bigint NOT NULL UNIQUE` FK → `carts`
  `ON DELETE CASCADE`; `user_id bigint NULL` FK → `users` `ON DELETE SET NULL`;
  `created_at timestamptz NOT NULL`. **No `expires_at`** (D2).
- Indexes: `(promotion_id)` for the total count, `(promotion_id, user_id)` for
  the per-user count.
- Promotion delete: a live reservation reports the existing `InUse` conflict
  through the `RESTRICT` foreign key.

### Promotion-module fixes this migration owns

- Consequence found while implementing T1: the reservation statements pushed
  `PromotionRepository` over Detekt's per-class limit. Split rather than
  suppressed — the statements now live with the table object they touch
  (`Promotions`, `PromotionRedemptions`, `PromotionReservations`), while
  `PromotionRepository` keeps only what is actually a decision: which
  transaction a write belongs to, which lock it takes, and what it answers.
  Every statement helper is an `…InTransaction` function that joins whatever
  transaction the repository opened, so nothing about the lock semantics moved.

### Cart-module fixes this migration owns

- `CartRepository.findOrCreateLockedCartInTransaction`'s
  `checkNotNull` becomes reachable once `CHECKED_OUT` is written concurrently
  with a cart mutation → bounded single retry (the shape
  `OrderRepository.place` already uses). Found independently by both council
  members.
- `CartService.render` computes the subtotal in `Int` and wraps silently;
  fixed to `Long` together with D13, in the same ticket as the capability.
- Consequence found while implementing T2: `CartRepository` was one function
  below Detekt's per-class limit, so the capability's `markCheckedOut` tipped
  it over. Split rather than suppressed — the standalone print-image registry
  (`insert`, `find`) moved into its own `PrintImageRepository`, and applying
  and removing a cart promotion became the one write they always were
  (`setPromotion(owner, promotionId?)`). The `print_images` rows a cart
  transaction must decide *together with* its own write — the ownership check
  of an add and the guest claim — stay in `CartRepository`, because they have
  to commit with it.

### Payment-module fixes this migration owns

- Consequence found while implementing T4: the payment module's `PaymentService`
  was one function below Detekt's per-class limit, so `start` and its four
  helpers tipped it over. Split rather than suppressed, along the seam the class
  KDoc had already named: *creating* a payment moved into a new internal
  `PaymentLauncher : PaymentStarter` — the one place where three parties act at
  once (the customer clicking twice, Mollie, and the database), and where every
  method exists for one interleaving of them — while *reading one back*, the
  webhook confirm and the two status calls, stayed in `PaymentService`, because
  each of those starts from a row that already exists.
- `PaymentRequest` is deleted with T4 (D14). The historical records that still
  name it — [`payment-migration.md`](payment-migration.md)'s type table and
  operation contract — describe the payment module as it was migrated on
  2026-08-01 and are left standing as history; the deletion is recorded in their
  deferred-work sections.

### App-module fixes this migration owns

- Consequence found while implementing T5: installing the checkout module made
  `Application.installModules` exceed Detekt's `LongMethod` limit. Split rather
  than suppressed: the seven master-data modules an admin maintains and every
  customer-facing module only reads — countries, VAT rates, suppliers, prices,
  promotions, articles, prompts — moved into the app-owned `CatalogRuntime` and
  `installCatalogRuntime`, in the identical install order. Four of the seven are
  consumed inside that group alone, so the composition root now names only the
  three capabilities that leave it: `articles`, `prompts`, and `promotionCodes`.

### Test plan

PostgreSQL through Testcontainers wherever PostgreSQL decides; every fake that
stands in for a suspending capability must suspend where the real one does.

- **Concurrency:** two simultaneous checkouts of one cart → one order, one
  payment row, identical 201 bodies. Two carts race the last unit of a
  `usage_limit_total = 1` promotion → exactly one 201 and one 409
  `PROMOTION_TOTAL_EXHAUSTED` (fixture: limit 1, two distinct carts, so only
  the limit under test can fail). `markCheckedOut` concurrent with `addItem` →
  *exercises* the bounded retry; the window cannot be staged deterministically,
  so a quiet run proves nothing (see D18).
- **Reservation lifecycle:** re-reserving the same cart does not double-count;
  paying converts the reservation into the redemption and frees nothing early;
  the D10 cancellation releases it in the same transaction; a terminal webhook
  releases it while the order stays `PENDING`; a released reservation frees the
  last unit for another cart.
- **Window vs. limits split:** a promotion expiring between apply and checkout
  → 400 `PROMOTION_EXPIRED` at checkout start; one expiring between checkout
  and webhook still redeems (`redeem` stays limits-only).
- **Cart apply parity (D5):** a code held by another cart's reservation is
  rejected at apply; the holding cart may re-apply its own code.
- **Flows:** free order (PAID, redemption present, no payment row, cart
  `CHECKED_OUT`, `checkoutUrl: null`); provider refusal (order `CANCELLED` by
  the payment module, cart **stays ACTIVE**, reservation gone, 502 whose body
  does not claim a cancellation); D21-shaped `null` (order stays `PENDING`,
  502); retry against a live payment (same URL, zero provider calls); retry
  against a terminal payment (second `payments` row); retry on a foreign order
  (404 and **no** provider call); two simultaneous retries (one live payment).
- **Request shape:** the validator matrix once, in unit tests; a fixture that
  posts the exact frontend JSON incl. `phone: ""` (must succeed, stored phone
  `null`) and billing contact fields (ignored); rejected requests never invoke
  the operation (HTTP-before-operation test).
- **Schema:** `V18` on an empty database; each constraint tripped by a seed
  that can violate only the rule under test.
- **Overflow (D13):** a cart whose line prices sum beyond `Int` → 409, nothing
  written, no reservation taken.

#### Where the plan landed (T6, 2026-08-02)

The module-level rows are covered by the suites of the module that owns the
behavior: `PromotionReservationsIntegrationTest` and
`PromotionSchemaIntegrationTest` (reservation lifecycle, the `V18` constraints,
the two concurrent reserves), `CartCheckoutIntegrationTest` (snapshot, idempotent
close, the `Int`-overflow cart, the add racing a checkout),
`OrderPlacementCapabilityIntegrationTest` and
`OrderCancellationIntegrationTest`/`OrderPaymentEndedIntegrationTest` (placement,
`AlreadyPlaced`, the payable matrix, both release paths),
`PaymentIdempotencyIntegrationTest` (the creation race), and the three checkout
suites (request shape, route matrix, service ordering).

The cross-module matrix runs against the **composed** application on
Testcontainers PostgreSQL with a local Mollie stub, in the app module next to the
other composition tests:

| Suite | Matrix rows |
| --- | --- |
| `CheckoutFlowCompositionIntegrationTest` | free order end to end; provider refusal (order `CANCELLED`, cart `ACTIVE`, reservation released, `502` claiming nothing); the D21-shaped `null` (order stays `PENDING`, reservation kept); window vs. limits, both halves |
| `CheckoutConcurrencyCompositionIntegrationTest` | double submit (one order, one payment, identical `201` bodies); two carts racing a `usage_limit_total = 1` coupon (one `201`, one `409 PROMOTION_TOTAL_EXHAUSTED`); two simultaneous retries (one live payment) |
| `CheckoutRetryCompositionIntegrationTest` | retry against a live payment (same URL, zero provider calls); terminal webhook releases the reservation with the order left `PENDING`, and the retry then writes a second `payments` row; foreign order → `404` without a provider call; cart-apply parity (D5) |

Two of them need a determinism trick rather than a race, and both are documented
in `CheckoutMollieStub`: the double submit holds the *first* provider creation on
a latch until the second submission has finished, and the D21 shape is produced
by a provider answering a payment id that is already stored — the insert then
conflicts on `ux_payments_mollie_payment_id` while the order's live slot stays
free, so the payment module reaches the same `null`. It reproduces that *answer*,
not the doubly-vacated race that also produces it; the duplicated id itself stays
open at the provider, because it belongs to another order's live payment.

## Decision log

### 2026-08-01 — Phase-1 council brainstorming

Three independent proposals (orchestrator, Opus `council-opus`, Codex
`gpt-5.6-sol`) on one briefing; one rebuttal round. Consensus reached on the
module cut, the promotion-owned reservation, deleting `PaymentRequest` in
favor of the order-owned `PayableOrder` (Codex conceded), no country-list
validation (Codex conceded; Opus's frontend-fallback evidence:
`createEmptyAddress()` hardcodes `'DE'`), `release` on the D10 cancellation
(Opus conceded to Codex's clustering argument), `validate` counting
reservations (Opus's find, verified by Codex against
`ValidateCoreAsync`), contact fields staying on `shippingAddress` (Codex's
frontend evidence; Opus withdrew its move-to-root deviation), and the `Long`
overflow guard (Opus verified `price_cents` has no upper bound and
`CartService.render` already wraps).

### 2026-08-02 — Joe's Phase-1 decisions

1. **Reservations have no TTL — strictly lifecycle-bound** (Joe, overriding
   the council's 15/30-minute debate): a reservation ends only through
   `redeem` (payment succeeded), `release` on the D10 cancellation,
   `release` when a payment ends terminally, or — added in Phase 3 —
   `releaseAbandoned` when a checkout attempt gives the coupon up again
   (a terminal placement refusal, or the customer removing the code). Joe
   accepts the named consequence: a crash between the reservation commit and
   the placement, or a terminal webhook that is never delivered at all,
   leaves a reservation that blocks its capacity **forever** until admin
   tooling exists (deferred to the anomaly page in
   [`payment-post-migration.md`](payment-post-migration.md)).
2. **`release` is built** and called from the order-cancellation transaction.
3. **Retry endpoint** is `POST /api/checkout/orders/{orderId}/payment` — the
   module owns its path prefix.
4. **Country-list validation is deferred**; only the two-letter format check
   runs. The shipping-country policy moves to
   [`all-post-migration.md`](all-post-migration.md) as an open product
   decision. *(Answered after the migration: Joe decided it on 2026-08-04 and it
   was implemented as issue #81 — the shipping country must be a row of
   `countries`, checked by the checkout service through the country module's
   `ShippableCountries` capability.)*

## Deviation and uncertainty log

| # | Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- | --- |
| D1 | In-flight promotion capacity = a query over pending orders and payment statuses | `ActiveReservationOrders` | A promotion-owned `promotion_reservations` row keyed on the cart, counted next to redemptions | Proposed deviation | Joe 2026-08-02 | — |
| D2 | Orders without payment reserve for 15 min; live payments reserve unbounded | `PromotionLimits.PendingOrderReservationMinutes` | **No TTL at all**: reservations end only via `redeem`/`release`/`releaseAbandoned`. Every *deterministic* end of a checkout now releases — the D10 cancellation, the terminal payment end, a terminal placement refusal, and the customer removing the coupon (the last two added in Phase 3). What blocks forever until admin tooling exists is only what no code path can observe: a crash between the reservation commit and the placement, and a terminal webhook that is never delivered at all — the merely *lost* one now heals through the redelivery notification (Phase 3, payment) | Proposed deviation | Joe 2026-08-02, consequence explicitly accepted | Anomaly page lists orphaned reservations ([`payment-post-migration.md`](payment-post-migration.md)) |
| D3 | A cancelled order stops reserving immediately (`status = Pending` predicate) | `ActiveReservationOrders` | `release(cartId)` inside `markCancelled`'s transaction — same immediacy, explicit mechanism | Required (mechanism differs) | Joe 2026-08-02 | — |
| D4 | An order whose payment ended terminally stops reserving | payment-status predicate in `ActiveReservationOrders` | The terminal transition notifies the order module (`paymentEnded`), which releases the reservation; the order stays `PENDING` (Payment D9). Since Phase 3 a *redelivered* terminal status notifies again — the release is idempotent, so Mollie's redelivery is the durable retry for a notification lost to a cancelled webhook job or a database failure. A later retry does **not** re-reserve: it competes for remaining capacity at `redeem`, so the D22 outcome (PAID without redemption) is the accepted worst case (see D17 for who loses) | Proposed deviation | Joe 2026-08-02 | — |
| D5 | The cart apply path counts in-flight capacity | `ValidateCoreAsync` counts unconditionally; five `CartServiceTests` | `validate` gains `reservationKey`, counts reservations excluding the caller's cart — a **restoration**; today's Kotlin cart misses this | Required | Council consensus | Delivered with T1 |
| D6 | Free order: cart `CHECKED_OUT`, then paid processing | `CheckoutAsync` 127–129 | `confirm` first, then `markCheckedOut`: a failure leaves an ACTIVE cart whose re-submission heals via `AlreadyPlaced`; legacy's order can strand a checked-out cart with an unconfirmed order | Proposed deviation | Council consensus, Joe 2026-08-02 | — |
| D7 | Payment-creation failure surfaces as the provider exception's 502 after the service cancelled the order | `CheckoutAsync` catch; `DomainExceptionHandler` | 502 `PAYMENT_NOT_STARTED` whose message does **not** claim the order was cancelled — `start == null` covers both Payment D10 (cancelled) and D21 (still `PENDING`) and checkout cannot tell them apart | Proposed deviation | Council consensus | Frontend copy must stay vague too |
| D8 | `GetOrCreateGuestToken` mints a guest cookie on checkout | `CheckoutController` | The token is read, never minted: without a cookie there is no cart, so the answer is the same 400 and no `Set-Cookie` | Proposed deviation (minor) | Council consensus | — |
| D9 | The guest token is logged on order creation | `LogOrderCreated` | Never logged (bearer credential; Order D17 parity) | Incidental | Council consensus | — |
| D10 | No country-list validation at checkout | Unused `Country.Domain` import; `AddressDto.Country` is a plain string | Same: two-letter format check only | Required (parity confirmed) | Joe 2026-08-02 | **Resolved after the migration (issue #81, 2026-08-04):** the shipping country must be a row of `countries`, checked by `CheckoutService` through the country module's `ShippableCountries` capability and answered as a field error on `shippingAddress.country`. The billing address stays unrestricted and the shape check in `CheckoutRequest` is unchanged → [`all-post-migration.md`](all-post-migration.md) |
| D11 | `email`/`phone` sit on every address; only the shipping copies are read | `CheckoutAsync` 93–94; Vue store serializes them on billing too | Contact fields exist only on `ShippingAddressInput`; billing's extra JSON keys are ignored by the serializer config | Required shape, trimmed inputs | Council consensus | — |
| D12 | The frontend always sends `phone: ""` when empty; legacy stores the empty string | `composePhoneNumber` returns `''`; `orders.phone` nullable | Blank optional strings normalize to `null` after validation — without this, `PlaceOrderInput` rejects **every** phoneless checkout | Required | Council consensus | Test fixture must send `phone: ""` |
| D13 | Totals arithmetic in 32-bit int, no upper price bound anywhere | `V15__create_carts.sql`: `quantity ≤ 99` but `price_cents` unbounded; `CartService.render` sums in `Int` | `Long` accumulation; carts beyond `Int` cents → 409 `CART_TOTAL_TOO_LARGE`; same fix applied to the cart's own `render` | Proposed deviation (guards an existing silent-wrap bug) | Council consensus | — |
| D14 | `CreatePaymentRequest` is the payment feature's input DTO | `Payment.Dtos` | `PaymentRequest` deleted; `PaymentStarter.start(PayableOrder)` consumes the order-declared snapshot (the `OrderPaymentGateway` pattern) | Internal, not observable | Council consensus | — |
| D15 | A double submit either errors on the checked-out cart or creates a second order, depending on timing | Legacy has no live-order index | `AlreadyPlaced` is a success: both submissions answer the same `orderId` and the same checkout URL; an edited second request is silently ignored in favor of the winning order | Proposed deviation | Council consensus | Frontend note |
| D16 | No retry-payment journey exists | — | `POST /api/checkout/orders/{orderId}/payment`: no body, ownership-checked (foreign = 404, no provider call), built exclusively from the stored order snapshot, live payment → same URL, terminal → second payment row, 409 for paid/cancelled/free orders | Behavior extension | Joe 2026-08-02 (path); council (behavior) | Frontend journey |
| D17 | `redeem` re-checks the usage limits against recorded redemptions only | `PaidOrderProcessor.cs` counts `PromotionRedemptions` alone | `redeem` counts the reservations of every *other* cart against **both** limits. In the normal path the term can never fire — `reserve`'s own check guarantees `redemptions + other reservations ≤ limit − 1` while the order still holds its reservation — so it decides exactly the D4 case: an order whose reservation was released and whose retried payment then competes at `redeem`. The counter-intuitive ordering: the customer who *paid first* can end `PAID` without a redemption, losing to a cart that has merely reserved and may never pay. Both designs respect the limit; they differ in who loses | Proposed deviation (surfaced in phase 3; the matrix had called `redeem` "Unchanged") | Behavior covered by D4 (Joe 2026-08-02, "competes for remaining capacity at redeem"); the who-loses ordering was confirmed by Joe on 2026-08-03 | — |
| D18 | A cart mutation that commits between the checkout's snapshot and `markCheckedOut` is silently absorbed into the checked-out cart; the added line never reaches the order | Legacy `CheckoutAsync` has the identical unlocked window: it reads the cart, places the order, runs the provider call, and marks the cart checked out with no lock across the span | Same window, retained. It is **not** narrow — it spans the reservation, the placement, and the Mollie round trip, i.e. seconds. The order is correct for what was snapshotted; the customer's loss is one un-ordered line, recoverable by re-adding. The cheap optimistic close (`markCheckedOut … AND updated_at = <snapshot>`) was deliberately rejected: the order is already placed from the old snapshot by then, so a refused close would leave an `ACTIVE` cart holding a line that was never ordered and break "the cart becomes `CHECKED_OUT` exactly when the checkout succeeded". A snapshot/finalization protocol is explicitly deferred | Retained legacy limitation | Phase-3 council 2026-08-03 (Codex found it; consensus to document, not build) | The cart race test *exercises* the bounded retry but cannot stage this window; its wording was corrected in the same edit |

## Migration retrospective

Completed 2026-08-03, after the phase-3 verification (three independent
reviews, one rebuttal round, four fix tickets) and the simplification review.
The simplification review found no unjustified types: no list wrappers, no
TODOs, no constraint-name inspection outside SQL and schema tests;
`OrderNotPayable` was collapsed to two variants during the fixes, and
`CheckoutResult.Invalid` was deliberately *kept* apart from `UnexpectedFailure`
because the type carries the tested claim "this 500 is the checkout's own bug,
never a client's".

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| A matrix cell claiming "Unchanged" survived four tickets while the code deliberately changed the behavior; the implementation KDoc documented the change the record denied | `redeem` counting other carts' reservations (D17); found only by the phase-3 Opus review | Record accuracy | Diffing each "Unchanged" matrix claim against the implementation at ticket acceptance would have caught it at T1 | Kept in this record; the skill already demands a current matrix while implementing — the gap was enforcement, not a missing rule |
| A determinism trick reproduced a race through a *different* mechanism than claimed, and the test asserted its foreign side effect (cancelling another order's provider payment) as correct | `CheckoutMollieStub.fixedPaymentId` → `ux_payments_mollie_payment_id`, not `ux_payments_live_order`; fixed in phase 3 (guard in `PaymentLauncher.cancelUnused`) | Data integrity | Writing the record sentence "is the state the race leaves behind" was the moment the difference was papered over | **Promoted to the guide** (tests section): a stand-in for a race must name its actual mechanism and assert differing side effects as known differences |
| Non-suspending fakes with a KDoc claiming they suspend — a verbatim repeat of Payment's phase-3 finding, with the guide rule already in place | `CheckoutServiceTest`'s five fakes; fixed in phase 3 | Test honesty | The simplification-review item ("verify against its *code*") is scheduled after implementation; running it at each ticket's acceptance would catch it earlier | Kept in this record as an enforcement note; no new rule — the rule exists and phase 3 caught it on schedule |
| Removing the reservation TTL left resource-release paths unenumerated: two deterministic flows (placement refusal, coupon removal) orphaned a hold forever, found independently by two reviewers | `CheckoutService` refusals, `CartService.removePromotion`; fixed in phase 3 (`releaseAbandoned`) | Design completeness | When an expiry is removed, every terminal outcome of the acquiring flow needs a named release path — a phase-1 checklist question | Proposed guide addition (persistence section), owner Joe: "a held resource without expiry needs an enumerated release path for every terminal outcome of the flow that acquired it" — declined by Joe on 2026-08-04: the migration is finished, so the guide gains no new rules; the lesson stays recorded here |
| The billing/shipping fixture gap in the `PayableOrder` read-back tests — a verbatim repeat of Order's phase-3 finding already recorded in the guide | `OrderPlacementCapabilityIntegrationTest` fixtures shared all address values; fixed in phase 3 | Test coverage | Same as above: the guide rule existed; reviewers caught the recurrence | Kept in this record; no new rule |
| The order-suite test-JVM failure from the T2 acceptance run reproduced once in phase 3 (first run: 8 × `SocketTimeoutException` opening connections; immediate re-run green, no code change) and was classified environmental by all three reviewers | T4 implementer run log; Opus's code-level analysis found no branch-specific timing dependency | Environment | — | **Promoted to `backend/AGENTS.md`** (quality-gates section): the symptom and the re-run-first instruction; plus the sequential-execution comment at `OrderTestSupport.seed` |
