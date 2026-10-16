# The Checkout package

This guide explains the TypeScript code in
[`backend-ts/src/modules/checkout`](../../../../backend-ts/src/modules/checkout).
It starts from the [Kotlin checkout guide](../../backend/packages/checkout-package.md)
and keeps its HTTP contract; the decision that changed in the port, one checkout
in **two transactions** instead of five commits, is recorded below.

## What this package does

It turns a filled cart into a placed order and sends the customer to pay for it.

It is the smallest module of the backend and the last consumer in the chain:

- **no table**, no `schema.ts`, no repository;
- **no exported capability**: `createCheckoutModule` answers `{ routes }` and
  nothing consumes a checkout;
- **two transactions of its own**, opened with the platform's `write(db, …)`,
  inside which it only ever calls what other modules export.

Everything a checkout *does* is done by another module, through a capability
that module exports:

| It needs | It calls | Owner |
| --- | --- | --- |
| the priced cart, the row lock, and closing the cart | `checkoutCarts.activeCart` / `lockForCheckout(trx, …)` / `markCheckedOut(trx, …)` | cart |
| holding the coupon's capacity, giving it back | `promotionCodes.reserve(trx, …)` / `release(trx, …)` | promotion |
| placing the order, confirming a free one, reading a payable one, starting its payment | `orders.place(trx, …)` / `confirmFree(trx, …)` / `payable` / `startPayment` | order |
| may we ship to this country? | `countries.isShippable` | country |

What the cart's lines *are* (the phone rule below) is answered by the cart
snapshot itself: every `CheckoutCartLine` carries its current `articleType`, so
the checkout asks the article catalog nothing.

## Decision: one checkout, two transactions

The Kotlin module committed five times in five modules (cart snapshot,
promotion reservation, placement, settlement, cart close) and held the sequence
together with compensations: a `releaseAbandoned` after a refused placement, a
unique-violation retry path inside the placement for a double-clicked checkout.
The port (#247) replaces that with two transactions of this module and the
order module's payment start between them:

- **TX1** locks the cart row (`SELECT … FOR UPDATE` through
  `lockForCheckout`), reserves the coupon, places the order together with its
  rendered confirmation mail, and, for a **free** order, confirms it and closes
  the cart. Everything in it commits together or not at all. The lock is what
  makes a second submission of the same cart wait and then find the live order
  (`alreadyPlaced`) instead of racing `ux_orders_live_cart`; the one-transaction
  scope is what makes a refused placement leave no reservation behind without a
  separate compensation.
- **The payment start is the order module's own transaction.**
  `orders.startPayment` opens it, locks the *order* row (`SELECT … FOR
  UPDATE`), makes the Mollie call inside it (eight seconds at most), and
  inserts the payment row there. The call runs outside both checkout
  transactions: neither TX1 nor TX2 stays open across a network call, and the
  cart lock is released before it. Only the order row is held, and the
  provider timeout stays below the database's `lock_timeout` (see
  [the Order package](order-package.md)).
- **TX2** only closes the cart, once a payment exists. Until then the cart stays
  `ACTIVE` on purpose: a checkout that dies halfway, and a provider that
  refuses, both leave the customer a cart to submit again.

What a refusal does is now the order module's business: when Mollie starts no
payment, `startPayment` cancels the order and releases the reservation inside
the very transaction that holds the order lock and answers `null`; the
checkout only answers `502 PAYMENT_NOT_STARTED`. There is no compensation left
in this module.

### A checkout that dies between its two transactions

