# Backend Payment package

`backend/modules/payment/src/shop/voenix/payment`

> **Status: skeleton.** The payment migration writes this guide in its last
> ticket (T4). What is below is the shape of the module as it exists after T3 —
> schema, Mollie adapter, `start` flow, webhook, and the status read path. The
> decided design and every deviation live in
> [`docs/migration/payment-migration.md`](../../migration/payment-migration.md).

## What this package does

It collects the money for an order, through Mollie, and tells the order module
when that money has arrived.

The module is deliberately small on the outside: it has **one** HTTP route (the
Mollie webhook), **one** exported capability (`PaymentModule.statusSource`, the
order module's `OrderPaymentStatusSource`), and **one** internal entry point
(`PaymentService.start`, whose caller arrives with the Wave-3 Checkout
migration). The two legacy endpoints — `POST /api/payments` and
`GET /api/payments/{id}` — are not migrated (deviation D1).

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
- The status an order answer carries comes from here, through
  `OrderPaymentStatusSource`. The *list* read (`stored`) never calls Mollie; the
  *single* read (`refreshed`) asks about a payment that is still `OPEN`,
  `PENDING`, or `AUTHORIZED` and confirms the order when Mollie reports it paid
  — the fallback for a webhook that never arrived, on exactly the same path the
  webhook takes (amount check D11, paid-but-cancelled D14 included). A provider
  that cannot be reached degrades to the stored status with a WARN
  (deviation D12).
- An order can have several payments; the one it *shows* is its live payment,
  or, when none is live, its last attempt.
- The status vocabulary is the order module's `OrderPaymentStatus`, not a type
  of this module: `payments.status` stores exactly those seven words. Which of
  them are terminal is this module's business alone, because only its partial
  unique index cares.

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
- `PaymentStatusIntegrationTest` — the batch read's zero provider calls, the
  refresh matrix over all seven statuses, and the refresh that confirms an order.
- `PaymentRoutesTest` — the secret, the body, and the outcome → status table.
- `MolliePaymentClientTest` — the provider contract, and that nothing the
  provider wrote ever reaches a log line.
- `MollieSettingsTest` — the configuration rules.

## To be written in T4

The `start` flow in prose, the composition section with its diagram, the dev
setup (test key + ngrok), and "what is deliberately not here".
