# Payment post-migration work

Durable follow-up work from the Payment migration
([`payment-migration.md`](payment-migration.md)) that belongs to operations, to
the frontend, or to a later module migration. General Payment behavior and
decisions stay in the record; this file holds only what outlives it.

The Checkout hooks below are **delivered**: the checkout module migrated on
2026-08-02 and is the real caller of the payment start (see
[`checkout-migration.md`](checkout-migration.md)). What remains open here is
operations and frontend work.

## Admin-dashboard anomaly page — owner: future admin-dashboard work (Joe, 2026-08-01)

Four situations are handled as far as software can handle them and then
deliberately left to a human. Today the evidence is a log line; the decision of
2026-08-01 is that a future admin dashboard lists them instead. All four come
from the same design choice: a webhook answer is a message to Mollie about
redelivery, so nothing that a redelivery cannot fix may answer with an error.

- [ ] **A `PAID` payment for a `CANCELLED` order** (deviation D14). The money
  moved for something this shop will not produce, and the refund is manual. The
  ERROR line already carries everything a refund needs — payment id, Mollie
  payment id, amount in cents, order id — and the payment stays `PAID`. The page
  has to make this findable without anybody reading a log, and should link the
  refund action.
- [ ] **Stuck `PENDING` orders whose payment ended terminally** (deviation D9).
  No automatic order cancellation happens on a `FAILED`, `EXPIRED`, or `CANCELED`
  payment: one order stays the customer's order across payment attempts. The
  price of that decision is that such an order sits `PENDING` until somebody
  looks. It is a customer-service case, and the query behind it is simple —
  orders in `PENDING` whose current payment is terminal.
- [ ] **Aged non-terminal payments, and the reconciliation sweep.** A payment
  that stays `OPEN`, `PENDING`, or `AUTHORIZED` for a long time is either a
  customer who never finished or a webhook this backend never received. The
  missed-webhook case repairs itself the moment the customer opens their order
  (`refreshed`), but nobody guarantees they ever will. A scheduled sweep over
  aged non-terminal payments — the same `refreshed` path, run by a worker instead
  of by a customer — was explicitly **not** built now (decision 2026-08-01,
  point 7); this page owns it together with the list of aged payments.
- [ ] **Unknown-webhook-id noise.** A delivery naming a payment this backend
  never created answers `200` and a WARN (deviation D2). With the webhook secret
  in place (D3) that should be rare, so a rising count is a signal: a leaked
  secret, a shared Mollie account, or a stale tunnel from someone's laptop. The
  page should count it rather than let it drown in the log.

- [ ] **Orphaned promotion reservations** (deviation D2 of the Checkout
  migration, Joe 2026-08-02). A `promotion_reservations` row holds the capacity
  of a coupon and has **no expiry**: it ends only through a redemption, through
  the cancellation of its order, or when that order's payment ends terminally. So
  a crash between the reservation commit and the placement, and a terminal
  webhook that never arrives, both leave a row that blocks its capacity
  **forever**. Joe accepted that consequence explicitly on the condition that
  this page lists such rows and lets an administrator delete them. The query is a
  join a human can read: reservations whose cart has no live order, plus
  reservations of orders that are `CANCELLED` or whose payment is terminal. It
  belongs next to the stuck-`PENDING` list above, because the same missed webhook
  produces both.

The `SUPERSEDED` outcome belongs in the same view: a dead payment reporting
itself `PAID` next to a live payment for the same order means the customer may
have been charged twice, and that is settled by hand as well.

## Checkout hooks — delivered by the Checkout migration (2026-08-02)

This section is history rather than a to-do list; each hook is recorded with
where it ended up.

- **The start has a caller and a capability of its own.** The payment module
  exports `PaymentStarter.start(order: PayableOrder)` on its handle, and the
  checkout module calls it. `PaymentRequest` was **deleted** (deviation D14 of
  the Checkout migration): the input is now the order-declared `PayableOrder`
  snapshot, the same direction the `OrderPaymentGateway` established. The
  boundary held — the payment module still never reads `orders`; the order module
  hands it the snapshot.
- **The `null` answer is handled without guessing.** The checkout answers
  `502 PAYMENT_NOT_STARTED` with a message that deliberately claims nothing about
  the order (deviation D7 of the Checkout migration), because `null` covers both
  cases and the caller cannot tell them apart: the refused creation whose
  compensation already cancelled the order (D10), and the doubly-vacated race
  that leaves it `PENDING` (D21). It does not cancel anything a second time, and
  it does **not** close the cart — the customer's next attempt must find it. Both
  endings are pinned by `CheckoutFlowCompositionIntegrationTest` in the app
  module.
- **The retry-payment flow exists.** It is
  `POST /api/checkout/orders/{orderId}/payment` (Joe's path decision of
  2026-08-02, deviation D16 of the Checkout migration): no body, ownership
  checked — a foreign order is a `404` and reaches no provider — and built
  exclusively from the stored order snapshot, so it needs no cart at all. A live
  payment answers its stored URL, a terminal one starts a second payment row, and
  a paid, cancelled, or free order is a `409`.
- **`carts.status = 'CHECKED_OUT'` is written** by the checkout, through the cart
  module's `CheckoutCarts.markCheckedOut`, and the retry really does not need a
  cart — see above.
- **`AlreadyPlaced` is treated as a success**, so a double-submitted checkout
  ends as one order, one payment row, and two identical answers (deviation D15;
  also listed in [`order-post-migration.md`](order-post-migration.md)).

## Frontend adaptation — owner: frontend work

