# Promotion post-migration work

Durable follow-up work from the Promotion migration
([`promotion-migration.md`](promotion-migration.md)) that belongs to later
module migrations.

## Capacity reservation by in-flight orders — owner: Checkout migration

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

The decision itself is still open. The brainstorming favourite was a
promotion-owned reservation concept (reserve with TTL at checkout start,
confirm to a redemption on payment, expired reservations simply not counted).
It belongs to the migration that has the real consumer, which is now Checkout
alone.

Since the Payment migration of 2026-08-01 the second half of the legacy rule has
its data too: `payments.order_id` and `payments.status` exist, and the module
that owns them exports only `OrderPaymentStatusSource` — a *display* read keyed
on order ids. Whoever builds the reservation therefore has to decide where the
"in-flight" query lives; the promotion module still must not learn about order or
payment status.

Relevant legacy behavior tests: `CartServiceTests`
(`ApplyPromotionAsync_RejectsPromotionReservedByInFlightOrder`,
`…_IgnoresOrderWithTerminalPayment`, `…_RejectsPaidOrderAwaitingRedemption`,
`…_IgnoresAbandonedOrderWithoutPayment`,
`…_RejectsPromotionReservedByUsersInFlightOrder`).

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

## Activity window at checkout time — owner: Checkout migration

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

The Checkout migration therefore needs a locked pre-payment check of its own.
The natural place for it is the reservation concept from the first section
above: a `reserve` operation that takes the promotion row lock, checks the
activity window *and* the usage limits, and records a reservation with a TTL.
That single operation closes both gaps, which is why the two sections should
be decided together rather than one after the other.

Whichever shape is chosen, that operation needs a clock. Today only `validate`
needs one, which is why `PromotionService` — not `PromotionRepository` —
holds the `java.time.Clock`; a locked check inside the repository would have
to receive the current instant from the service.

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