TX1 and the payment start are committed, TX2 is not: the order is `PENDING`,
its payment is live, and the cart is still `ACTIVE`. Two paths meet that state,
and both end it (#262, Codex finding A4):

- **The retry route closes the originating cart** once the payment stands. The
  `PayableOrder` carries its `cartId` for this; `markCheckedOut` is idempotent,
  so for the usual retry (the checkout closed the cart long ago) it is one
  `UPDATE` that changes nothing. Without it a customer who paid through the
  order page would keep a cart whose live order is paid, and every later
  `POST /api/checkout`, new lines included, would resolve that order and be
  answered `502`.
- **A checkout of a cart whose live order is already paid settles it.**
  `orders.place` answers `alreadyPaid` for such an order, TX1 closes the cart
  and answers the order as settled (`201`, `checkoutUrl: null`), the same answer
  a free order gets. Nothing is placed, Mollie is not called.

A checkout resubmitted before anybody paid needs neither: `alreadyPlaced`,
then `startPayment` answers the live payment's URL, then TX2 closes the cart.

The lock order stays acyclic: TX1 takes the cart row, then the promotion row
(inside `reserve`); the free-order confirmation inside TX1 takes the order row
it just inserted, then the promotion row again (inside `redeem`); the payment
start takes the order row and, on a refusal, the promotion row. No transaction
ever takes a cart row after an order row.

## Production file map

| File | What it holds |
| --- | --- |
| `index.ts` | `createCheckoutModule(deps)` answering `{ routes }` |
| `routes.ts` | the two routes, the Zod schema `checkoutInput`, the order id parameter, and the one error table |
| `service.ts` | `createCheckoutService`: the flow below in two transactions, `CheckoutInput`, and `CheckoutResult`, the union every ending is expressed as |

`CheckoutResult` is a union of its own rather than the shared `OperationResult`
because a checkout composes four modules and the reason it stopped is the only
thing that tells a customer what to do next. Two Kotlin endings are gone:
`Invalid` (the placement cannot refuse a body the schema accepted) and
`UnexpectedFailure` (a step that answers something unusable throws, and the
app's `onError` answers `500`, the platform rule; a free order that cannot be
confirmed throws inside TX1, which rolls the placement back).

## HTTP API

| Method | Path | Who calls it | Answers |
| --- | --- | --- | --- |
| `POST` | `/api/checkout` | the storefront, signed in or as a guest | `201 {orderId, checkoutUrl\|null}` + `Location: /api/orders/{orderId}` |
| `POST` | `/api/checkout/orders/{orderId}/payment` | the storefront, for an order that was already placed | `200 {orderId, checkoutUrl}` |

Both sit on one sub-app under `/api/checkout` with the `guestCapable`
protection: a guest and a signed-in customer alike need a valid CSRF pair. Both
answers carry `Cache-Control: no-store`, set inside the handler, so a CSRF
rejection or a rejected body is answered without it. Neither route hands out a
guest cookie: the token is read with `guestTokens.tryGet` and never minted. A
visitor without one has no cart, which the empty-cart answer covers, and no
order, which the not-found answer covers. A signed-in customer's cart is found
by their user id, so a missing cookie alone never means "empty".

### The request

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

The schema `checkoutInput` in `routes.ts` is the whole field matrix (see
[Request validation](../conventions/request-validation.md)): every postal field
is required and bounded by its column (`Must not be blank`, `Must be at most N
characters`), the country is a two-letter code and is upper-cased, the email is
required, at most 255 characters, and has to look like an address. Three
details are deliberate:

- **The contact fields sit on the shipping address only.** The Vue store
  serializes `email` and `phone` on the billing address too. The schema is
  strict everywhere, so those two keys are *declared* on the billing address
  as ignored values and never read; every other unknown property is refused.
- **`phone: ""` means no phone.** The store always sends the field. A blank or
  absent phone is `null` after parsing; a given one is trimmed and at most 50
  characters. Nothing else is done to it: the shop does not know what a valid
  number looks like in every country it ships to.
- **`billingAddress: null` is not missing data.** It is the customer saying
  "same address", and the order module resolves it into the stored columns. A
  missing key means the same.

The country is checked twice, in two places: the schema checks the two-letter
**shape** of both addresses, and the service asks the country module whether the
shop **ships** to the shipping country. The answer lives in a table an admin
maintains and can change between two submissions of the same form, so it cannot
be a rule of the schema. The billing address is never checked against the list;
an invoice may go anywhere.

The phone follows the same split: the schema checks the length of a given
number, and the service refuses a checkout *without* a number when any line of
the cart snapshot has `articleType === 'SPOD'`, because the print-on-demand
partner rejects an order that carries no phone. A line the catalog no longer
resolves has `articleType: null`, is not a SPOD article here, and is refused a
moment later with `CART_ITEM_UNAVAILABLE`, the answer that actually helps.

### The answers

| Status | `code` | When |
| --- | --- | --- |
| `201` | none | the order exists; `checkoutUrl` is `null` for a free order and for a cart whose live order is already paid |
| `400` | none | the body broke its field rules (`Validation failed` with the field errors) |
| `400` | `CART_EMPTY` | no identity, no active cart, a cart without a line, or a cart that a concurrent checkout closed between the snapshot and the lock |
| `400` | none | the shop does not ship to `shippingAddress.country`: a **field error** |
| `400` | none | the cart contains a SPOD article and the request carries no phone: a **field error** on `shippingAddress.phone` |
| `400`/`403`/`409` | `PROMOTION_*` | the coupon could not be reserved; status and code come from the promotion module's `toApiError` |
| `409` | `CART_ITEM_UNAVAILABLE` | a line names an article variant the catalog no longer has |
| `409` | `CART_TOTAL_TOO_LARGE` | the cart's amounts do not fit the integer cents an order stores |
| `502` | `PAYMENT_NOT_STARTED` | Mollie started no payment; the order is cancelled and the cart is still active |
| `500` | none | a step threw; nothing is mapped, the app's `onError` answers |

The two field errors are answered in the exact shape a rejected body has, so a
frontend that highlights invalid fields needs no new branch:

```json
{ "message": "Validation failed", "errors": { "shippingAddress.country": ["We do not ship to this country"] } }
```

The retry route answers the same body with `200`, since it creates nothing, and
adds its own refusals: `404 Order not found` for an unknown, a foreign, and a
non-numeric order id (one answer, so nobody can probe for somebody else's
order), `409 ORDER_ALREADY_PAID` for a `PAID` order, and
`409 ORDER_NOT_PAYABLE` for a `CANCELLED` one. A free order is `PAID` the moment
it is placed, so its retry is the "already paid" answer too.

## The flow of a checkout

In five lines:

1. **Read and guard**, outside any transaction: `activeCart` (no identity, no
   cart, no line → `CART_EMPTY`), the integer bound, `isShippable`, the phone
   rule. A checkout that could never succeed writes nothing.
2. **TX1**: `lockForCheckout`; `reserve` when the cart carries a coupon;
   `orders.place`; a free order: `confirmFree` and `markCheckedOut`; a live
   order already paid: `markCheckedOut`.
3. **Outside this module's transactions**: `orders.startPayment(order)`, which
   runs the provider call and stores the payment in its own transaction under
   the order row lock; `null` answers `502` and ends here.
4. **TX2**: `markCheckedOut`.
5. Answer `201` with the order id and the checkout URL.

Three details of step 2:

- `lockForCheckout` answering `false` means the cart was closed by a concurrent
  checkout between this one's snapshot read and its lock. The answer is
  `CART_EMPTY`: the customer's cart is gone, and the order that took it is in
  their history. Placing from the stale snapshot would create a second order.
- A placement that answers `unknownArticle` wrote nothing, so the reservation
  taken a moment earlier is released in the same transaction before the
  refusal goes out. A deleted article variant refuses every retry the same way,
  and a reservation has no expiry; without the release the customer who gives
  up would block the coupon's capacity forever.
- `alreadyPlaced` is a success: a second submission is answered with the one
  order that was placed, and `startPayment` answers that order's stored
  checkout URL without a provider call, because the order row lock serializes
  the two starts. Both submissions therefore receive the same body.
  `alreadyPaid` is the same order once it is paid: the cart is closed in TX1
  and the answer is the settled order (see the decision above).

`markCheckedOut` answering `false` is logged at `warn` with the cart id: this
checkout read the cart as `ACTIVE` a moment earlier and has settled an order for
it since, so the only thing that can have closed it is a concurrent checkout of
the very same cart. Nothing is broken for the customer.

### Known limitation: the snapshot is read before the lock

The cart snapshot (`activeCart`, a read-only transaction of the cart module) is
taken **before** TX1 locks the row, as the Kotlin module did and as #247 lists
the steps. A line added to the cart between that read and the lock is not in
the order, and the cart is still closed by TX2. The window is one request's
worth of milliseconds and the customer would have to add a line from a second
tab inside it; the orchestrator recorded the gap on #277 and decided not to
close it in the port. The alternative, when it is ever needed, is an
`activeCart(trx, …)` that reads the snapshot *inside* TX1 after the lock; the
cart module's `checkoutCarts` would grow one `trx`-taking read and the guards
of step 1 would move into TX1.

## The retry route

`POST /api/checkout/orders/{orderId}/payment` exists because a payment that
ended `FAILED`, `EXPIRED`, or `CANCELED` falls out of `ux_payments_live_order`,
so a second payment for the same order is possible, and the storefront offers
it on the order page.

- It has **no body**. Everything the payment needs is read back as a
  `PayableOrder` through `orders.payable`, under the same ownership rule as the
  order reads.
- It reads **no cart**, and closes the one the order came from once the
  payment stands (`PayableOrder.cartId`): a no-op after a normal checkout, the
  repair after a checkout that died between its two transactions (see the
  decision above).
- A live payment answers its stored URL without touching the provider; a
  terminal one starts a second payment row. Two simultaneous retries queue on
  the order row lock and are sent to the same payment.
- It does **not** reserve the coupon again. The reservation was released when
  the payment ended, so the retry competes for whatever capacity is left when
  the order is paid; a `PAID` order without a redemption is the accepted worst
  case, and the order module logs it.

## Composition

```ts
const checkout = createCheckoutModule({
  db,
  checkoutCarts: cart.checkoutCarts,
  promotionCodes: promotion.promotionCodes,   // reserve, release
  orders: order.orders,                       // place, payable, confirmFree, startPayment
  countries: country,                         // isShippable
  guestTokens: auth.guestTokens,
})
app.route('/', checkout.routes)
```

Create it after cart, promotion, order, and country; it is the last module
`createApplication` creates (see
[Module architecture](../conventions/module-architecture.md#application-composition)).
The dependencies are typed as `Pick<…>`, so the tests of the composition hand in
the real modules and nothing else is reachable from here.

## Differences from the Kotlin module

- **Two transactions** instead of five commits (see the decision above).
- **`409 CART_IMAGE_UNAVAILABLE` is gone.** `cart_items.print_image_id` is
  `ON DELETE RESTRICT` and TX1 places from the locked cart's own lines, so a
  line whose image vanished cannot exist.
- **A closed cart under the lock answers `CART_EMPTY`.** Kotlin's second
  submission in that window was answered with the live order through
  `AlreadyPlaced`; here the window is before the placement, and the honest
  answer is that the cart is gone.
- **A free order's retry answers `ORDER_ALREADY_PAID`**, because the order
  module checks the status before the amount; Kotlin's `Free` answer is only
  reachable for a pending order of total zero, which TX1 never leaves behind,
  and maps to `ORDER_NOT_PAYABLE`.
- **The phone rule reads the snapshot**, so the `checkout → article` edge of
  the module graph carries no import today.
- **The discount** on the order is the cart snapshot's `discountCents`, the
  number the customer was shown, rather than a recalculation from the reserved
  promotion; both come from the same promotion row.

## Tests

```sh
bun test src/modules/checkout
```

- `validation.test.ts` is the field-rule matrix of `checkoutInput` through
  `validationFailed`: the frontend's exact body, the optional and validated
  billing address with its ignored contact keys, every required field, the
  lengths, the country shape, the email rules, the phone normalization, and
  the refused unknown property.
- `checkout.test.ts` runs the journeys through the **whole composed
  application** (`createApplication` with a Mollie stub, built by
  `src/test-fixtures.ts`) on PostgreSQL, because the checkout owns no table and
  every assertion is about a row another module wrote: a guest and a signed-in
  checkout with and without a coupon, the free order (paid, redeemed,
  production requested, no provider call), the refused provider (order
  cancelled, reservation released, cart active), the empty cart, the
  unshippable country, the SPOD phone rule, the expired coupon, the
  unresolvable line that gives its coupon back, the two races (two
  submissions of one cart end as one order and one payment; two carts racing
  the last unit of a coupon end as one order and one conflict), and the cart
  whose live order was paid while it stayed open (settled, not `502`). The retry
  matrix covers the live payment, the terminal payment that frees the coupon,
  two simultaneous retries, the retry after a checkout that died before TX2
  (the cart ends `CHECKED_OUT`), the foreign and malformed ids, and the paid,
  cancelled, and free orders. The dying checkout is simulated with a trigger
  that refuses the cart's close, the same technique the order tests use for
  atomicity.

The journey tests build the composition root from inside the module folder.
Test files and `test-fixtures.ts` may import the composition root by the `test`
file policy in `backend-ts/eslint.config.ts`.