- [ ] **`paymentStatus` is back in the order response.** The Order migration
  removed it (its deviation D5) and this migration returns it, on both
  `GET /api/orders` and `GET /api/orders/{orderId}`. It is a **string or
  `null`**, and the values are uppercase: `OPEN`, `PENDING`, `AUTHORIZED`,
  `PAID`, `FAILED`, `CANCELED`, `EXPIRED`. `null` means the order has no payment
  at all — a free order, or a checkout that was never started — so the UI needs a
  branch for it rather than a default label.
- [ ] **Mind the spelling.** The payment value is `CANCELED` with **one** L,
  while the order `status` value is `CANCELLED` with **two**. They are different
  facts from different systems (Mollie cancelled the payment; the shop cancelled
  the order) and must stay two words in the TypeScript types as well.
- [x] **Do not poll `/api/payments`.** There is no payment endpoint for clients
  (deviation D1). The order detail read is the status source, and it is also what
  repairs a missed webhook — opening the order refreshes a still-running payment
  from Mollie. Done by issue #93: the checkout store's `fetchOrderStatus` reads
  `GET /api/orders/{orderId}`, and the order confirmation page refreshes it on a
  **bounded, backing-off** ladder (`useOrderStatusRefresh`) instead of asking
  every three seconds forever.
- [x] **The frontend never calls Mollie either.** Checkout answers a
  `checkoutUrl` and the customer is sent there; the redirect back carries
  `orderId` as a query parameter. Done by issue #93, which also wired the retry
  route: an order that is placed but unpaid offers
  `POST /api/checkout/orders/{orderId}/payment`, whose `409 ORDER_ALREADY_PAID` /
  `ORDER_NOT_PAYABLE` are rendered as their own messages.

## Local development setup — owner: whoever runs the backend locally

There is deliberately no dummy mode (deviation D16, decision 2026-08-01): a
provider stub that answers "paid" without money moving is the one stub whose
accidental activation in production nobody would notice until the bank
statement. Local development therefore uses Mollie's own test mode, exactly as
the legacy application did.

The backend refuses to start without a valid `Mollie:` block, so this is setup,
not an optional extra.

1. **Get a test API key.** In the Mollie dashboard, switch the organization to
   test mode and copy the key that starts with `test_`. It moves no real money;
   the payment screens offer buttons for "paid", "failed", "expired", and so on.
2. **Expose your machine to the internet.** Mollie calls the webhook from
   outside, so `localhost` is not reachable for it. Start a tunnel — for example
   `ngrok http 8080` — and note the `https://….ngrok-free.app` address it prints.
   It changes every time you restart the tunnel unless you have a reserved
   domain.
3. **Invent a webhook secret** of at least 16 characters. Any generated UUID
   works: `uuidgen` on macOS prints one.
4. **Set the four environment variables** before `./kotlin run` (from
   `backend/`). The webhook URL is the tunnel address plus the route **plus the
   secret as its last path segment** — the secret is part of the address Mollie
   calls, which is also why the URL has to be HTTPS:

   ```sh
   export MOLLIE_API_KEY="test_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
   export MOLLIE_REDIRECT_URL="http://localhost:5173/order-confirmation"
   export MOLLIE_WEBHOOK_SECRET="0b6f1c3e-9f2a-4d51-8c0e-7c4a1f2b3d5e"
   export MOLLIE_WEBHOOK_URL="https://<your-tunnel>.ngrok-free.app/api/payments/webhook/$MOLLIE_WEBHOOK_SECRET"
   ```

   `MollieSettings` validates all four in its constructor, so a typo fails the
   startup with a clear message instead of failing on the first customer.

   `MOLLIE_REDIRECT_URL` has to be the page that reads the order back, because
   the backend appends `?orderId=…` to it (deviation D19). In the Vue frontend
   that page is the `order-confirmation` route.
5. **Check the wiring** by watching the backend log while you finish a test
   payment. A delivery that reaches the application logs nothing on the happy
   path and answers `200`; a `403` in the tunnel's request list means the secret
   in `MOLLIE_WEBHOOK_URL` and `MOLLIE_WEBHOOK_SECRET` have drifted apart.

Since the Checkout migration of 2026-08-02 there is an HTTP way to start a
payment: `POST /api/checkout` with a filled cart, and
`POST /api/checkout/orders/{orderId}/payment` to try again. A full local round
trip therefore needs the key and the tunnel above.

Without them, the integration tests drive the same flow against a stubbed
provider — the payment module's own suites, and the composed checkout journeys in
the app module:

```sh
./kotlin test --include-module payment
./kotlin test --include-module app
```

## Accepted consequences worth revisiting later

These are decided, not open — recorded so a later feature does not rediscover
them as bugs.

- **A payment refund is invisible to this backend.** `PAID` is never refreshed
  from Mollie, because the shop tracks no refunds. If refunds are ever offered,
  the refresh rule in `PaymentService.REFRESHABLE_STATUSES` and the meaning of a
  `PAID` order both have to be revisited.
- **The webhook is protected by a secret in the URL, not by a signature.** Mollie
  sends no signature, so this is the available mechanism (deviation D3). The
  consequence is that the secret leaks with any log, proxy, or browser history
  that records full URLs on the path — which is why the URL must be HTTPS and why
  the secret is redacted in `MollieSettings.toString()`. Rotating it means
  changing the environment variable and the Mollie dashboard together.
- **A provider failure during a status read is answered with the stored status**
  (deviation D12), so a customer can briefly see a status that is one step behind
  reality. That is deliberate: a display read must not become a `502` because
  Mollie is slow.
- **Guest-token lifetime** and the other cross-cutting open questions stay in
  [`all-post-migration.md`](all-post-migration.md); the payment module widened no
  surface they apply to.
