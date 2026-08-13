# Order post-migration work

Durable follow-up work from the Order migration
([`order-migration.md`](order-migration.md)) that belongs to the frontend, to
operations, or to a later module migration. General Order behavior and
decisions stay in the record; this file holds only what outlives it.

The Payment and Checkout hooks below are **delivered**: the payment module
migrated on 2026-08-01 (see [`payment-migration.md`](payment-migration.md) and
[`payment-post-migration.md`](payment-post-migration.md)), the checkout module
on 2026-08-02 (see [`checkout-migration.md`](checkout-migration.md)). What
remains open in this file is frontend and operations work.

## Frontend adaptation — owner: frontend work

The Kotlin `/api/orders` contract is observably different from the legacy
`/api/checkout/orders` one. The Vue frontend in `frontend/` must follow
before it is pointed at the Kotlin backend.

### `src/stores/shop/orders.ts`

All items below are done with issue #94 (part of the frontend migration, issue
#84). The store owns the order vocabulary — `OrderStatus`,
`OrderPaymentStatus`, `Order`, `OrderItem`, `OrderStatusSnapshot` — and
`stores/shop/checkout.ts` re-exports what it needs from there.

- [x] Fetch `GET /api/orders` instead of `GET /api/checkout/orders`. The
  answer is still a direct JSON array, and it is now guest-capable: a visitor
  without an account sees the orders they placed under their guest cookie
  (deviation D4).
- [x] Adopt the renamed and added fields of an order:
  `totalAmountInCents` → `total`, `shippingCostInCents` → `shippingCost`, plus
  the new `subtotal` and `discountAmount`. All four are integer cents, and the
  backend guarantees `total = subtotal + shippingCost - discountAmount`, so the
  discount no longer has to be inferred.
- [x] Adopt the renamed fields of a line: `priceAtTime` → `price`,
  `promptPriceAtTime` → `promptPrice`, `generatedEditedImageId` → `imageId`.
- [x] Drop `customData`; it never held anything but `{}` (deviation D6) and is
  absent from the response.
- [x] Keep `paymentStatus`, but re-type it. It was absent for the length of the
  Order migration (deviation D5) and the Payment migration returned it on
  2026-08-01. It is a **string or `null`**, uppercase, and its values are
  Mollie's: `OPEN`, `PENDING`, `AUTHORIZED`, `PAID`, `FAILED`, `CANCELED`,
  `EXPIRED`. `null` means the order has no payment at all — a free order, or a
  checkout that was never started — so the UI needs a branch for it rather than
  a default label. Note that the payment value `CANCELED` carries **one** L
  while the order `status` value `CANCELLED` carries two; they are different
  facts from different systems and must stay two words in the types as well. The
  details are in
  [`payment-post-migration.md`](payment-post-migration.md).
- [x] Drop `'shipped'` from `OrderStatus`. The backend's status set is
  `PENDING | PAID | CANCELLED` (deviation D7), and the values arrive
  **uppercase**. `normalizeStatus` is deleted: the wire values *are* the type,
  and the i18n maps are keyed by them.
- [x] A single order is `GET /api/orders/{orderId}` and answers the same
  representation as a list entry, lines included. Both an unknown and a foreign
  id answer `404` with the shared `ApiError` body; there is no `403`
  (deviation D3).

### `src/stores/shop/checkout.ts`

Both items are done with issue #93 (part of the frontend migration, issue #84).

- [x] `GET /api/checkout/orders/{orderId}` becomes `GET /api/orders/{orderId}`
  with the field names above.
- [x] `POST /api/checkout` exists again since the Checkout migration of
  2026-08-02 and answers `201 {orderId, checkoutUrl|null}` — a `null` URL is a
  free order that is already paid. The request body it takes and the new retry
  route `POST /api/checkout/orders/{orderId}/payment` are described in
  [`checkout-migration.md`](checkout-migration.md).

### `src/stores/shop/cart.ts`

The three reorder items are done with issue #91 (part of the frontend
migration, issue #84).

- [x] The reorder call `POST /api/cart/order-items/{orderItemId}` exists again
  and answers the recalculated `CartView`. Two behaviors changed: the new line
  always has **quantity 1** rather than the ordered quantity, and it is priced
  at today's catalog price, not the price the customer paid (deviation D13).
- [x] Handle `409` with `ApiError.code = "ORDER_IMAGE_UNAVAILABLE"`: the print
  image of that ordered line cannot be printed any more. The useful reaction is
  offering a fresh upload, not a retry. `OrderView.vue` shows the offer as an
  alert whose action creates an empty editor draft for the same article and
  variant, so the customer uploads a new image instead of retrying.
- [x] Reorder no longer requires a login. A guest may reorder their own order's
  line under the same ownership rule as every other order read (deviation D14).
  Nothing in the store or the view asks for a session before reordering.
