# Backend Payment package

`backend/modules/payment/src/shop/voenix/payment`

> **Status: skeleton.** The payment migration writes this guide in its last
> ticket (T4). What is below is the shape of the module as it exists after T2 —
> schema, Mollie adapter, `start` flow, and webhook. The status read path
> (`OrderPaymentStatusSource`, `OrderView.paymentStatus`) arrives with T3 and is
> not described here yet. The decided design and every deviation live in
> [`docs/migration/payment-migration.md`](../../migration/payment-migration.md).

## What this package does

It collects the money for an order, through Mollie, and tells the order module
when that money has arrived.

The module is deliberately small on the outside: it has **one** HTTP route (the
Mollie webhook) and **one** internal entry point (`PaymentService.start`, whose
caller arrives with the Wave-3 Checkout migration). The two legacy endpoints —
`POST /api/payments` and `GET /api/payments/{id}` — are not migrated
(deviation D1).

## The five-minute mental model

- A **payment** is one *attempt* at collecting the money for one order. An order
  can have several over its life; at most one of them is *live* at a time.
- Which one is live is decided by the partial unique index
  `ux_payments_live_order`, not by any column and not by any code. A payment
  that failed, was cancelled, or expired falls out of it, and a retry then
  becomes a second payment for the same order (deviation D9).
- `start` is idempotent by that index: a double-clicked checkout ends as one row
  and one checkout URL, and the attempt that lost the race has its provider
  payment cancelled.
- The webhook trusts nothing it is sent. It reads the payment id and asks Mollie
  what the status is; a forged body changes nothing.
- A terminal payment status never cancels the order. Only a provider that
  refused to create a payment at all does (deviation D10).

## HTTP API

| Method | Path | Who | Answers |
| --- | --- | --- | --- |
| `POST` | `/api/payments/webhook/{secret}` | Mollie | `200` empty; `403` wrong secret; `400` missing id; `502` provider; `500` database |

The route installs none of the application's auth or CSRF subtrees — Mollie has
no session and sends no token. The secret path segment takes their place
(deviation D3) and is compared, in constant time, before anything else happens.

The full outcome table lives on `PaymentConfirmation`, and the reasoning behind
each status is in the migration record's webhook contract.

## The schema

`payments`, created by `V17__create_payments.sql`. See the migration record for
the DDL and the rationale of every dropped legacy column.

## Configuration

`MollieSettings` reads the `Mollie:` block: `ApiKey`, `RedirectUrl`,
`WebhookUrl` (HTTPS only), `WebhookSecret`. Every value is validated in the
constructor, so a misconfigured deployment fails before Flyway runs. There is no
dummy mode (deviation D16); local development uses a Mollie test key and a
tunnel.

## Tests

- `PaymentSchemaIntegrationTest` — every constraint and both indexes.
- `PaymentIdempotencyIntegrationTest` — the races, and the two compensations
  that must survive a cancelled request.
- `PaymentWebhookIntegrationTest` — what one delivery does to payment and order.
- `PaymentRoutesTest` — the secret, the body, and the outcome → status table.
- `MolliePaymentClientTest` — the provider contract, and that nothing the
  provider wrote ever reaches a log line.
- `MollieSettingsTest` — the configuration rules.

## To be written in T4

The five-minute model of the status read path, the `start` flow in prose, the
composition section, the dev setup (test key + ngrok), and "what is deliberately
not here".
