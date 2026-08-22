# Payment (Mollie) module migration

Migration of the legacy .NET Payment feature into the Kotlin `payment` module,
planned and decided by the migration council (orchestrator, Opus, Codex) on
2026-07-31/2026-08-01. General rules live in
[`module-migration-guide.md`](module-migration-guide.md); this file records
only module-specific facts, decisions, and history.

## Status

`complete`

Phase 1 (council brainstorming, two rebuttal rounds, Joe's decisions) decided
the plan; Phase 2 implemented it ticket by ticket (T1–T4) on the
`payment-migration` branch; Phase 3 (2026-08-01) verified it with three
independent reviews (orchestrator, Opus, Codex), one rebuttal round per
contested finding, and one consolidated fix commit — reversing D21 and adding
D25–D27 — followed by the post-migration simplification review (no findings)
and the retrospective below. Full quality gate at closeout: `./kotlin check`,
950 tests, 0 failures. The consolidated findings and their outcomes are a
comment on PR #67.

The delivered module is described for readers in
[`../dev/backend/payment-package.md`](../dev/backend/packages/payment-package.md); its
place in the composition is in
[`../dev/backend/module-architecture.md`](../dev/backend/conventions/module-architecture.md).

## Task parameters

Target module:

`payment`

Source feature:

`../voenix-shop/backend/Voenix.Api/Features/Payment` (controller, service,
domain, DTOs, exceptions), plus `Configuration/MollieOptions.cs`, the payment
branches of `Features/Checkout/Services/CheckoutService.cs` (payment creation,
create-failure compensation, the `paymentStatus` joins), and the payment rows
of `ErrorHandling/DomainExceptionHandler.cs`. Behavior evidence:
`Voenix.Api.Tests/Features/Payment/PaymentServiceTests.cs`.

Target package:

`backend/modules/payment/src/shop/voenix/payment`

Analysis checkpoint:

`wait-for-approval` — satisfied; approvals recorded in the decision log on
2026-08-01.

Known consumers:

- Mollie itself (`POST /api/payments/webhook` — the only external HTTP
  consumer of the legacy feature).
- The Vue frontend **never** calls `/api/payments` directly (verified by
  grep); it follows the `checkoutUrl` from checkout and polls the order
  status for `paymentStatus`.
- Wave-3 Checkout is the future caller of payment creation.
- The order module consumes the payment status for its responses
  (`paymentStatus`, absent since Order deviation D5).

Approved deviations from current behavior:

- See the deviation log below; the money-affecting ones are D1 (webhook-only
  surface), D2 (unknown webhook id answers 200), D9 (payment lifecycle), D11
  (amount check on PAID), D14 (manual refund for PAID-on-CANCELLED).

Explicitly deferred work — all of it recorded in
[`payment-post-migration.md`](payment-post-migration.md) since T4:

- Admin-dashboard anomaly page (PAID-for-CANCELLED orders, stuck `PENDING`
  orders whose payment is terminal, aged non-terminal payments/reconciliation,
  unknown-webhook-id noise) — owner: future admin-dashboard work (Joe,
  2026-08-01).
- ~~Wave-3 Checkout: the caller of `start`, the retry-payment flow for an order
  whose payment ended terminally, cart `CHECKED_OUT`, and
  `OrderWriteResult.AlreadyPlaced` handling.~~ **Delivered 2026-08-02** by the
  Checkout migration, which also replaced `PaymentRequest` with the
  order-declared `PayableOrder` and made `start` reachable as the exported
  `PaymentStarter` (see [`checkout-migration.md`](checkout-migration.md)).
- Frontend adaptation: the returning `paymentStatus` field (see contract).
- Local development setup (Mollie test key + tunnel, D16), written down there
  because it outlives the migration.

## The decided design

### Module cut and dependency direction

New compilation module `backend/modules/payment` (flat package). Compile-time
edge: **`payment → order`** (council consensus after rebuttal; Codex conceded).
The order module declares the exchange vocabulary; payment implements it. Only
the harmless display read is late-bound; the money path (webhook → confirm) is
a direct call. Rationale on record: `cart` re-exports `order`, so the inverse
direction would make every order consumer compile against the Mollie
integration.

New in `backend/modules/order` (this migration owns these changes):

- `OrderPaymentStatus` — public enum `OPEN, PENDING, AUTHORIZED, PAID, FAILED,
  CANCELED, EXPIRED` (Mollie's set; `CANCELED` with one L next to order
  `CANCELLED` with two — deliberate, must be named in the package guides).
- `OrderPaymentGateway` — public interface, implemented by the order module
  itself and exported on `OrderModule`:
  `confirm(orderId): OrderPaymentOutcome` and
  `cancel(orderId): OrderPaymentOutcome`.
- `OrderPaymentOutcome` — public enum `APPLIED, ALREADY_APPLIED,
  UNKNOWN_ORDER, REFUSED`. The five internal `PaidOrderResult` values map
  onto it **inside the order module**; `PromotionRefused → APPLIED` (a paid
  order is structurally not a payment failure — deviation D13). For `cancel`:
  `PENDING → CANCELLED` = `APPLIED`, already `CANCELLED` = `ALREADY_APPLIED`,
  `PAID` = `REFUSED` (a paid order is never cancelled by a payment failure),
  unknown = `UNKNOWN_ORDER`. Both writes take the same `SELECT … FOR UPDATE`
  row lock as `markPaid`, so confirm and cancel of one order serialize.
- `OrderPaymentStatusSource` — public interface, declared by order,
  implemented by payment, late-bound by the app:
  `stored(orderIds: Set<Long>): Map<Long, OrderPaymentStatus>` (list read,
  never calls Mollie) and `refreshed(orderId: Long): OrderPaymentStatus?`
  (single-order read; refreshes `OPEN/PENDING/AUTHORIZED` from Mollie and may
  confirm the order — the missed-webhook fallback).
- `OrderView` gains `paymentStatus: OrderPaymentStatus?` (`null` = no payment
  row: free order or payment not started).
- `OrderRepository.liveOrderOfCart`: the `checkNotNull` becomes a single
  bounded retry of the placement insert (a cancellation racing a placement is
  now reachable because `cancel` writes `CANCELLED`); `OrderWriteResult`'s
  outcome set stays unchanged.

New in `backend/app`: `LateBoundPaymentStatus` (the `LateBoundProductionSource`
pattern — fail loud while unbound), `MollieSettings` in `ApplicationSettings`,
a `Mollie:` block in `application.yaml`, payment install **after** order:

```kotlin
val paymentStatus = LateBoundPaymentStatus()
val order = installOrderModule(…, payments = paymentStatus, …)
val payments = installPaymentModule(database, settings.mollie, order.payments)
paymentStatus.bind(payments.statusSource)
```

### Payment module type map (planned production files)

| File | Visibility | Meaning |
| --- | --- | --- |
| `PaymentModule.kt` | public handle, internal factory | Runtime assembly; exports `statusSource` |
| `MollieSettings.kt` | public | `apiKey`, `redirectUrl`, `webhookUrl`, `webhookSecret`, constructor-only `apiUrl`; validated at construction (fail before Flyway); `toString` redacts the key. **No dummy mode** (decision 2026-08-01) |
| `Payments.kt` | internal | The Exposed table |
| `PaymentRepository.kt` | internal | The only code touching `payments`; nested `Insertion` result for the live-conflict case |
| `PaymentService.kt` | internal | `start`, webhook confirmation, status source implementation |
| `PaymentOperations.kt` | internal | Route test seam: `confirm(molliePaymentId): PaymentConfirmation` |
| `PaymentConfirmation.kt` | internal | Webhook outcome → HTTP mapping, incl. `SUPERSEDED` |
| `PaymentRequest.kt` | internal | `start` input from the future Checkout caller: `orderId`, `amountCents`, `email`, `phone`, billing/shipping `Address` |
| `MolliePayments.kt` | internal | The provider port: `create`, `find`, best-effort `cancel`; failures answer `null`/`false`, `CancellationException` passes through |
| `MolliePayment.kt` | internal | The provider answer: id, status, checkoutUrl, amountCents |
| `StoredPayment.kt` | internal | The service's read model of a payment row: `paymentId`, `orderId`, `molliePaymentId`, `status`, `amountCents`, `checkoutUrl` |
| `MolliePaymentClient.kt` | internal | Ktor-client adapter (`FalImageGenerator` precedent); amount as exact two-decimal string via `BigDecimal` (never locale-dependent formatting); phone normalization via libphonenumber; Idempotency-Key header; short timeouts (~5 s connect / 10 s request) |
| `PaymentRoutes.kt` | internal | The single webhook route |

No Java Mollie SDK (two endpoints; the repo's provider-logging rules are not
enforceable through an SDK). No new external dependency — libphonenumber is
already in `libs.versions.toml`.

### `start` flow (the Wave-3 caller arrived 2026-08-02: `start` is now the public `PaymentStarter`, implemented by `PaymentLauncher`)

1. Fast path: a live payment for the order exists → answer its stored
   `checkout_url` (no provider call; the double-clicked checkout).
2. Otherwise `MolliePayments.create` (with a fresh per-attempt Idempotency-Key
   — semantics to be verified against Mollie's live docs during
   implementation, D17), then insert via `executePostgresWrite`.
3. `23505` from `ux_payments_live_order`: re-read the winner in a fresh
   transaction, answer its `checkout_url`; best-effort Mollie-cancel of the
   loser under `withContext(NonCancellable)` (an open orphan is the hazard).
   A re-read that finds *no* winner is the vacated slot and retries the insert
   once with the same created payment — see the reversed **D21** below for the
   delivered shape.
4. Mollie refused/unreachable: `orders.cancel(orderId)` under
   `NonCancellable` (the legacy compensation, moved from Checkout into the
   payment module — D10), WARN naming order id and idempotency key, answer
   "no payment started".

### Webhook contract

`POST /api/payments/webhook/{secret}` — form-encoded field `id`; the secret
path segment comes from `MollieSettings.webhookSecret` and is Joe's 2026-08-01
condition (D3): reject a wrong or missing secret with `403` before any
processing. The route installs none of the auth/CSRF subtrees (Mollie cannot
send tokens); the body is **never** trusted — only the `id` is read, and the
status comes from Mollie's API.

| Situation | Response |
| --- | --- |
| Wrong webhook secret | `403`, nothing read (missing secret segment: `404`, D23) |
| Missing/blank `id` | `400` |
| Unknown mollie payment id — a payment *Mollie knows* and this backend never created | `200` + WARN (deviation D2 from legacy `404`) |
| An id Mollie itself does not know (its read is refused) | `502`, like any other unusable provider answer — the status is fetched before the row is looked up, so the two cases never mix |
| Provider unreachable/unreadable/unknown status value/answer about another payment | `502` (Mollie retries) |
| Status recorded, not `PAID` | `200` |
| `PAID`, order confirm `APPLIED`/`ALREADY_APPLIED` | `200` (confirm fires even when the stored status was already `PAID` — idempotent re-delivery) |
| `PAID`, amount mismatch vs stored `amount_cents` | order **not** confirmed, ERROR, `200` (D11) |
| `PAID` for a `CANCELLED` order (`REFUSED`) | `200` + ERROR naming orderId, payment ids, amount — manual refund via runbook/admin page (D14) |
| Status update refused by `ux_payments_live_order` (dead payment resurrecting next to a live one) | `SUPERSEDED`: ERROR ("may have been charged twice"), confirm still fires on `PAID`, `200` |
| Database failure | `500` (Mollie retries) |

### Schema — Flyway `V17__create_payments.sql`

```sql
CREATE TABLE payments (
    id bigint GENERATED BY DEFAULT AS IDENTITY,
    order_id bigint NOT NULL,
    mollie_payment_id character varying(64) NOT NULL,
    status text NOT NULL,
    amount_cents integer NOT NULL,
    checkout_url text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT ux_payments_mollie_payment_id UNIQUE (mollie_payment_id),
    CONSTRAINT ck_payments_status CHECK (
        status IN ('OPEN','PENDING','AUTHORIZED','PAID','FAILED','CANCELED','EXPIRED')
    ),
    CONSTRAINT ck_payments_amount_cents_positive CHECK (amount_cents > 0),
    CONSTRAINT fk_payments_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT
);

-- One live payment per order (the Wave-2 half of Order deviation D10). A
-- payment that failed, was cancelled, or expired falls out of the index, so a
-- Wave-3 retry may start a second payment for the same order; two live
-- payments for one order fail with 23505 instead of charging twice.
CREATE UNIQUE INDEX ux_payments_live_order
    ON payments (order_id)
    WHERE status NOT IN ('FAILED','CANCELED','EXPIRED');

-- The order-list join.
CREATE INDEX ix_payments_order_id ON payments (order_id);
```

Dropped legacy columns: `currency` (EUR is a constant everywhere in this
system — D4), `description` (always `Order #<id>`, built at send time — D5),
`redirect_url` (derivable from settings + orderId — D5). Kept: `checkout_url`,
promoted to `NOT NULL`, because the idempotent repeated `start` now actually
reads it (D6). `mollie_payment_id` narrowed to `varchar(64)` (D7).

## Behavior matrix

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| Reject non-positive amount | `PaymentService.cs:29`; test `…WhenAmountIsZero` | Required | `require` in `start` (caller is a module, not HTTP) + DB `CHECK` | Service test + schema test |
| Cents → exact two-decimal string for Mollie | `PaymentService.cs:32` | Required | `BigDecimal.valueOf(cents, 2).toPlainString()` | Adapter test under a comma-decimal default locale (`4070 → "40.70"`) |
| Append `orderId` to the redirect URL (`?`/`&`) | `PaymentService.cs:35-39`; test `…AppendsOrderIdToRedirectUrl` | Required (mechanism incidental) | `URLBuilder` parameter append (D19) | Adapter test: URL with and without existing query |
| Billing + shipping address, email, street+house join, `metadata {"orderId": n}` | `PaymentService.cs:41-96`; test `…SendsBillingAndShippingAddress` | Required | Checkout hands the data in as `PaymentRequest`; payment never reads order rows | Adapter test asserting the full JSON body |
| Phone → E.164 via libphonenumber; invalid → absent; no `+` and no country → absent | `PaymentService.cs:262-285`; tests `…NormalizesPhone…`, `…OmitsInvalidPhone…` | Required | Same four-case matrix, trimmed value used consistently (D18) | Adapter tests: the four legacy cases + invalid + no-country |
| Missing checkout URL in Mollie's answer is a failure | `PaymentService.cs:110-112` | Required | `MolliePayments.create` answers `null` | Adapter test |
| Create failure → order `CANCELLED` | `CheckoutService.cs:146-153` | Required (moved) | `orders.cancel` inside `start`, `NonCancellable` (D10) | Service test incl. cancelled-coroutine case with a suspending fake |
| Persist payment; answer (payment, checkoutUrl) | `PaymentService.cs:114-132` | Required | Insert via `executePostgresWrite`; live-conflict → winner's URL | Idempotency integration tests |
| Webhook reads only `id`; status fetched from Mollie, body never trusted | `PaymentController.cs:23-32`, `PaymentService.cs:138-150` | Required | Same | Route test: forged `status=PAID` in the body is ignored; a provider `GET` is asserted |
| Missing `id` → 400 | `PaymentController.cs:27-29` | Required | Same | Route test |
| Unknown mollie id → 404 | `PaymentService.cs:141-145` | Proposed deviation | `200` + WARN (D2) | Route test |
| Store status only when changed, bump `updated_at` | `PaymentService.cs:195-206` | Required | Same | Service test (repeated identical status leaves `updated_at`) |
| `PAID` triggers paid-order processing even when stored status already `PAID` | test `…WhenPaidStatusIsUnchanged` | Required | `confirm` fires regardless; order-side lock makes it `ALREADY_APPLIED` | Webhook integration test |
| Non-`PAID` statuses trigger nothing on the order | test `…DoesNotDelegate…WhenStatusIsNotPaid` | Required | Same — **no** order cancellation on terminal status (D9, Joe 2026-08-01) | Webhook test: `FAILED`/`EXPIRED`/`CANCELED` leave the order `PENDING` |
| Status read refreshes `OPEN/PENDING/AUTHORIZED` from Mollie | `PaymentService.cs:158-162,220-224`; test `…RefreshesOpenPayment…` | Required (load-bearing missed-webhook fallback) | `refreshed(orderId)` on the single-order read only; may confirm the order | Status integration tests: refresh on the three statuses, zero calls on terminal; refresh observing `PAID` confirms the order (production row + email job exist) |
| List read joins stored statuses without provider calls | `CheckoutService.GetOrdersForUserAsync` reads `db.Payments` directly | Required | `stored(orderIds)` batch read | Test: 20 orders, zero provider calls |
| Provider failure during status read → 502 | Legacy exception mapping | Proposed deviation | WARN + stored status answered (D12) | Status test |
| Unknown Mollie status string is an error, nothing written | `PaymentService.cs:287-290` | Required | Adapter answers `null`; webhook `502`; raw value never logged (AGENTS.md rule) | Adapter + route test asserting the log |
| `paymentStatus` in order responses | `CheckoutService.cs:164-231`; Order D5 follow-up | Required (returning contract) | `OrderView.paymentStatus`, uppercase values, `null` without payment | Order route tests |
| Amount check on `PAID` | none (new) | Approved addition | Mismatch → no confirmation, ERROR (D11) | Webhook test |

## Operation contract

| Operation | Required input | Required success value | Required errors | Ordering |
| --- | --- | --- | --- | --- |
| Webhook confirm | form `id` + URL secret | `200`, empty body | `403` secret, `400` missing id, `502` provider, `500` db | n/a |
| `start` (internal, Wave-3 caller) | `PaymentRequest` | checkout URL (existing live one on repeat) | "no payment started" after compensation | n/a |
| `stored` (via order list) | order ids | map orderId → status | — (absent = no payment) | n/a |
| `refreshed` (via order detail) | order id | status or `null` | provider failure degrades to stored status | n/a |

Example webhook request (Mollie's fixed shape):

```
POST /api/payments/webhook/<secret>
Content-Type: application/x-www-form-urlencoded

id=tr_WDqYK6vllg
```

→ `200`, empty body.

Example order-detail response after this migration (the only public JSON
change — one added field):

```json
{
  "orderId": 42,
  "createdAt": "2026-08-01T10:15:30Z",
  "status": "PENDING",
  "paymentStatus": "AUTHORIZED",
  "subtotal": 3800,
  "shippingCost": 490,
  "discountAmount": 220,
  "total": 4070,
  "items": [ { "…": "unchanged" } ]
}
```

`paymentStatus` is flat on both sides (it is an enum, not a sealed value — no
nesting asymmetry possible), uppercase, `null` for an order without a payment.

## Decision log

### 2026-07-31 — Council brainstorming and rebuttal round

Identical briefing to Opus and Codex; three independent proposals. Consensus
without Joe: webhook-only surface recommendation, hand-written Ktor Mollie
client, `payments.order_id NOT NULL` + FK RESTRICT, dropped write-only
columns, batch/refresh split for status reads, webhook hardening (untrusted
body, idempotent PAID re-delivery, unknown id → 200 recommendation).
Rebuttal outcomes: dependency direction `payment → order` (Codex conceded);
5→4 outcome mapping inside order (Codex conceded); Idempotency-Key adopted,
`ReconciliationRequired` rejected (both converged); create-failure
compensation kept (Codex conceded). On the payment lifecycle the two members
swapped positions; the conflict went to Joe.

### 2026-08-01 — Joe's decisions on the eight open points

1. **Lifecycle (D9):** the customer-friendly model. One order stays the
   customer's order across payment attempts; **no** automatic order
   cancellation on a terminal payment status; a stuck `PENDING` order is a
   customer-service case, later surfaced on an admin-dashboard anomaly page.
   Consequence: local `id` PK, partial unique index `ux_payments_live_order`,
   a Wave-3 retry is a second payment for the **same** order. The council's
   auto-cancel proposal is rejected.
2. **Webhook-only surface (D1):** approved; no ops tool uses the two legacy
   endpoints.
3. **Unknown webhook id → 200 (D2):** approved. Condition (D3): the webhook
   must be protected by a secret so nobody but Mollie can trigger processing —
   implemented as a secret URL segment validated before any work.
4. **Dev story (D16):** no dummy mode. Local development uses a Mollie test
   key and an ngrok tunnel, as in legacy.
5. **Amount check on PAID (D11):** approved.
6. **PAID for a CANCELLED order (D14):** manual refund; ERROR log carries
   everything a human needs; a future admin-dashboard page will list such
   anomalies.
7. **Reconciliation sweep:** not built now; the same future admin-dashboard
   page owns it. Deferred with owner.
8. **Schema cleanups (D4–D7):** approved as a package, including the
   `varchar(64)` narrowing.

## Deviation and uncertainty log

| # | Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- | --- |
| D1 | `POST /api/payments`, `GET /api/payments/{id}` | `PaymentController.cs:14-40`; no frontend consumer; IDOR on GET, unchecked client input on POST | Not migrated; create/status are module capabilities | Contract removal + security | Joe 2026-08-01 | — |
| D2 | Unknown webhook id answered `404` | `PaymentService.cs:141-145` | `200` + WARN | Contract change | Joe 2026-08-01 | — |
| D3 | Webhook anonymous without secret | legacy route | Secret URL segment, `403` before processing | Hardening (new, Joe's condition) | Joe 2026-08-01 | — |
| D4 | `currency` column, always `'EUR'` | `Payment.cs:8`; whole system is EUR cents | Dropped; EUR constant in the adapter | Cleanup | Joe 2026-08-01 | One migration re-adds it if a second currency ever exists |
| D5 | `description`, `redirect_url` stored, never read | `PaymentService.cs:113-126` | Dropped; derived at send time | Cleanup | Joe 2026-08-01 | — |
| D6 | `checkout_url` nullable, never read back | `Payment.cs:11` | `NOT NULL`, read by the idempotent repeated `start` | Behavior addition | Joe 2026-08-01 | — |
| D7 | `mollie_payment_id` unbounded text | `PaymentConfiguration.cs` | `varchar(64)` | Narrowing | Joe 2026-08-01 | — |
| D8 | Link was `orders.payment_id` | `CheckoutService.cs:155` | `payments.order_id NOT NULL`, FK RESTRICT | Structure | Order D5 (2026-07-31) | — |
| D9 | No idempotency; no defined retry story | code inspection; council proposed auto-cancel + one-payment-per-order | Partial unique index; retry = second payment, same order; terminal status never cancels the order | Product decision | Joe 2026-08-01 | Wave-3 retry flow; admin anomaly page for stuck orders |
| D10 | Checkout cancelled the order on create failure | `CheckoutService.cs:146-153` | Compensation moves into `start` (`orders.cancel`, `NonCancellable`) | Structure, behavior preserved | Council | `liveOrderOfCart` bounded retry in order |
| D11 | No amount verification on PAID | absent in legacy | Mollie's paid amount vs `amount_cents`; mismatch → no confirmation, ERROR | Hardening (new) | Joe 2026-08-01 | — |
| D12 | Provider failure in status read → `502` | exception mapping | WARN + stored status | Behavior change | Council consensus | — |
| D13 | "Payment maps its five results" | `order-post-migration.md:94-98` | Order maps 5→4 (`PromotionRefused → APPLIED`) before export | Boundary decision | Council (Codex conceded) | Rewrite the order-post bullet |
| D14 | Paid webhook for cancelled order silently did nothing | `PaidOrderProcessor.cs` | ERROR + `200`; payment stays `PAID`; manual refund | Ops decision | Joe 2026-08-01 | Admin anomaly page (deferred) |
| D15 | `paymentStatus` in order responses via `orders.payment_id` joins | `CheckoutService.cs:164-231`; Order D5 | `OrderView.paymentStatus` via `OrderPaymentStatusSource` | Contract addition (returning field) | Planned by Order D5 | Frontend adaptation |
| D16 | — (dev affordance) | council proposed dummy mode | Not built; test key + ngrok | Rejected proposal | Joe 2026-08-01 | Document the dev setup |
| D17 | No provider idempotency key | absent in legacy | Per-attempt `Idempotency-Key` on create | Hardening | Council | Verified against docs.mollie.com/reference/api-idempotency (orchestrator, 2026-08-01): header `Idempotency-Key`, POST only, keys cached 1 h, replay answered with `Idempotent-Replayed: true`, same key + different body → 400, concurrent same key → 409, UUID4 recommended. A fresh UUID per create attempt is compatible with all of it |
| D18 | Phone parsed untrimmed, `+` check on trimmed value | `PaymentService.cs:268-280` | Trimmed value used consistently | Incidental | Council | — |
| D19 | Redirect URL by string concatenation | `PaymentService.cs:35-39` | `URLBuilder` | Incidental | Council | — |
| D20 | Plan: `NonCancellable` only around the loser-cancel (start step 3) | plan §start flow | Everything from a successful `create` onward (insert, winner-read, loser-cancel) runs under one `withContext(NonCancellable)` — with the narrower scope the cleanup is unreachable, because the IO dispatch aborts first. The phase is bounded by construction: at most two insert transactions plus two reads, each bounded by the Hikari connection timeout and the JDBC driver, and at most one provider call bounded by `HttpTimeout` (5 s connect, 10 s request/socket), which still fires inside `NonCancellable` because the plugin cancels the *request's own* job rather than the caller's | Superset of the plan, T2 | Orchestrator acceptance 2026-08-01 | Codex's recorded dissent (phase 3): transaction-local `lock_timeout`/`statement_timeout` would bound the database side explicitly. Noted as possible app-wide hardening, not payment-specific — recorded in [`all-post-migration.md`](all-post-migration.md), owner Joe |
| D21 | Plan does not name the case | plan §start flow step 3 | **Decision reversed in phase 3.** A conflict re-read that finds no live winner means the order's live slot is free again, so the insert is retried once with the *same* created payment instead of cancelling it: the payment is valid and nobody is racing for the slot any more. Only a *second* conflict whose winner is gone again — a cancellation committing inside each of two windows — ends in a best-effort cancel, a WARN and "no payment started"; the order stays `PENDING` (D9), because only the create-refusal compensation cancels an order. Mirrors `OrderRepository.place`'s bounded retry | Gap closed and then reshaped, T2 → phase 3 | Council consensus 2026-08-01 (three-model review) | Coverage is invariant-style only: `PaymentIdempotencyIntegrationTest` races `start` against the death of the live payment and asserts the answer is never a payment cancelled at Mollie and never cancels the order. No deterministic seam exists and none was built — the same accepted class as the T1 note on `liveOrderOfCart` |
| D22 | Plan: "blank secret fails construction" | plan §MollieSettings | `MollieSettings` requires ≥ 16 characters for the webhook secret; a guessable secret is the same hole as none | Hardening, T2 | Orchestrator acceptance 2026-08-01 | — |
| D23 | Record table: "wrong/missing secret → 403" | plan §webhook contract | Wrong secret → 403; a request *without* the secret segment does not match the route and is Ktor's 404. Nothing is read or processed either way | Contract nuance, T2 | Orchestrator acceptance 2026-08-01 | — |
| D24 | `apiUrl` constructor-only, no config key | plan §MollieSettings | Unchanged for deployments. The composition test reaches the stub through an `internal` `module(mollie: MollieSettings)` overload of the composition root (plus an optional `mollie` override on `ApplicationSettings.from`) — a test seam, not a configuration surface | Test seam, T3 | Orchestrator acceptance 2026-08-01 | — |
| D25 | Legacy validated the Mollie options with `[Required]` only | legacy options class | `MollieSettings` enforces the whole deployment shape at construction: absolute `http(s)` API and redirect URLs, an **HTTPS-only** webhook URL, a webhook secret of at least 16 characters, and the webhook URL's last path segment **is** that secret — a mismatch would start a shop whose every real callback is answered `403` in silence. `toString` renders the webhook URL without its path, because the secret is that path's end and the line's `credentials=[REDACTED]` was otherwise false for every real deployment | Hardening, phase 3 | Council consensus 2026-08-01 | Verification `MollieSettingsTest` (mismatch refused, percent-encoded secret accepted, `toString` leaks neither credential) |
| D26 | Provider answers were decoded leniently | T2 implementation | Phase-3 hardening of everything Mollie says: `id`, `status` and `amount` are **required** response fields (a truncated answer is a decoding failure → `null` → webhook `502` → redelivery, instead of a payment with an empty id and an amount of zero cents); an amount is only usable in `EUR` (D4); `find` refuses an answer whose id is not the id it asked about; a **blank** checkout URL counts as missing. Every one of those log lines names this adapter's own context — the order being paid for, the id being read — never anything the provider wrote | Hardening, phase 3 | Council consensus 2026-08-01 | Verification `MolliePaymentClientTest` |
| D27 | T1 note: the residual `error(…)` in `OrderRepository.place` would mean index and read disagree | `order-migration.md` T1 note | It is plainly reachable: two consecutive conflict windows, each hit by a cancellation, end there. Documented as an accepted, vanishingly rare `500` for a customer who can simply place the order again — not a bug detector, and deliberately not looped over | Correction of a claim, phase 3 | Council consensus 2026-08-01 | `OrderConcurrencyIntegrationTest` asserts a *single* racing cancellation never reaches it |

## Test plan

PostgreSQL through Testcontainers wherever PostgreSQL behavior matters.

- **Schema** (`PaymentSchemaIntegrationTest`): Flyway on an empty database;
  each rejected write can violate only the rule under test: the status CHECK
  (all seven accepted, order-spelling `CANCELLED` rejected), `amount_cents >
  0`, the `mollie_payment_id` UNIQUE, the order FK, `ON DELETE RESTRICT`, and
  `ux_payments_live_order` (second live payment rejected; a `FAILED` row plus
  a new live row accepted).
- **Idempotency/concurrency** (`PaymentIdempotencyIntegrationTest`): two
  concurrent `start` calls → one row, same `checkout_url` for both, exactly
  one surviving provider payment, loser cancelled; sequential repeat → zero
  provider calls; `start` after `FAILED` → second row accepted; dead payment
  resurrecting next to a live one → `SUPERSEDED`, ERROR, confirm still fires;
  loser-cancel on a cancelled coroutine with a fake that suspends where the
  real client suspends.
- **Webhook** (`PaymentRoutesTest` / integration): secret wrong → `403` and
  no provider call; forged body status ignored (provider `GET` asserted);
  missing id → `400`; unknown id → `200` + WARN; unknown status value →
  `502`, nothing written, raw value in no log line; repeated `PAID` →
  confirmation fires, `updated_at` untouched; `PAID` for `CANCELLED` → `200`,
  order stays `CANCELLED`, ERROR asserted via logback; amount mismatch → no
  confirmation, ERROR; terminal statuses leave the order `PENDING` (D9); CSRF:
  succeeds without token while a protected route still rejects.
- **Status reads** (`PaymentStatusIntegrationTest`): batch read, zero provider
  calls; refresh matrix over the seven statuses; refresh observing `PAID`
  confirms the order and stores `PAID` (the row-level proof — production
  request row, email job row — lives in `PaymentCompositionIntegrationTest`,
  because only the composed application runs the real order module); provider
  failure → stored status + WARN.
- **Order module** (`OrderCancellationIntegrationTest` + route tests):
  `cancel` transition matrix; concurrent `confirm`/`cancel` serialize on the
  row lock with side effects matching the final status; concurrent `place`
  against a cancellation → bounded retry, no `IllegalStateException`;
  `paymentStatus` present in both order routes, `null` without payment.
- **Adapter** (`MolliePaymentClientTest`): amount formatting under a
  comma-decimal locale; the four legacy phone cases + invalid + no-country, and
  a billing/shipping pair in *different* countries pinning that each address is
  its own region hint; full address/metadata JSON body; redirect URL with
  existing query; missing or blank `_links.checkout.href` → failure;
  Idempotency-Key header present; the configured timeouts read back off a
  request; 4xx/5xx/malformed JSON → `null` without logging provider bodies; and
  the D26 answer hardening (truncated answer, non-EUR amount, foreign payment
  id).
- **Settings** (`MollieSettingsTest`): blank api key / non-absolute URLs /
  blank or short secret / a webhook URL that does not end in the secret all fail
  construction (D25); `toString` renders neither credential and drops the
  webhook URL's path.
- **Composition** (`PaymentCompositionIntegrationTest` in `backend/app`): the
  composed application against a local Mollie stub answers a webhook and then
  serves `GET /api/orders/{id}` with `"paymentStatus": "PAID"` — proving both
  bindings and the late-bound source.

## Ticket cut (Phase 2, serial)

1. **T1 — Order exports the payment write path**: `OrderPaymentGateway`,
   `OrderPaymentOutcome`, the `markCancelled` write, the shared row lock, the
   `liveOrderOfCart` bounded retry, `OrderModule.payments` export. No payment
   module yet; no `OrderView` change yet.
2. **T2 — Payment module**: Flyway V17, table, repository, `MolliePayments`
   port + Ktor adapter, `MollieSettings` (incl. webhook secret), `start`
   flow, webhook confirm flow, routes, install seam, app wiring of the module
   (without the status source), module docs skeleton.
3. **T3 — Status read path**: `OrderPaymentStatus`, `OrderPaymentStatusSource`
   declared in order, implemented in payment, `LateBoundPaymentStatus` in the
   app, `OrderView.paymentStatus`, batch/refresh semantics, composition test.
4. **T4 — Docs and closeout**: `payment-package.md`, `module-architecture.md`,
   roadmap move + wave recompute, `order-post-migration.md` rewritten in
   delivered-state language, `payment-post-migration.md` (admin anomaly page,
   reconciliation, Wave-3 retry notes, frontend adaptation, dev setup with
   test key + ngrok), package-guide updates of every module this migration
   binds.

## Migration retrospective

Completed 2026-08-01 after the phase-3 council verification (three independent
reviews, one rebuttal round, fix commit) and the post-migration simplification
review. The simplification review itself found nothing: no speculative types
(the deletion test was run per type by the reviews), no transaction wrapper
without a named policy, no constraint-name inspection, no TODOs, no copied
infrastructure — the phase-2 discipline held.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| A redaction test can be rigged by fixture choice: `toString` leaked the webhook secret for every real deployment while its test passed, because the fixture kept the secret and the secret-carrying URL distinct | `MollieSettingsTest` pre-fix; D25; only one of three reviewers caught it | Every settings/credential `toString` test | Redaction fixtures must embed the credential in every rendered component | Guide, tests section — bullet added 2026-08-01 |
| Serialization defaults on required provider-response fields turn truncated answers into plausible zero values (a truncated `PAID` became a permanent 0-cent amount mismatch) | `MolliePaymentResponse` pre-fix; D26 | Every provider adapter | Required response fields carry no defaults; truncated answers must fail decoding | Guide, tests section — bullet added 2026-08-01 |
| A deviation row changed an operation's outcome while the operation contract table and the consumer-facing post-migration notes kept promising the old behavior (`start`'s `null` = "order cancelled") | D21 pre-reversal vs. operation contract and `payment-post-migration.md` | Every migration recording acceptance deviations | Deviation edits re-check the contract table and derived consumer instructions in the same edit | Guide, deviation-log section — rule added 2026-08-01 |
| A fake's KDoc claimed the suspension behavior the guide requires while the code did not have it, so the D10 compensation test could not fail for a missing `NonCancellable` | `FakeOrders`/`FakeMolliePayments` pre-fix | Every migration's fakes | Simplification review verifies each fake's dispatch against its code, not its KDoc | Guide, step-4 checklist — bullet added 2026-08-01 |
| A deferral owned by a *future* migration went silently ownerless when that migration landed without the expected consumer | `generator-migration.md`'s `UpstreamFailure` row, owner "Payment migration" | Every cross-record deferral | Closeout sweeps `docs/migration` for deferrals naming the finishing migration as owner or trigger | Guide, step-4 checklist — bullet added 2026-08-01; the deferral itself retired (decision in `generator-migration.md`) |
| An accepted residual failure path was documented as impossible (`OrderRepository.place`'s `error(…)` "would mean the index and this read disagree") and the claim survived T1 acceptance | D27; the triple race is a legal interleaving of this migration's own writers | Honesty of accepted-risk documentation | An accepted rare failure gets a deviation-log row; "impossible" claims about concurrency need an argument, not an assertion | Covered by D27 and the corrected KDoc; no guide change (single occurrence) |
| Process evidence, positive: all five orchestrator-solo acceptance decisions (D20–D24) survived adversarial review by two independent models, while the same phase produced two S2 findings the per-ticket acceptance had missed (stale repair `status`, `toString` leak) | This record's phase-3 outcome | Council workflow calibration | — | No action: the split "orchestrator accepts per ticket, council verifies per phase" is doing what it is for |
