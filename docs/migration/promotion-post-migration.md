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

## Customer-facing error payload for promotion codes — owner: Cart migration

Legacy exposes stable error codes (`PROMOTION_INVALID_CODE`,
`PROMOTION_INACTIVE`, `PROMOTION_NOT_STARTED`, `PROMOTION_EXPIRED`,
`PROMOTION_LOGIN_REQUIRED`, `PROMOTION_TOTAL_EXHAUSTED`,
`PROMOTION_PER_USER_EXHAUSTED`) with HTTP 400/403/409 via the domain
exception handler. The Kotlin module returns typed
`PromotionCodeResult` failures without an HTTP shape. The Cart migration
defines the customer-facing wire format when it exposes the apply-code
endpoint.
