# Promotion post-migration work

Durable follow-up work from the Promotion migration
([`promotion-migration.md`](promotion-migration.md)) that belongs to later
module migrations.

## Capacity reservation by in-flight orders — delivered by the Checkout migration

The legacy backend counts "reservations" against promotion usage limits:
pending orders referencing the promotion that are either younger than 15
minutes without a payment, or have a payment in status
open/pending/authorized/paid
(`PromotionApplicationService.ActiveReservationOrders`,
`PromotionLimits.PendingOrderReservationMinutes`). This prevents a promotion
from being over-applied while checkouts are in flight.

The Kotlin promotion module deliberately counts only real redemptions (Joe,
2026-07-24). The Order migration of 2026-07-31 did **not** build the
reservation and was not supposed to: it is a rule of the *checkout* flow, and
the order module has no checkout. What it did do is keep the data the
reservation needs queryable — `orders.promotion_id`, `orders.status`, and
`orders.created_at`, with an index on the promotion id — so Checkout can build
it without the promotion module learning about order or payment status.

Delivered by the Checkout migration on 2026-08-02 (ticket T1, see
[`checkout-migration.md`](checkout-migration.md)) as a promotion-owned
reservation — and *not* as a query over orders and payments. The legacy
predicate was replaced by a table the promotion module owns itself,
`promotion_reservations` (`V18__create_promotion_reservations.sql`): one row per
cart, counted next to the redemptions in every limit check. The promotion module
therefore still knows nothing about order or payment status; what it learns is
that a cart is checking out (deviation D1).

Four members of `PromotionCodes` carry the lifecycle:

- `reserve(promotionId, cartId, userId)` runs in its own transaction under a
  lock on the promotion row, checks the active flag, the activity window, the
  customer eligibility, and the usage limits — counting redemptions **and** the
  reservations of other carts — and upserts the row keyed on the cart;
- `release(cartId)` gives it back, inside the caller's transaction;
- `redeem(...)` consumes the reservation of its own cart in the same statement
  sequence that inserts the redemption, so capacity moves from in-flight to
  recorded without ever being counted twice or free in between;
- `validate(code, userId, reservationKey)` counts reservations too, excluding
  the caller's own cart (deviation D5) — the restoration of the legacy rule that
  the apply path counts in-flight capacity as well.

