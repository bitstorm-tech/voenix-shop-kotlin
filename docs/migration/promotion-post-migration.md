# Promotion post-migration work

Durable follow-up work from the Promotion migration
([`promotion-migration.md`](promotion-migration.md)) that belongs to later
module migrations.

## Capacity reservation by in-flight orders — owner: Order and Checkout migrations

The legacy backend counts "reservations" against promotion usage limits:
pending orders referencing the promotion that are either younger than 15
minutes without a payment, or have a payment in status
open/pending/authorized/paid
(`PromotionApplicationService.ActiveReservationOrders`,
`PromotionLimits.PendingOrderReservationMinutes`). This prevents a promotion
from being over-applied while checkouts are in flight.

The Kotlin promotion module deliberately counts only real redemptions (Joe,
2026-07-24). When Order and Checkout are migrated, decide how to restore an
equivalent guarantee without leaking order/payment status into the promotion
module. The brainstorming favourite was a promotion-owned reservation concept
(reserve with TTL at checkout start, confirm to a redemption on payment,
expired reservations simply not counted), but the decision belongs to the
migration that has the real consumers.

Relevant legacy behavior tests: `CartServiceTests`
(`ApplyPromotionAsync_RejectsPromotionReservedByInFlightOrder`,
`…_IgnoresOrderWithTerminalPayment`, `…_RejectsPaidOrderAwaitingRedemption`,
`…_IgnoresAbandonedOrderWithoutPayment`,
`…_RejectsPromotionReservedByUsersInFlightOrder`).

## `promotion_redemptions.order_id` — owner: Order migration

Legacy links each redemption to the order that created it (`order_id bigint
NULL`, unique index, FK to `orders` with `ON DELETE RESTRICT`; migration
`EnforcePromotionRedemptionLimits`). The unique index guarantees at most one
redemption per order. The Kotlin schema omits the column because the orders
table does not exist yet and the guide forbids placeholder foreign keys.

The Order migration adds the column, unique index, and FK, and extends
`PromotionCodes.redeem` to take the order id so the at-most-one-redemption-
per-order invariant is enforced by PostgreSQL again.

## Activity window at checkout time — owner: Checkout migration

`PromotionCodes.redeem` re-checks only the usage limits, as the migration
spec prescribes ("locks the promotion row, re-checks the limits"). The active
flag and the activity window are checked by `validate`, which runs when the
customer enters the code.

Since the Cart migration (2026-07-30) this is a real gap with a real consumer:
Cart binds `validate` and stores the promotion on the cart, so a cart validated
before the end date and checked out after it would still redeem the promotion,
as would a promotion an administrator deactivated in between. Only the last
step is missing — nobody calls `redeem` yet — so the gap cannot be hit until
Checkout exists, which is exactly the migration that owns closing it.

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

Note for the Order migration: do not add the window check to `redeem` while
wiring up the paid-order path. Order keeps the legacy semantics — eligibility
and limits under the row lock, nothing else.

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
