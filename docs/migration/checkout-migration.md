# Checkout migration

## Status

`implementation`

Phase 1 (council brainstorming) is complete: three independent proposals
(orchestrator, Opus, Codex), one rebuttal round, and Joe's decisions of
2026-08-02 are recorded below.

Phase 2 is implemented on the `checkout-migration` branch: the six sub-tickets
T1–T6 are done — the promotion reservation lifecycle with `V18`, the cart's
`CheckoutCarts`, the order's `OrderPlacement` with the release hooks, the
payment's `PaymentStarter`, the checkout module itself with its composition, and
the cross-module test matrix plus this documentation sweep. Phase 3 — council
verification, simplification review, and retrospective — has not run yet, which
is why this record is not `complete`.

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
| The redemption is recorded at payment time and re-checks only limits, never the window | `PaidOrderProcessor` lines 50–66; [`promotion-post-migration.md`](promotion-post-migration.md) | Required (already delivered by Order) | Unchanged; `redeem` additionally consumes the order's own reservation atomically (D1) | Redemption deletes the reservation; capacity not double-counted |
| The order is created `PENDING` with frozen address/price snapshot; billing falls back to shipping | `CheckoutAsync` lines 70–120 | Required (already delivered by `OrderService.place`) | `OrderPlacement.place(PlaceOrderInput)` | Existing order tests; checkout integration test |
| `email`/`phone` are read from the shipping address only; the billing copies are never read | `CheckoutAsync` lines 93–94 | Required | Kept: contact fields live on `shippingAddress` (D11); billing input has no contact fields, extra JSON keys are ignored | Route test posting the exact frontend shape |
| Total 0: the order is confirmed paid immediately, no payment exists, `checkoutUrl` is `null` | `CheckoutAsync` lines 126–132; `Checkout_ConfirmsZeroTotalPromotionOrderWithoutPayment` | Required | `OrderPaymentGateway.confirm(orderId)`, then `markCheckedOut` (order swapped, D6) | Integration test: PAID, redemption present, no payment row, cart CHECKED_OUT |
| Total > 0: a payment is created and the customer receives `{orderId, checkoutUrl}` with 201 | `CheckoutAsync` lines 137–160 | Required | `PaymentStarter.start(PayableOrder)` | Integration test with Mollie stub |
| Payment-creation failure: the order is cancelled, the cart stays ACTIVE, the error surfaces | `CheckoutAsync` catch block; `Checkout_CancelsPromotionOrderWhenPaymentCreationFails` | Required | Compensation already lives inside the payment module (Payment D10); checkout answers 502 `PAYMENT_NOT_STARTED` without claiming the order state (D7) and does **not** mark the cart | Integration test: order CANCELLED, cart ACTIVE, reservation released (D3) |
| The cart becomes `CHECKED_OUT` exactly when the checkout succeeded | `MarkCartCheckedOutAsync` call sites | Required | `CheckoutCarts.markCheckedOut(cartId)` after a checkout URL exists / after the free-order confirm | Integration test both paths |
| A double-submitted checkout is harmless | Legacy: timing-dependent (second submit usually hits the checked-out cart); Kotlin: `ux_orders_live_cart` | Required | `AlreadyPlaced` is a success answering the winning order; `start` answers the existing URL without a provider call (D15) | Concurrency test: two simultaneous checkouts → one order, one payment row, identical 201 bodies |
| Checkout works for guests via the guest token | `GetGuestToken` in the controller | Required | Guest-capable CSRF-protected route subtree; token read, never minted (D8) | Route test without login; no `Set-Cookie` on checkout |
| Order id and guest token are logged on creation | `LogOrderCreated` | Incidental (token is a bearer credential) | Order id only (D9) | Log assertion in service test |
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
| `CheckoutResult.kt` | internal sealed result (`Started`, `EmptyCart`, `PromotionRejected(reason)`, `ItemUnavailable`, `ImageUnavailable`, `TotalTooLarge`, `PaymentNotStarted`, `OrderNotFound`, `OrderNotPayable`, `Invalid`, `UnexpectedFailure`) |
| `CheckoutRoutes.kt` | the two routes under `/api/checkout`, guest-capable CSRF-protected subtree, `Cache-Control: no-store` |

**New public capabilities on existing modules:**

