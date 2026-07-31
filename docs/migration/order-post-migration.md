# Order post-migration work

Durable follow-up work from the Order migration
([`order-migration.md`](order-migration.md)) that belongs to the frontend, to
operations, or to a later module migration. General Order behavior and
decisions stay in the record; this file holds only what outlives it.

Do not create placeholder Payment or Checkout types inside Order to complete
any of these items early.

## Frontend adaptation — owner: frontend work

The Kotlin `/api/orders` contract is observably different from the legacy
`/api/checkout/orders` one. The Vue frontend in `../voenix-shop/frontend` must
follow before it is pointed at the Kotlin backend.

### `src/stores/shop/orders.ts`

- [ ] Fetch `GET /api/orders` instead of `GET /api/checkout/orders`. The
  answer is still a direct JSON array, and it is now guest-capable: a visitor
  without an account sees the orders they placed under their guest cookie
  (deviation D4).
- [ ] Adopt the renamed and added fields of an order:
  `totalAmountInCents` → `total`, `shippingCostInCents` → `shippingCost`, plus
  the new `subtotal` and `discountAmount`. All four are integer cents, and the
  backend guarantees `total = subtotal + shippingCost - discountAmount`, so the
  discount no longer has to be inferred.
- [ ] Adopt the renamed fields of a line: `priceAtTime` → `price`,
  `promptPriceAtTime` → `promptPrice`, `generatedEditedImageId` → `imageId`.
- [ ] Drop `customData` (never held anything but `{}`; deviation D6) and
  `paymentStatus` (returns with the Payment migration; deviation D5). Both are
  absent from the response, so a `PaymentStatus` branch has nothing to read.
- [ ] Drop `'shipped'` from `OrderStatus`. The backend's status set is
  `PENDING | PAID | CANCELLED` (deviation D7), and the values arrive
  **uppercase** — the `normalizeStatus` lowercasing still works, but the type
  must match the three remaining values.
- [ ] A single order is `GET /api/orders/{orderId}` and answers the same
  representation as a list entry, lines included. Both an unknown and a foreign
  id answer `404` with the shared `ApiError` body; there is no `403`
  (deviation D3).

### `src/stores/shop/checkout.ts`

- [ ] `GET /api/checkout/orders/{orderId}` becomes `GET /api/orders/{orderId}`
  with the field names above. Placing an order (`POST /api/checkout`) is
  **not** migrated yet and stays a Wave-3 item.

### `src/stores/shop/cart.ts`

- [ ] The reorder call `POST /api/cart/order-items/{orderItemId}` exists again
  and answers the recalculated `CartView`. Two behaviors changed: the new line
  always has **quantity 1** rather than the ordered quantity, and it is priced
  at today's catalog price, not the price the customer paid (deviation D13).
- [ ] Handle `409` with `ApiError.code = "ORDER_IMAGE_UNAVAILABLE"`: the print
  image of that ordered line cannot be printed any more. The useful reaction is
  offering a fresh upload, not a retry.
- [ ] Reorder no longer requires a login. A guest may reorder their own order's
  line under the same ownership rule as every other order read (deviation D14).
- [ ] `GET /api/orders` answers the **complete** history, every line included,
  with no `LIMIT` and one lines query per order. That is the legacy behavior
  and it is fine for the order counts of today, but nobody has decided what a
  customer with hundreds of orders should see. Pagination is a contract change
  that the frontend has to ask for — decide it here, together with the page
  size the UI actually renders, before the list grows.

## Admin production PDFs are per supplier — owner: frontend and operations

The legacy `GET /api/orders/{id}/pdf` was **anonymous** (its admin attribute
was commented out) and answered exactly one document per order. Both facts
changed (deviations D1 and D2):