A reservation has **no TTL** (deviation D2, Joe's decision of 2026-08-02): it
ends only through a redemption, through the cancellation of its order, or when
the order's payment ends terminally. The accepted consequence — a crash between
the reservation and its order, or a permanently missed terminal webhook, blocks
that capacity until an administrator removes the row — is the orphaned-reservation
entry of the admin anomaly page in
[`payment-post-migration.md`](payment-post-migration.md).

Relevant legacy behavior tests: `CartServiceTests`
(`ApplyPromotionAsync_RejectsPromotionReservedByInFlightOrder`,
`…_IgnoresOrderWithTerminalPayment`, `…_RejectsPaidOrderAwaitingRedemption`,
`…_IgnoresAbandonedOrderWithoutPayment`,
`…_RejectsPromotionReservedByUsersInFlightOrder`). Their Kotlin counterparts are
`PromotionReservationsIntegrationTest` in the promotion module and the cart-apply
parity case in `CheckoutRetryCompositionIntegrationTest` (app module).

## `promotion_redemptions.order_id` — owner: Order migration

Legacy links each redemption to the order that created it (`order_id bigint
NULL`, unique index, FK to `orders` with `ON DELETE RESTRICT`; migration
`EnforcePromotionRedemptionLimits`). The unique index guarantees at most one
redemption per order. The Kotlin schema omitted the column because the orders
table did not exist yet and the guide forbids placeholder foreign keys.

Delivered by the Order migration on 2026-07-31 (ticket T2, see
[`order-migration.md`](order-migration.md)) — and stricter than legacy:
`V16__create_orders.sql` adds `order_id bigint **NOT NULL**` with a unique
constraint and a `RESTRICT` foreign key, so a redemption without the order it
paid for does not exist in the Kotlin schema at all. `PromotionCodes.redeem`
takes the order id and now **must run inside the caller's Exposed
transaction** (`IllegalStateException` outside one), which is what makes the
redemption commit and roll back with the decision that made the order paid.
The delete result changed with it: `PromotionDeleteResult.Redeemed` became
`InUse`, because an order restricts the delete too and SQL state `23503`
cannot say which of the two references held the promotion back.

## Activity window at checkout time — delivered by the Checkout migration

`PromotionCodes.redeem` re-checks only the usage limits, as the migration
spec prescribes ("locks the promotion row, re-checks the limits"). The active
flag and the activity window are checked by `validate`, which runs when the
customer enters the code.

Since the Cart migration (2026-07-30) this is a real gap with a real consumer:
Cart binds `validate` and stores the promotion on the cart, so a cart validated
before the end date and checked out after it would still redeem the promotion,
as would a promotion an administrator deactivated in between. Since the Order
migration (2026-07-31) `redeem` has a caller as well — `markPaid` redeems the
promotion of the order it is paying — but the gap still cannot be *reached*,
because nothing calls the placement operation yet. Checkout is both the
migration that closes the gap and the one that opens it.

The gap has to be closed **when the checkout starts**, not when the redemption
is recorded. Legacy works exactly that way and the distinction matters:

- `ValidateForCheckoutAsync` locks the promotion row and checks the activity
  window *and* the limits, and it runs before the payment is created.
- `PaidOrderProcessor` records the redemption once the payment succeeds and
  checks only customer eligibility and the usage limits
  (`Order/Services/PaidOrderProcessor.cs`, lines 50–66) — deliberately no
  window check.

So `redeem` must stay limits-only. Moving the availability check into the
locked `redeem` transaction looks cheaper — the rules are already grouped in
the private extension `Promotion.availabilityFailure()` in `PromotionService`
— but it would break the paid-order case: a promotion that expires between
checkout and payment confirmation would leave a paid order whose redemption
was rejected, and the customer has already been charged the discounted price.
An expiring promotion must not invalidate a checkout that is already running.

Closed on 2026-08-02 by exactly the operation the first section describes:
`reserve` takes the promotion row lock, checks the activity window *and* the
usage limits, and records the reservation — and it runs at the start of the
checkout, before the order is placed and long before a payment exists. One
operation closed both gaps, which is why the two sections were decided together.

`redeem` stayed limits-only, so the split is now real behavior rather than a
plan: a coupon that expires between the cart and the checkout is refused with
`400 PROMOTION_EXPIRED`, and a coupon that expires while the customer is paying
is still redeemed by the webhook. Both halves are pinned by
`CheckoutFlowCompositionIntegrationTest` in the app module.

The clock the operation needs stayed where it was: `PromotionService` holds the
`java.time.Clock` and hands the current instant into the locked repository
check, exactly as this section anticipated.

The note this section carried for the Order migration — do not add the window
check to `redeem` while wiring up the paid-order path — was honored:
`OrderRepository.markPaid` calls `redeem` inside its transaction and the
promotion module still checks eligibility and limits under the row lock and
nothing else. The paid-order case even gained a name for what the missing
window check protects: when the limit turns out to be exhausted at payment
time, the order becomes `PAID` without a redemption
(`PaidOrderResult.PromotionRefused`, deviation D22), because the customer has
already been charged.

## Customer-facing error payload for promotion codes — owner: Cart migration

Legacy exposes stable error codes (`PROMOTION_INVALID_CODE`,
`PROMOTION_INACTIVE`, `PROMOTION_NOT_STARTED`, `PROMOTION_EXPIRED`,
`PROMOTION_LOGIN_REQUIRED`, `PROMOTION_TOTAL_EXHAUSTED`,
`PROMOTION_PER_USER_EXHAUSTED`) with HTTP 400/403/409 via the domain
exception handler. The Kotlin module returns typed
`PromotionCodeResult` failures without an HTTP shape. The Cart migration
defines the customer-facing wire format when it exposes the apply-code
endpoint.

Delivered by the Cart migration on 2026-07-30; see
[`cart-migration.md`](cart-migration.md). `CartRoutes` maps the typed failures
onto all seven legacy codes carried in `ApiError.code`: `400` for
`PROMOTION_INVALID_CODE`, `PROMOTION_INACTIVE`, `PROMOTION_NOT_STARTED` and
`PROMOTION_EXPIRED`, `403` for `PROMOTION_LOGIN_REQUIRED`, and `409` for
`PROMOTION_TOTAL_EXHAUSTED` and `PROMOTION_PER_USER_EXHAUSTED`. The whole
matrix is pinned by `CartFlowIntegrationTest`.

The Checkout migration moved that mapping one module down: it is the public
`PromotionCodeResult.toApiError()` of the promotion module now, because a coupon
refused while it is entered into the cart and the same coupon refused while the
checkout reserves it must reach the customer as the very same answer. Cart and
Checkout both call it; nothing about the wire format changed.