- cart — `CheckoutCarts` on the `CartModule` handle:
  `activeCart(guestToken, userId): CheckoutCart?` (cartId, promotionId, lines,
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
  - `redeem(promotionId, orderId, cartId, userId)` — unchanged semantics
    (limits-only, caller's transaction) and additionally consumes this cart's
    reservation atomically with the redemption insert;
  - `validate(code, userId, reservationKey)` — now counts reservations,
    excluding `reservationKey` (D5);
  - the private `toApiError` mapping of `PromotionCodeResult` moves from
    `CartRoutes` into the promotion module; cart and checkout share it.
- The release has two callers: `OrderRepository.markCancelled` (the D10
  compensation, D3) and the terminal-payment-end path (D4): when the payment
  module applies a terminal status (`FAILED`/`EXPIRED`/`CANCELED`) to a stored
  payment, it notifies the order module (a new member on the order-declared
  gateway vocabulary, e.g. `OrderPaymentGateway.paymentEnded(orderId)`), which
  releases the reservation of that order's cart while leaving the order
  `PENDING` (Payment D9 untouched).

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

Lock order stays acyclic: `promotions` (reserve) — no other lock; `orders` then
`promotions` (confirm/cancel). `reserve` only counts other carts' rows.

**Composition root:** install between cart and account;
`validateCheckoutRequests()` joins the single `RequestValidation` block;
`order.payments` gains checkout as its second consumer (only `confirm`).
Register `modules/checkout` in `project.yaml` and `app/module.yaml`.

**Flyway `V18__create_promotion_reservations.sql`** (promotion module):

- `id bigint identity PK`; `promotion_id bigint NOT NULL` FK → `promotions`
  `ON DELETE RESTRICT`; `cart_id bigint NOT NULL UNIQUE` FK → `carts`
  `ON DELETE CASCADE`; `user_id bigint NULL` FK → `users` `ON DELETE SET NULL`;
  `created_at timestamptz NOT NULL`. **No `expires_at`** (D2).
- Indexes: `(promotion_id)` for the total count, `(promotion_id, user_id)` for
  the per-user count.
- Promotion delete: a live reservation reports the existing `InUse` conflict
  through the `RESTRICT` foreign key.

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
  proves the bounded retry.
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
conflicts while the order's live slot stays free, which is the state the
doubly-vacated race leaves behind.

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
   `redeem` (payment succeeded), `release` on the D10 cancellation, or
   `release` when a payment ends terminally. Joe accepts the named
   consequence: a crash between the reservation commit and the placement, or
   a permanently missed terminal webhook, leaves a reservation that blocks
   its capacity **forever** until admin tooling exists (deferred to the
   anomaly page in [`payment-post-migration.md`](payment-post-migration.md)).
2. **`release` is built** and called from the order-cancellation transaction.
3. **Retry endpoint** is `POST /api/checkout/orders/{orderId}/payment` — the
   module owns its path prefix.
4. **Country-list validation is deferred**; only the two-letter format check
   runs. The shipping-country policy moves to
   [`all-post-migration.md`](all-post-migration.md) as an open product
   decision.

## Deviation and uncertainty log

| # | Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- | --- |
| D1 | In-flight promotion capacity = a query over pending orders and payment statuses | `ActiveReservationOrders` | A promotion-owned `promotion_reservations` row keyed on the cart, counted next to redemptions | Proposed deviation | Joe 2026-08-02 | — |
| D2 | Orders without payment reserve for 15 min; live payments reserve unbounded | `PromotionLimits.PendingOrderReservationMinutes` | **No TTL at all**: reservations end only via `redeem`/`release`; crash orphans block forever until admin tooling | Proposed deviation | Joe 2026-08-02, consequence explicitly accepted | Anomaly page lists orphaned reservations ([`payment-post-migration.md`](payment-post-migration.md)) |
| D3 | A cancelled order stops reserving immediately (`status = Pending` predicate) | `ActiveReservationOrders` | `release(cartId)` inside `markCancelled`'s transaction — same immediacy, explicit mechanism | Required (mechanism differs) | Joe 2026-08-02 | — |
| D4 | An order whose payment ended terminally stops reserving | payment-status predicate in `ActiveReservationOrders` | The terminal transition notifies the order module (`paymentEnded`), which releases the reservation; the order stays `PENDING` (Payment D9). A later retry does **not** re-reserve: it competes for remaining capacity at `redeem`, so the D22 outcome (PAID without redemption) is the accepted worst case | Proposed deviation | Joe 2026-08-02 | — |
| D5 | The cart apply path counts in-flight capacity | `ValidateCoreAsync` counts unconditionally; five `CartServiceTests` | `validate` gains `reservationKey`, counts reservations excluding the caller's cart — a **restoration**; today's Kotlin cart misses this | Required | Council consensus | Delivered with T1 |
| D6 | Free order: cart `CHECKED_OUT`, then paid processing | `CheckoutAsync` 127–129 | `confirm` first, then `markCheckedOut`: a failure leaves an ACTIVE cart whose re-submission heals via `AlreadyPlaced`; legacy's order can strand a checked-out cart with an unconfirmed order | Proposed deviation | Council consensus, Joe 2026-08-02 | — |
| D7 | Payment-creation failure surfaces as the provider exception's 502 after the service cancelled the order | `CheckoutAsync` catch; `DomainExceptionHandler` | 502 `PAYMENT_NOT_STARTED` whose message does **not** claim the order was cancelled — `start == null` covers both Payment D10 (cancelled) and D21 (still `PENDING`) and checkout cannot tell them apart | Proposed deviation | Council consensus | Frontend copy must stay vague too |
| D8 | `GetOrCreateGuestToken` mints a guest cookie on checkout | `CheckoutController` | The token is read, never minted: without a cookie there is no cart, so the answer is the same 400 and no `Set-Cookie` | Proposed deviation (minor) | Council consensus | — |
| D9 | The guest token is logged on order creation | `LogOrderCreated` | Never logged (bearer credential; Order D17 parity) | Incidental | Council consensus | — |
| D10 | No country-list validation at checkout | Unused `Country.Domain` import; `AddressDto.Country` is a plain string | Same: two-letter format check only | Required (parity confirmed) | Joe 2026-08-02 | Shipping policy → [`all-post-migration.md`](all-post-migration.md) |
| D11 | `email`/`phone` sit on every address; only the shipping copies are read | `CheckoutAsync` 93–94; Vue store serializes them on billing too | Contact fields exist only on `ShippingAddressInput`; billing's extra JSON keys are ignored by the serializer config | Required shape, trimmed inputs | Council consensus | — |
| D12 | The frontend always sends `phone: ""` when empty; legacy stores the empty string | `composePhoneNumber` returns `''`; `orders.phone` nullable | Blank optional strings normalize to `null` after validation — without this, `PlaceOrderInput` rejects **every** phoneless checkout | Required | Council consensus | Test fixture must send `phone: ""` |
| D13 | Totals arithmetic in 32-bit int, no upper price bound anywhere | `V15__create_carts.sql`: `quantity ≤ 99` but `price_cents` unbounded; `CartService.render` sums in `Int` | `Long` accumulation; carts beyond `Int` cents → 409 `CART_TOTAL_TOO_LARGE`; same fix applied to the cart's own `render` | Proposed deviation (guards an existing silent-wrap bug) | Council consensus | — |
| D14 | `CreatePaymentRequest` is the payment feature's input DTO | `Payment.Dtos` | `PaymentRequest` deleted; `PaymentStarter.start(PayableOrder)` consumes the order-declared snapshot (the `OrderPaymentGateway` pattern) | Internal, not observable | Council consensus | — |
| D15 | A double submit either errors on the checked-out cart or creates a second order, depending on timing | Legacy has no live-order index | `AlreadyPlaced` is a success: both submissions answer the same `orderId` and the same checkout URL; an edited second request is silently ignored in favor of the winning order | Proposed deviation | Council consensus | Frontend note |
| D16 | No retry-payment journey exists | — | `POST /api/checkout/orders/{orderId}/payment`: no body, ownership-checked (foreign = 404, no provider call), built exclusively from the stored order snapshot, live payment → same URL, terminal → second payment row, 409 for paid/cancelled/free orders | Behavior extension | Joe 2026-08-02 (path); council (behavior) | Frontend journey |

## Migration retrospective

To be completed after phase-3 verification and simplification.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| — | — | — | — | — |