- the routes are admin-only and live under `/api/admin/orders`;
- an order yields **one PDF per involved supplier**, so fetching is a two-step
  interaction: `GET /api/admin/orders/{orderId}/production-pdfs` lists
  `{ supplierId, fileName }` for the documents that exist, and
  `GET /api/admin/orders/{orderId}/production-pdfs/{supplierId}` downloads one.

- [ ] Any admin UI or ops runbook that links "the order PDF" has to offer the
  list first. `fileName` is the producer-facing `ORD-{orderId}.pdf` and
  therefore **repeats** across the suppliers of one order — it is unique per
  destination, not per order, so a UI that downloads several documents of one
  order must disambiguate them itself.
- [ ] A `409` with a `PRODUCTION_PDF_MISSING_IMAGE`,
  `PRODUCTION_PDF_UNREADABLE_IMAGE`, or `PRODUCTION_PDF_INVALID_SOURCE` code is
  repairable order data (a missing supplier assignment, a deleted image), not a
  server fault; `PRODUCTION_PDF_RENDER_FAILURE` is a `500` whose details are in
  the log only.

## Payment hooks — owner: Payment migration (Wave 2)

The order module supplies everything the payment flow needs and calls none of
it. What Payment owns:

- [ ] Call `markPaid(orderId)` when the provider confirms a payment, and map
  its five results: `Paid` and `AlreadyPaid` are both success (the call is
  idempotent by design), `NotFound` and `Cancelled` are refusals, and
  `PromotionRefused` is a **paid** order whose coupon could not be redeemed
  (deviation D22) — it must not be reported as a payment failure.
- [ ] Build `payments` with a `payments.order_id` reference. The legacy
  `orders.payment_id` column is deliberately absent (deviation D5), and
  `paymentStatus` returns to the order response only through this migration.
- [ ] Own the cancellation write path. Nothing writes `CANCELLED` today. Two
  things depend on that being added deliberately: the partial unique index
  `ux_orders_live_cart` lets a cart be ordered again once its order is
  cancelled, and `OrderRepository.liveOrderOfCart` currently asserts with
  `checkNotNull` that a conflicting cart still has a live order — a
  cancellation racing a placement is the one situation that can break the
  assertion, and the Payment migration must decide then whether it becomes a
  retry or an expected result.
- [ ] Add provider-level idempotency per order. The database already prevents
  a second order per cart; a second *payment* for one order is Payment's own
  key (deviation D10).

## Checkout hooks — owner: Checkout migration (Wave 3)

- [ ] Call the placement operation. `OrderService.place`, `PlaceOrderInput`,
  and `OrderWriteResult` are `internal` and have no HTTP surface, because their
  first caller does not exist yet. Checkout decides whether they become public
  or whether the checkout route lives in a module that can see them.
- [ ] Own everything `PlaceOrderInput` expects to be already decided: reading
  the active cart, the totals (`CartTotals`), validating the address against
  the country list — `orders.shipping_country` is a `varchar(2)` **without** a
  foreign key to `countries` on purpose (decision 9) — and the pre-payment
  promotion re-check.
- [ ] Write `carts.status = 'CHECKED_OUT'`, the path the Cart migration
  deferred (see [`cart-migration.md`](cart-migration.md)).
- [ ] Restore capacity reservation of promotions by in-flight orders. The
  order schema keeps `promotion_id`, `status`, and `created_at` queryable
  precisely so this can be built without leaking order state into the promotion
  module; the design question and the reason `redeem` must stay limits-only are
  in
  [`promotion-post-migration.md`](promotion-post-migration.md).
- [ ] Handle `OrderWriteResult.AlreadyPlaced` as a success. It is what makes a
  double-submitted checkout harmless: the order that won the race is returned
  instead of a second one being created.

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
- **Guest-token lifetime.** Orders are now the second kind of customer content
  reachable through the `voenix.guest` cookie after a logout. The decision is
  cross-cutting and stays in
  [`all-post-migration.md`](all-post-migration.md); the order module only
  widened the surface it applies to.
