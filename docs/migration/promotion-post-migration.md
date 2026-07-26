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

## Activity window at checkout time — owner: Cart and Checkout migrations

`PromotionCodes.redeem` re-checks only the usage limits, as the migration
spec prescribes ("locks the promotion row, re-checks the limits"). The active
flag and the activity window are checked by `validate`, which runs when the
customer enters the code.

That leaves a gap once a real consumer exists: a cart validated before the end
date and checked out after it would still redeem the promotion, as would a
promotion an administrator deactivated in between. Nothing consumes the
capability yet, so nothing is broken today.

The Cart and Checkout migrations must close it, and both ways are open:
re-running `validate` immediately before `redeem` (advisory, with a small race
window), or moving the availability check into the locked `redeem` transaction
(atomic, and the reason `Promotion.availabilityFailure` is a separate rule
group). Recommendation: the second, once a consumer makes the requirement
concrete.

## Customer-facing error payload for promotion codes — owner: Cart migration

Legacy exposes stable error codes (`PROMOTION_INVALID_CODE`,
`PROMOTION_INACTIVE`, `PROMOTION_NOT_STARTED`, `PROMOTION_EXPIRED`,
`PROMOTION_LOGIN_REQUIRED`, `PROMOTION_TOTAL_EXHAUSTED`,
`PROMOTION_PER_USER_EXHAUSTED`) with HTTP 400/403/409 via the domain
exception handler. The Kotlin module returns typed
`PromotionCodeResult` failures without an HTTP shape. The Cart migration
defines the customer-facing wire format when it exposes the apply-code
endpoint.
