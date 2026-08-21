# ADR 0002 — Production fulfillment channels: SFTP push and SPOD API

- Status: accepted
- Date: 2026-08-21
- Deciders: three-model council (Claude orchestrator, Opus, Codex); contested
  points decided by Joe. Driving issue: #205.

## Context

The production module was built around exactly one way of reaching a
producer: render an immutable PDF per job and push it to a supplier's SFTP
destination. Every mechanism assumed that shape — the artifact generation
stage is the exactly-once anchor for the `production_job_items` snapshot,
the delivery adapter seam takes PDF bytes, the ship guard is
`generated_at IS NOT NULL`, and "Shipped" is reported by a human.

T-shirts (issue #205) are produced by Spreadconnect (SPOD), an
API-fulfilled supplier: a job becomes design uploads plus an order created,
confirmed, and shipped through their REST API, with shipping reported back
by webhook. The following was verified against the official SPOD API docs
(https://api.spreadconnect.app/docs/):

- Orders are created in state `NEW` and produce nothing until
  `POST /orders/{id}/confirm`; the confirmed state can also be set at
  creation, which we deliberately never use.
- There is **no idempotency mechanism** for `POST /orders`, no order list
  endpoint, and no lookup by `externalOrderReference` — an order can only be
  fetched by the SPOD order id returned at creation.
- A non-blank **phone and email are required** on every order.
- Webhooks demand an ack of **202 with body `[accepted]`** within 8 seconds;
  delivery is at-least-once. `Shipment.sent` carries carrier, tracking code,
  tracking URL, and our `externalOrderReference`.
- Shipping can be preset with `shipping.preferredType: "STANDARD"`.
- Placement uses `view` + `hotspot` (e.g. `FRONT` / `MEDIUM_FRONT`); an
  endpoint lists the available hotspots per product type and design.

## Decisions

### 1. The channel is a property of the job, decided at split time

`production_jobs` gains a snapshotted `fulfillment_channel`
(`SFTP` | `SPOD`), derived from the supplier's enabled destination when the
split worker creates the job. Each channel owns its own lifecycle from
there. Routing stays live master data (destinations), the job's channel is
frozen the moment the job exists.

### 2. A PDF is not a universal job artifact *(Joe, D1 — against the 2:1 council majority)*

- **SFTP jobs** keep the current lifecycle unchanged: generate the immutable
  PDF exactly once, create `production_deliveries` rows, push the bytes,
  human ship report.
- **SPOD jobs** create **no PDF and no `production_deliveries` rows**. Their
  remote lifecycle lives in `production_spod_orders` (keyed by the job):
  uploaded designs (`production_spod_designs`), order creation state,
  the SPOD order id, confirmation, and the remote state reported by
  webhooks.

Consequences accepted with this decision (the majority's dissent, recorded):

- `production_job_items` moves into the **split transaction** for both
  channels. The snapshot then describes the job as split, no longer provably
  the PDF bytes: a catalog rename between split and generation can make the
  supplier page and the PDF disagree on an SFTP job. Accepted — the window
  is minutes and the PDF is still rendered from the same immutable order
  snapshot fields.
- The ship guard `generated_at IS NOT NULL` is replaced by a channel-neutral
  `prepared_at` on `production_jobs`: SFTP sets it when the artifact is
  generated, SPOD when the order is confirmed. The guarded ship UPDATE and
  the fulfillment surfaces become artifact-optional and channel-aware.

### 3. Destinations get per-channel detail tables

`production_destinations` keeps identity, supplier, channel, label,
enabled, and the notification fields; the SFTP-shaped columns move to
`production_destination_sftp`, and SPOD configuration lives in
`production_destination_spod` — every column `NOT NULL` in its own table,
composite FKs on `(id, channel)`, the same one-table-per-shape pattern the
article schema uses. The SPOD row carries `environment`
(`STAGING` | `PRODUCTION`) and a write-only access token; the base URL is
derived from the environment enum in code, so no admin input can point
fulfillment at an arbitrary host. At most one **enabled** SPOD destination
per supplier (partial unique index).

### 4. Order submission protocol and idempotency

Upload designs → `POST /orders` always in state `NEW` with
`shipping.preferredType: "STANDARD"`, customer phone + email, and the
deterministic `externalOrderReference = ORD-{orderId}-JOB-{jobId}` →
**persist the returned SPOD order id before anything else** → `confirm` →
set `prepared_at`. Placement is `view: FRONT`, hotspot `MEDIUM_FRONT`,
falling back to the available-hotspots endpoint; no placement available is
the bounded error `PLACEMENT_UNAVAILABLE`.

Because SPOD offers no idempotency: an ambiguous creation outcome (timeout,
reset, 5xx after send) permits **one** automatic re-create — an orphaned
`NEW` order is inert (produces nothing, charges nothing) — and a second
ambiguity quarantines the job as `OUTCOME_UNKNOWN` with an ops alert.
Confirmation retries first read `GET /orders/{id}` and confirm only while
the state is `NEW`. The 60 req/min limit is enforced by a client-side pacer
plus the retryable code `RATE_LIMITED` on 429; adapters never retry —
retries belong to the database-backed worker, as everywhere in production.

### 5. Inbound webhook

`POST /api/production/webhooks/spod/{secret}` follows the Mollie pattern:
no session auth, app-config secret (`production.spod.webhookSecret`,
≥ 32 chars, redacted), constant-time comparison before the body is read.
The handler is synchronous — two lookups and one guarded transaction —
and answers **202 with body `[accepted]`**; every no-op outcome (unknown
reference, already shipped, unknown event) answers the same way so SPOD
stops redelivering. `Shipment.sent` reuses the existing guarded ship
transaction: `shipped_by_user_id` stays `NULL`, a new
`shipped_by_channel` column records the reporter, the carrier name maps
case-insensitively onto the bounded `ShippingCarrier` enum (else `OTHER`)
with the raw name stored admin-visible, and **SPOD's tracking URL is
discarded** — the shop keeps building tracking links from its own bounded
list (decision J2 of issue #119). `Order.cancelled` / `Order.needs-action`
and `OUTCOME_UNKNOWN` set the remote state and enqueue **one** ops alert
mail per job through the `EmailOutbox` to a configured alert address.
A human ship on a SPOD job stays allowed as the admin's lost-webhook
fallback.

### 6. Customer contact data *(Joe, D2)*

SPOD orders carry the **customer's** phone and email. Checkout therefore
requires a non-blank phone whenever the cart contains a t-shirt line;
`orders.phone` stays nullable for mug-only orders. `ProductionData` (the
production view, not the supplier view — `FulfillmentOrder` and its
data-minimization pin are untouched) gains the customer phone and email.

### 7. No checkout country gate *(Joe, D3)*

A delivery country SPOD does not support fails the submission with a
bounded retryable error and an ops alert; the operator refunds manually.
No `checkout → production` coupling is added for it.

### 8. What reaches production stays live

The SPOD ids (`productTypeId`, `appearanceId`, `sizeId` on the t-shirt
variant) are resolved at submission time through `CatalogVariant`, like
supplier routing today — a mapping fix heals every pending order. Safety
net: the adapter compares the current composed variant name against the
order line's snapshotted `variant_name` and refuses on mismatch with a
bounded retryable code, so a paid "Black / M" can never silently become a
different garment.

## Consequences

- New tables: `production_destination_sftp`, `production_destination_spod`,
  `production_spod_orders`, `production_spod_designs`; changed:
  `production_destinations`, `production_jobs` (`fulfillment_channel`,
  `prepared_at`, `shipped_by_channel`), `production_job_items` (written at
  split).
- The SFTP path must stay behaviorally unchanged apart from the snapshot
  timing and the guard rename; its regression suite is the acceptance bar.
- Glossary entries *Production delivery* and *Shipped* no longer assume
  SFTP transport and human reporting and are updated with this feature.