- [ ] `GET /api/orders` answers the **complete** history, every line included,
  with no `LIMIT` and one lines query per order. That is the legacy behavior
  and it is fine for the order counts of today, but nobody has decided what a
  customer with hundreds of orders should see. Pagination is a contract change
  that the frontend has to ask for — decide it here, together with the page
  size the UI actually renders, before the list grows. The frontend migration
  (issue #84) deliberately deferred it: issue #94 renders the complete history
  and adds no pagination.

## Admin production PDFs are per supplier — owner: frontend and operations

The legacy `GET /api/orders/{id}/pdf` was **anonymous** (its admin attribute
was commented out) and answered exactly one document per order. Both facts
changed (deviations D1 and D2):

- the routes are admin-only and live under `/api/admin/orders`;
- an order yields **one PDF per involved supplier**, so fetching is a two-step
  interaction: `GET /api/admin/orders/{orderId}/production-pdfs` lists
  `{ supplierId, fileName }` for the documents that exist, and
  `GET /api/admin/orders/{orderId}/production-pdfs/{supplierId}` downloads one.

Both items are done with issue #100 (part of the frontend migration, issue
#84). Joe's decision 2 of #84 kept the admin surface narrow: `OrdersView.vue` is
an order-ID input that lists the documents and downloads one — no order search,
no order table, no status editing. `stores/admin/orders.ts` owns the two routes
and their error vocabulary.

- [x] Any admin UI or ops runbook that links "the order PDF" has to offer the
  list first. `fileName` is the producer-facing `ORD-{orderId}.pdf` and
  therefore **repeats** across the suppliers of one order — it is unique per
  destination, not per order, so a UI that downloads several documents of one
  order must disambiguate them itself. The utility saves each document as
  `ORD-{orderId}-supplier-{supplierId}.pdf` and shows both names.
- [x] A `409` with a `PRODUCTION_PDF_MISSING_IMAGE`,
  `PRODUCTION_PDF_UNREADABLE_IMAGE`, or `PRODUCTION_PDF_INVALID_SOURCE` code is
  repairable order data (a missing supplier assignment, a deleted image), not a
  server fault; `PRODUCTION_PDF_RENDER_FAILURE` is a `500` whose details are in
  the log only.

## Payment hooks — delivered by the Payment migration (2026-08-01)

This section is history rather than a to-do list. The order module still calls
none of the payment flow; what it *supplies* is now consumed, and this is where
each hook ended up.

- **The paid consumer exists.** It is not `markPaid` directly: the order module
  declares, implements, and exports `OrderPaymentGateway` with
  `confirm(orderId)` and `cancel(orderId)`, and the payment module calls
  `confirm` when Mollie reports a payment as paid. The five internal
  `PaidOrderResult` values are mapped onto the four `OrderPaymentOutcome` values
  **inside the order module** (deviation D13 of the Payment migration), so the
  mapping rule lives where the results do rather than in the payment module:
  `Paid → APPLIED`, `AlreadyPaid → ALREADY_APPLIED`, `NotFound → UNKNOWN_ORDER`,
  `Cancelled → REFUSED`, and `PromotionRefused → APPLIED` — a paid order whose
  coupon could not be redeemed (deviation D22 of this migration) is a promotion
  problem the order module logs, and structurally not a payment failure.
- **`payments` exists, with `payments.order_id`.** Flyway `V17__create_payments`
  builds it with a `NOT NULL` order reference and an `ON DELETE RESTRICT`
  foreign key; the legacy `orders.payment_id` column stayed absent (deviation
  D5). `paymentStatus` is back in both order responses, filled through the
  `OrderPaymentStatusSource` the order module declares and the payment module
  implements.
- **The cancellation write path exists.** `OrderPaymentGateway.cancel` writes
  `PENDING → CANCELLED` under the same `SELECT … FOR UPDATE` row lock as the
  paid transition, so a confirmation and a cancellation of one order serialize.
  A `PAID` order refuses cancellation (`REFUSED`) and an already cancelled one
  answers `ALREADY_APPLIED`. The single caller is the payment module's
  compensation for a provider that would not create a payment at all (deviation
  D10 of the Payment migration) — a payment that *ends* terminally deliberately
  leaves the order `PENDING` (D9). With `CANCELLED` now actually written, the
  `checkNotNull` in `OrderRepository.liveOrderOfCart` became a bounded retry of
  the placement insert: a cancellation racing a placement is an expected result,
  not an assertion failure.
- **Idempotency per order is delivered, and it is not a provider key.** The
  authority is the partial unique index `ux_payments_live_order`: one live
  payment per order, so a double-clicked checkout ends as one row and one
  checkout URL. A fresh `Idempotency-Key` per create attempt protects the
  provider call itself (deviation D17), and a payment that ended `FAILED`,
  `EXPIRED`, or `CANCELED` falls out of the index so a Wave-3 retry may start a
  second payment for the same order.

## Checkout hooks — delivered by the Checkout migration (2026-08-02)

This section is history rather than a to-do list. Every hook the Order migration
left open has its caller now, and this is where each one ended up (see
[`checkout-migration.md`](checkout-migration.md)).

- **The placement has a caller, and a public capability to reach it.** The order
  module exports `OrderPlacement` on its handle, with `place(PlaceOrderInput)`
  and the retry read `payable(orderId, userId, guestToken)`. `PlaceOrderInput`
  and its `Address` became public with it; the placement's success variants
  carry the new public `PayableOrder` — the frozen snapshot a payment is built
  from — instead of the internal `OrderView`. `OrderService.place` itself stayed
  internal.
- **Everything `PlaceOrderInput` expects is decided by the checkout module.** It
  reads the active cart through the cart module's `CheckoutCarts` capability,
  takes the totals from `CartTotals` — the very same arithmetic the customer saw
  in their cart — and reserves the coupon before it places anything. The address
  is validated for its *shape* only: the two-letter country check is all there
  is, because whether the shop ships to a country is an open product decision
  (deviation D10 of the Checkout migration, recorded in
  [`all-post-migration.md`](all-post-migration.md)). `orders.shipping_country`
  therefore still has no foreign key to `countries`.
- **`carts.status = 'CHECKED_OUT'` is written.** `CheckoutCarts.markCheckedOut`
  performs it, and the checkout calls it *last* — after the payment exists or
  after a free order was confirmed — so a checkout that dies halfway leaves an
  `ACTIVE` cart the customer can simply submit again.
- **The capacity reservation exists**, but not as a query over orders: the
  promotion module owns a `promotion_reservations` table keyed on the cart
  (deviation D1). The order schema's `promotion_id`, `status`, and `created_at`
  were not needed for it after all; what the order module contributes is the
  `release` call inside its cancellation transaction and the `paymentEnded`
  notification a terminal payment triggers. The details are in
  [`promotion-post-migration.md`](promotion-post-migration.md).
- **`AlreadyPlaced` is treated as a success.** Both submissions of a
  double-clicked checkout answer the same `orderId` and the same checkout URL:
  the placement answers the order that won, and `PaymentStarter.start` answers
  that order's stored URL without calling Mollie (deviation D15). The composed
  proof is `two overlapping submissions of one cart end as one order and one
  payment` in the app module.
- **The payment is started by the checkout.** The payment module exports
  `PaymentStarter.start(PayableOrder)`; the legacy-shaped `PaymentRequest` was
  deleted with it (deviation D14), so the payment consumes the order-declared
  snapshot the way `OrderPaymentGateway` established. A `null` answer becomes
  `502 PAYMENT_NOT_STARTED` and deliberately claims nothing about the order
  (deviation D7).

## Accepted consequences worth revisiting later

These are decided, not open — recorded so a later feature does not rediscover
them as bugs.

- **Account deletion re-exposes orders.** `orders.user_id` is `ON DELETE SET
  NULL`, so a deleted account's order becomes reachable through its stored
  guest token again (deviation D25). No account-deletion feature exists yet;
  the one that is built must anonymize orders first.
- **An article deleted before production keeps its request open.** The
  supplier is resolved live, so a line whose article is gone reports
  `supplierId = null` and the production request stays retryably open rather
  than failing (deviation D24). That is deliberate — it stays repairable — but
  it needs an operational view of long-open requests, which
  [`email-post-migration.md`](email-post-migration.md) already tracks for the
  equivalent email jobs.
- **An order claimed from another device keeps its images with the old guest
  token.** Print images are claimed by guest token only, exactly as the legacy
  `GuestDataClaimService` did; an order is claimed by token *or* by confirmed
  e-mail address. So an order placed in one browser and claimed by a login in
  another belongs to the account, while its `print_images` rows still belong to
  the guest token of the first browser. Reordering such a line answers `409`
  with `ORDER_IMAGE_UNAVAILABLE` until a login from the original browser heals
  the ownership. This is a retained legacy limitation, not a new one, and the
  frontend's fresh-upload reaction to that code already covers the customer:
  they upload the image again and order normally.
  Superseded by issue #110 (2026-08-11): nothing claims an order any more, so
  the cross-device case is now simply an order that stays with the browser it
  was placed in, plus the permanent order link the confirmation mail carries.
- **Guest-token lifetime.** Orders are now the second kind of customer content
  reachable through the `voenix.guest` cookie after a logout. The decision is
  cross-cutting and stays in
  [`all-post-migration.md`](all-post-migration.md); the order module only
  widened the surface it applies to.
