# SPOD fulfillment: the print-on-demand channel

This page explains how a t-shirt job is produced. It belongs to the
[Production package guide](production-package.md), which describes everything
the two fulfillment channels share; here we look only at the second one, the
print-on-demand partner **Spreadconnect (SPOD)**.

The architecture decisions behind it are recorded in
[ADR 0002](../../adr/0002-production-fulfillment-channels.md). This page is the
implementation guide: what the code does, in which order, and why the order is
the interesting part.

## Two channels, one worker

A mug reaches its producer as a PDF pushed over SFTP. A t-shirt reaches its
producer as a conversation with a REST API. The split worker decides which of
the two a job belongs to when it creates the job, from the supplier's enabled
destinations, and freezes that decision in `production_jobs.fulfillment_channel`.

From there the two lifecycles have nothing in common except their last column:

| | SFTP job | SPOD job |
| --- | --- | --- |
| Artifact | an immutable PDF, generated once | none at all |
| Delivery rows | one per enabled destination | none at all |
| Remote state | none | `production_spod_orders` + `production_spod_designs` |
| Ready to ship | `prepared_at` set when the PDF exists | `prepared_at` set when the remote order is confirmed |
| Shipment reported by | a human on the fulfillment page | a webhook (and a human as fallback) |

`prepared_at` is the shared column, and it is what the guarded ship update
reads. Everything else on the SPOD side is new.

## The one fact that shapes the whole design

The partner's API has **no idempotency mechanism**. Concretely:

- `POST /orders` accepts no idempotency key. Sending it twice creates two
  orders.
- There is no endpoint that lists orders.
- There is no endpoint that finds an order by *our* `externalOrderReference`.
- The only way to read an order back is `GET /orders/{id}` with the id the
  creation answered with.

Put those together and one sentence follows: **if the order id is lost, the
order is lost.** Nothing can find it again — not a retry, not a support ticket
of ours, only a human reading the partner's backoffice.

Two properties of the API make that survivable:

- An order can be created in state `NEW`, and a `NEW` order **produces nothing
  and charges nothing** until `POST /orders/{id}/confirm`. An orphaned `NEW`
  order is litter, not damage.
- `GET /orders/{id}` reports the state, so a repeated attempt can tell "still
  `NEW`" from "already confirmed".

Everything below is built out of exactly those two properties.

## The submission stage, step by step

`delivery/spod/SpodOrderSubmitter.kt` is the fourth stage of the production
worker. Each scan takes the SPOD jobs whose `prepared_at` is still `NULL`, in
ascending id order, and makes **one** attempt per job. Nothing retries inside
the attempt; a failure records a bounded code and the job waits for the next
scan, exactly like every other production stage.

Two of those jobs are left out of the scan on purpose: one quarantined as
`create_state = 'OUTCOME_UNKNOWN'` (no automatic call may be made until a human
has looked) and one whose `remote_state` is `'CANCELLED'` (the order is finished
remotely and will never leave that state, so re-reading it would record
`ORDER_STATE_UNEXPECTED` once a minute forever). `NEEDS_ACTION` stays in the
scan: somebody resolves it in the backoffice, and the next scan simply
continues.

### One worker, and why that matters more here

The whole protocol assumes a single application instance with a single
production worker, like every other stage. On the SFTP side a second worker
would at worst render a PDF twice. Here it would create a **second paid order**:
the partner offers no idempotency key, the guarded writes of this shop protect
its own rows but not a call already in flight, and the id of the second order
would be the one nobody stores. Do not run two instances against one database
without giving this stage a lock first.

### 1. Resolve the order and check the lines

The order comes through the shared `ProductionSource` with the shared
`resolveOrder` codes (`SOURCE_NOT_FOUND`, `SOURCE_INVALID`,
`SOURCE_UNAVAILABLE`). Then each line of the job is checked twice:

- The live lines of this supplier must be **exactly** the snapshotted set: same
  count, every snapshotted position present, and never empty. Different →
  `ITEM_SET_CHANGED`.
- It must carry `spodProduct`, the partner's three ids (product type,
  appearance, size). Missing → `ITEM_WITHOUT_SPOD_PRODUCT`.
- Its **current** composed variant name must equal the name the split
  snapshotted in `production_job_items`. Different → `SPOD_MAPPING_CHANGED`.

The first check is the one a per-line check cannot do, because it is about a
line that is *gone*. Reassigning an article to another supplier after the split
shortens this supplier's share of the order, and the lines that disappear are
silently the trailing ones — so without it a fully paid order would be ordered
from the partner as a partial one, or, when the supplier lost every line, as an
order with nothing in it.

The second check is the safety net of ADR 0002, decision 8, and it is worth
understanding. The three partner ids are read from *today's* master data on
every load, deliberately: a mapping an admin fixes today heals every order
still waiting to be submitted. But the same liveness would let a corrected
mapping silently turn a paid "Schwarz / M" into a different garment. The
snapshotted name is the tripwire — production refuses rather than guesses.

### 2. Convert and upload the designs

Print images are stored as WebP; the partner's `POST /designs/upload` takes
PNG of at most 10 MB. `delivery/spod/PrintImagePng.kt` does the conversion
inside production, through `ImageIO` — the same path the PDF renderer uses, so
the registered `webp-imageio` reader claims the RIFF container.

Two budgets apply:

- the longest edge is scaled down to at most **4096 px**;
- the PNG must stay under **9.5 MiB**, and PNG being lossless, the only way to
  make it smaller is to make the image smaller — so it is halved and
  re-encoded, at most **three** times.

The bounded loop is the point: an unbounded one would spin forever on a
pathological image. The three failure codes are `PRINT_IMAGE_MISSING`,
`PRINT_IMAGE_UNREADABLE`, and `PRINT_IMAGE_TOO_LARGE`.

Uploads are grouped **per distinct print image**, and the resulting design id
is written to `production_spod_designs` **per item position**. That row is what
makes a re-scan skip an upload it already did — and what makes two lines that
print the same image share one design.

### 3. Create the order — always `NEW`

The request is built in one place (`SpodJobContext.request`) and carries:

- `state: "NEW"` — never `CONFIRMED`, even though the API would allow it;
- `shipping.preferredType: "STANDARD"` (the customer never chooses);
- the customer's `email` and `phone`, both required by the partner. Checkout
  already requires a phone whenever the cart holds a shirt, so a missing one is
  a defensive `PHONE_MISSING` — checked *before* a design is uploaded;
- `externalOrderReference = "ORD-{orderId}-JOB-{jobId}"`, deterministic so the
  webhook can find the job and a human can read the same string on both
  screens;
- `oneTimeItems`, grouped the partner's way: one entry per (product type,
  design), with a `quantityItems` line per size and colour, and one
  `configurations` entry placing the design at `view: FRONT`,
  `hotspot: MEDIUM_FRONT`.

If the partner *refuses* the creation with a `4xx`, it has stated that it
created nothing — which is the one situation in which a second creation is
safe. The stage then asks
`GET /productTypes/{id}/hotspots/design/{designId}` for the placements this
product type actually offers, takes the first front one, and creates once
more. No front hotspot on offer is `PLACEMENT_UNAVAILABLE`; a second refusal
is `ORDER_CREATE_REJECTED`. Both of them alert an operator (see
[The ops alert](#the-ops-alert)).

That endpoint answers **names only** — `LEFT_CHEST`, `MEDIUM_FRONT`,
`LARGE_BACK` — and no view field, so which view a name belongs to has to be read
off the name itself. Two families count as front: names containing `FRONT`, and
chest names, which sit on the front of the garment without saying so. A `FRONT`
name wins over a `CHEST` one — it is the placement this shop asks for by default
— and the match is case-insensitive, so a lower-case `front` is not the
difference between a printed shirt and a job for a human.

### 4. Persist the id — in its own transaction, before anything else

`SpodOrderRepository.recordCreatedOrder` writes `external_reference` and flips
`create_state` to `CREATED` in a transaction of its own, *before* the confirm
call is even attempted. A crash between creation and confirmation then costs
one confirm call on the next scan instead of an untraceable order.

An **ambiguous** outcome is the interesting one: a timeout, a reset
connection, an unreadable answer, or a `5xx` after the request went out. The
order may exist and may not, and nothing can tell.

- The first ambiguity increments `create_ambiguous_count`, leaves
  `create_state = 'PENDING'`, and the next scan simply creates again. Worst
  case: one inert `NEW` orphan.
- The second sets `create_state = 'OUTCOME_UNKNOWN'`. The job is quarantined —
  the scan stops handing it out — and the same transaction enqueues the ops
  alert that asks a human to read the partner's backoffice. "Reconciling a
  quarantined job" below is that human's checklist.

This is why `SpodResult.Failed` carries `ambiguous` next to the error code: a
stated `4xx` refusal is *known* and costs no ambiguity, everything else is
assumed to have taken effect.

### 5. Confirm — but read the state first

The confirmation reads `GET /orders/{id}` and only confirms while the state is
`NEW`. A state of `CONFIRMED` is not a failure but the second crash-recovery
case: the confirm went through and this backend never learned it, so the job
is closed without confirming twice. Any other state is `ORDER_STATE_UNEXPECTED`
— the webhook owns what happens to an order the partner cancelled or flagged.

Success writes `confirmed_at`, `remote_state = 'CONFIRMED'`, and the job's
`prepared_at` in **one** transaction. That commit is this channel's
counterpart of "the PDF exists": the moment the job becomes shippable.

## The client

`delivery/spod/SpodClient.kt` is hand-written for the same reason
`MolliePaymentClient` is: an SDK logs wherever it pleases, and nothing this
partner writes may ever reach a log line. It follows the same two-constructor
pattern — a no-argument constructor building a CIO client for a deployment,
and one taking an `HttpClientEngine` so a test can drive a `MockEngine`
through the *same* configuration.

Four rules:

- **Nothing secret or foreign is logged.** Not the response body, not a
  decoder message (which quotes the input it failed on), not the access token,
  not the request URL. Only this adapter's own words plus, at most, the HTTP
  status number.
- **It never retries.** Retries belong to the database-backed worker.
- **It paces itself.** The partner allows 60 requests per minute, so the
  client keeps at least **1050 ms** between any two requests it makes,
  measured on a monotonic clock (`System.nanoTime`) behind a mutex. The wait
  is deliberately counted for the *whole client*, not per destination: there is
  one `SpodClient` per application, so two suppliers on the partner's API
  cannot together exceed the budget. A `429` that happens anyway is the
  retryable code `RATE_LIMITED`.
- **Where and how to authenticate travels with the call.** Each supplier has
  its own destination row — environment, token, timeout — so every call takes
  a `ProductionDeliveryDestination.Spod` and reads all three off it. The base
  URL comes from the `SpodEnvironment` enum, never from a column: no admin
  input can point fulfillment at an arbitrary host.

The pacer's clock and its sleeping function are constructor parameters, which
is how `SpodClientTest` observes the exact waiting arithmetic without a test
that takes seconds.

Its `Json` is lenient, exactly like the webhook's and for the same reason: the
partner answers ids as numbers in some fields and as strings in others, so
`{"id": 12345}` has to read into the same `String` a quoted id does. Without
that, a creation this shop really performed would fail to decode, count as
ambiguous, and quarantine the job after a second orphan — on every order.

## The tables

`V24__production_spod_orders.sql` adds two.

`production_spod_orders`, one row per job:

| Column | Meaning |
| --- | --- |
| `production_job_id` | primary key, `RESTRICT` against `production_jobs` |
| `external_reference` | the partner's order id; `NULL` until created, unique once set |
| `create_state` | `PENDING` \| `CREATED` \| `OUTCOME_UNKNOWN` |
| `create_ambiguous_count` | how many creations left the outcome undecided |
| `attempt_count`, `last_error_code` | the usual retry bookkeeping, bounded codes only |
| `confirmed_at`, `remote_state` | `CONFIRMED` here; the webhook writes `NEEDS_ACTION` / `CANCELLED` |

Two check constraints keep the state honest: a `CREATED` row must have an
`external_reference`, and so must a confirmed one.

`production_spod_designs`, one row per item position: `(production_job_id,
position)` and the `design_id`, cascading with the order row above.

## The webhook: how a shipment comes back

The partner produces and ships; nobody in this shop presses a button for a
t-shirt. `POST /api/production/webhooks/spod/{secret}` is where that comes
back, and it follows the Mollie webhook of the payment module exactly
(ADR 0002, decision 5):

- **No auth subtree at all.** The partner has no session and sends no CSRF
  token, so the secret in the path takes their place. It is compared with
  `MessageDigest.isEqual` — constant time — **before the body is read**, so a
  wrong secret costs a string comparison and nothing else. A route test counts
  the bodies the application reads and pins that the count stays zero.
- **The answer is always `202` with the body `[accepted]`.** The partner
  demands exactly that within eight seconds and redelivers anything else, so
  every no-op answers it too: an unknown reference, a job that shipped hours
  ago, an event type this shop does not act on, a body that is not JSON. Only a
  database failure becomes a `500`, because that is the one thing a redelivery
  fixes.
- **The handler is synchronous**: two lookups and one guarded transaction. No
  call back to the partner, nothing handed to a background scan.

Three event types are acted on:

| Event | What happens |
| --- | --- |
| `Shipment.sent` | the job goes through the **same guarded ship transaction** a human ship uses — `shipped_by_channel = 'SPOD'`, `shipped_by_user_id` NULL, one customer mail |
| `Order.cancelled` | `remote_state = 'CANCELLED'` and one ops alert mail |
| `Order.needs-action` | `remote_state = 'NEEDS_ACTION'` and one ops alert mail |

There is exactly one difference between that ship and a human's, and it is the
`prepared_at` guard. A human ship keeps it: pressing the button on a job whose
document does not exist yet is a mistake, and the answer is `NOT_READY`. A
channel ship drops it, because the shipment *is* the proof that the remote order
exists and was confirmed — the partner does not ship what it never produced —
and because the `202` means nothing will ever redeliver the event. Refusing it
would lose the shipment for good and leave the customer untold. The one case is
real: a job quarantined as `OUTCOME_UNKNOWN` whose order an operator adopted in
the backoffice by hand. So the same statement sets
`prepared_at = COALESCE(prepared_at, now())`, which is both what the
shipping-consistency CHECK requires and the truth about the job.

The job is found by the partner's own order id
(`production_spod_orders.external_reference`) and, when that is not stored —
the case an ambiguous creation leaves behind — by this shop's deterministic
`ORD-{orderId}-JOB-{jobId}` reference, whose two ids are then checked against
the job they claim.

The carrier name the partner sends is mapped case- and separator-insensitively
onto the bounded `ShippingCarrier` list (`Deutsche Post`, `deutsche_post`, and
`DEUTSCHE POST` are the same carrier); anything else becomes `OTHER`. The raw
name is stored in `production_jobs.shipping_carrier_reported` for
administrators. The partner's **tracking URL is discarded** — it is not even a
field of the payload type — because the shop builds every link in every mail
from its own carrier list (decision J2 of issue #119).

At-least-once delivery is handled by not needing to handle it: the ship
transaction is guarded on "not shipped yet", the remote-state write is
idempotent, and both mails go through the outbox, whose unique
`(email_kind, source_id)` rule makes "one mail per job" true no matter how
often an event arrives. A cancellation followed by a needs-action event
therefore still produces exactly one alert.

## The ops alert

Four situations end on a human's desk, and all of them enqueue the same mail
kind, `SPOD_OPS_ALERT`, keyed by the production job:

- the partner cancelled the order,
- the partner flagged it as needing action,
- the submission stage quarantined the job as `OUTCOME_UNKNOWN`,
- the submission recorded a failure that no further scan can heal.

The last one is the set below — the codes that wait for a *person* rather than
for the partner, the network, or the next rate-limit window:

`ORDER_CREATE_REJECTED`, `PLACEMENT_UNAVAILABLE`, `PHONE_MISSING`,
`ITEM_WITHOUT_SPOD_PRODUCT`, `SPOD_MAPPING_CHANGED`, `ITEM_SET_CHANGED`.

The alert is enqueued in the same transaction that records the code, so a job
nobody can produce is never a job nobody was told about. Every other code stays
silent: it describes something that comes back on its own, and a mail per scan
would be noise.

The mail goes to `production.spod.alertEmail` and contains the job number, the
order number, the partner's order id, and one sentence per reason — no customer
data, because an alert lands in a shared mailbox. The reason travels as an enum,
so nothing the partner wrote can reach a mail. What to *do* with such a mail is
[Handling an alert](#handling-an-alert) in the runbook below.

## The ops runbook

Everything below is operations work: it happens in the admin UI, in the
application configuration, or in the partner's backoffice — never in code.

### The two credentials, and why they live apart

The channel needs two secrets, and they deliberately live in different places:

| Credential | Where it lives | Who sets it |
| --- | --- | --- |
| The partner's **access token** | `production_destination_spod.access_token`, one row per supplier destination | an admin, in the admin UI |
| The **webhook secret** | `production.spod.webhookSecret` in the application configuration | whoever deploys |

The token is per supplier and changes as often as a supplier does, so it is
master data an admin can edit. The secret is one per deployment, is the last
path segment of the callback URL, and must survive a database restore
independently of it — so it is configuration.

The token is **write-only** everywhere: it is stored as entered (never
trimmed, because a token may legitimately look odd), no read endpoint ever
returns it, `SpodDestinationInput.toString()` prints `accessToken=[redacted]`,
and leaving the field empty on an update keeps the stored one. A screen that
never shows a secret cannot leak it into a screenshot or a log.

### Configuration

```yaml
production:
  spod:
    webhookSecret: ""   # PRODUCTION_SPOD_WEBHOOK_SECRET, at least 32 characters
    alertEmail: ""      # PRODUCTION_SPOD_ALERT_EMAIL
```

Both values are blank in a deployment without a print-on-demand supplier. A
deployment that has one must set **both**, and the application enforces exactly
that at startup:

- one of the two set and the other blank → the startup fails
  (`SPOD webhook secret must be at least 32 characters`, or
  `SPOD alert e-mail address is required`);
- both blank while a `production_destinations` row with `channel = 'SPOD'`
  exists → the startup fails with
  `A print-on-demand destination exists, so production.spod.webhookSecret and
  production.spod.alertEmail are required`.

A channel whose shipments arrive by webhook cannot report a single one without
the secret that authorizes the callback, and a channel that quarantines jobs
cannot tell anybody without an address — so neither is optional. The opposite
case is allowed: a configured block without any SPOD destination simply does
nothing.

The check runs while the production module is installed, **before** the
background worker starts — otherwise a worker could submit a paid order during
the seconds of a startup that was about to fail. The same rule is closed from
the write side: `POST`/`PUT /api/admin/production/destinations` refuses a SPOD
destination with a `channel` field error while the block is missing (*This
deployment has no production.spod configuration …*), enabled or not, so nobody
can store the row that would break the next restart.

`ProductionSpodSettings.toString()` prints `webhookSecret=[REDACTED]`, so a
settings dump at startup cannot spill it.

### Setting a supplier up, staging first

The partner runs two installations, and this shop picks between them with the
`environment` column alone — the base URL is derived from the enum in code, so
no admin input can point fulfillment at a host of its own:

| `environment` | Base URL |
| --- | --- |
| `STAGING` | `https://rest.spreadconnect-staging.app` |
| `PRODUCTION` | `https://rest.spreadconnect.app` |

The two installations have **separate tokens and separate data**; a token of the
one installation is refused by the other, which reaches this shop as the bounded
`REFUSED`. Setting a supplier up therefore has a fixed order:

1. Create the supplier and its SPOD destination with
   `POST /api/admin/production/destinations` (admin UI:
   `/admin/logistics/destinations`): `channel: "SPOD"`, a label, `enabled`, the
   notification
   address, and the `spod` block — `environment`, `accessToken`,
   `timeoutSeconds` (1…3600; 30 is a sane start).
2. Enter the partner's product ids on every t-shirt variant (product type,
   appearance, size). Without them, submission stops at
   `ITEM_WITHOUT_SPOD_PRODUCT` before a single call goes out.
3. Register the webhook subscriptions (next section) against the **same**
   installation.
4. Order one shirt end to end and watch the job on the *Logistics* page: it must
   reach `prepared_at`, then arrive as a shipment through the webhook.

Only one **enabled** SPOD destination per supplier is allowed (a partial unique
index enforces it), so switching a supplier from staging to production means
disabling the staging row first — or editing it, which is usually what you
want, because the job history stays attached to the destination it used.

### Registering the webhook subscriptions

Subscribing is an operations step, not code: this shop never calls the
subscription API. Register one subscription per event type, each pointing at
the secret-bearing URL, against the base URL of the installation you are
setting up:

```sh
SPOD_BASE=https://rest.spreadconnect-staging.app   # or https://rest.spreadconnect.app
for event in Shipment.sent Order.cancelled Order.needs-action; do
  curl -X POST "$SPOD_BASE/subscriptions" \
    -H "X-SPOD-ACCESS-TOKEN: $SPOD_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"eventType\":\"$event\",\"url\":\"https://<shop-host>/api/production/webhooks/spod/$PRODUCTION_SPOD_WEBHOOK_SECRET\"}"
done
```

Two checks that are worth the minute they cost:

- `curl -i https://<shop-host>/api/production/webhooks/spod/wrong-secret` must
  answer `403`, and the same URL with the real secret must answer `202` with
  the body `[accepted]` for any body at all.
- The URL must be reachable from the public internet. A shop behind a VPN gets
  no shipments and no error — the partner retries into the void, and the first
  symptom is a customer asking where the parcel is.

Rotating the secret means deploying the new value and re-registering the
subscriptions with the new URL. The old URL answers `403` from the moment the
new configuration is live, so do it in that order and expect the partner to
redeliver whatever fell into the gap once the new subscription exists.

### Reading a job's state

The admin page *Logistics* (`/admin/logistics`,
`GET /api/admin/production/jobs?status=OPEN|SHIPPED`) shows
per job the `fulfillmentChannel`, the partner's order id (`externalReference`),
the `remoteState`, and who reported the shipment (`shippedByChannel` = `SPOD`,
or a user id for a human). SPOD jobs never offer a PDF — the column is empty and
the PDF endpoint answers `404`.

What the admin surface does **not** show is the submission's `attempt_count` and
`last_error_code`. A job that sits open without ever reaching `prepared_at` is
therefore read in the database:

```sql
SELECT j.id, j.fulfillment_channel, j.prepared_at,
       o.create_state, o.create_ambiguous_count,
       o.attempt_count, o.last_error_code, o.external_reference, o.remote_state
FROM production_jobs j
LEFT JOIN production_spod_orders o ON o.production_job_id = j.id
WHERE j.fulfillment_channel = 'SPOD' AND j.prepared_at IS NULL
ORDER BY j.id;
```

`last_error_code` is one of the bounded codes below and says what to fix. Most
of them heal by themselves once the cause is gone — the worker retries every
job on every scan (once a minute) — with exactly one exception, the quarantine.

### Reconciling a quarantined job (`OUTCOME_UNKNOWN`)

This is the one state the automation will never leave on its own: `openJobs()`
excludes `create_state = 'OUTCOME_UNKNOWN'`, and no endpoint releases a job.
That is deliberate. The shop does not know whether the partner holds zero, one,
or two orders for this job, and only a human looking at the partner's backoffice
can tell.

1. Take the job and order number from the alert mail.
2. Search the partner's backoffice for the reference
   `ORD-{orderId}-JOB-{jobId}`. It is deterministic and appears on every order
   this job ever created, which is what makes the search possible at all.
3. Decide from what you find:

| What the backoffice shows | What it means | What to do |
| --- | --- | --- |
| No order with that reference | Both attempts really failed | Release the job (below); the next scan creates cleanly. |
| Exactly one order, state `NEW` | The order exists, inert, unconfirmed | Write its id into the job (below) and release it; the next scan confirms it. |
| Exactly one order, already confirmed | The confirmation went through, this shop never learned it | Write its id in and release; the next scan reads the state, sees `CONFIRMED`, and only sets `prepared_at`. |
| Two orders with the same reference | The first ambiguity created an orphan | Cancel the extra `NEW` order at the partner, then treat it as the single-order case. |

Releasing is a manual `UPDATE`, in this early development phase deliberately
not an endpoint — it is rare, it is irreversible in the sense that it starts a
paid production, and it must not be one click away:

```sql
-- Case "no order at all": start over.
UPDATE production_spod_orders
SET create_state = 'PENDING', create_ambiguous_count = 0, last_error_code = NULL
WHERE production_job_id = <jobId>;

-- Case "the order exists": adopt it, then let the worker confirm it.
UPDATE production_spod_orders
SET external_reference = '<the partner''s order id>',
    create_state = 'CREATED', create_ambiguous_count = 0, last_error_code = NULL
WHERE production_job_id = <jobId>;
```

Never invent an `external_reference`. A wrong id confirms somebody else's
order; `create_state = 'CREATED'` without one is refused by a check constraint,
which is the safety net for a typo, not for a guess.

If the order cannot be produced at all — the partner cancelled it, the delivery
country is not supported — the job stays where it is and the customer is
refunded manually (ADR 0002, decision 7). There is no automatic refund path.

How an operator learns of it is the alert mail: an unsupported delivery country
is not a state of its own but a refusal of `POST /orders`, so it reaches this
shop as `ORDER_CREATE_REJECTED`, and recording that code enqueues the
`SPOD_OPS_ALERT` in the same transaction. Nobody has to watch the job list for
it.

### Handling an alert

One mail kind, `SPOD_OPS_ALERT`, keyed by the production job, reaches
`production.spod.alertEmail` in three situations. The mail is German, names the
job and order number and the partner's order id, and carries no customer data —
an alert lands in a shared mailbox.

| Reason in the mail | What happened | The first move |
| --- | --- | --- |
| *SPOD hat den Auftrag storniert* | `Order.cancelled` arrived; `remote_state = 'CANCELLED'` | Check the backoffice for the reason, then refund the order or place it again. |
| *SPOD meldet, dass der Auftrag eine Rückmeldung braucht* | `Order.needs-action` arrived; `remote_state = 'NEEDS_ACTION'` | Answer whatever is open in the backoffice; the partner continues on its own. |
| *Zweimal in Folge blieb offen …* | The submission quarantined the job | Follow "Reconciling a quarantined job" above. |
| *Der Auftrag kann bei SPOD nicht angelegt werden …* | The submission recorded a failure that heals only by hand | Read the job's `last_error_code` (SQL above): a mapping or a phone number is fixed in the admin surface, an `ORDER_CREATE_REJECTED` is usually an order the partner will not accept at all — refund it manually (below). |

Because the mail is keyed by `(kind, source_id)`, a job produces **one** alert
however many events arrive — a cancellation after a needs-action does not
produce a second mail, and a quarantine that was already reported does not
either. The consequence to know: if an alert was already sent and a *different*
reason appears later, the outbox has nothing new to send, and the job's state
is read from the admin surface, not from the mailbox. The mail is a nudge, the
job is the truth.

An alert that cannot be resolved into a reason any more — the job was released,
the state was cleared — resolves to nothing and the mail is retried
(`SOURCE_NOT_FOUND`) rather than sent, which is the outbox's normal behaviour
for a reference whose subject has moved on.

## The bounded error codes

Everything the stage can persist in `last_error_code`. All of them are
retryable — the job keeps its place in the queue — but "retryable" is not
"heals by itself": most of them wait for an admin.

| Code | Meaning |
| --- | --- |
| `SOURCE_NOT_FOUND` / `SOURCE_INVALID` / `SOURCE_UNAVAILABLE` | the shared order-resolution codes |
| `DESTINATION_MISSING` / `DESTINATION_DISABLED` | the supplier has no usable print-on-demand destination |
| `ITEM_WITHOUT_SPOD_PRODUCT` | a variant carries no partner mapping |
| `ITEM_SET_CHANGED` | the live lines are no longer the snapshotted set — a line was added, removed, or moved |
| `SPOD_MAPPING_CHANGED` | today's variant name is not the snapshotted one |
| `PHONE_MISSING` | the order has no phone, which the partner requires |
| `PRINT_IMAGE_MISSING` / `PRINT_IMAGE_UNREADABLE` / `PRINT_IMAGE_TOO_LARGE` | the conversion could not produce a PNG |
| `RATE_LIMITED` | the partner answered `429` |
| `PROVIDER_UNAVAILABLE` | unreachable, timed out, or `5xx` |
| `PROVIDER_ANSWER_UNREADABLE` | an answer arrived that could not be read |
| `REFUSED` | the partner refused a call with a `4xx` |
| `ORDER_CREATE_REJECTED` | the creation was refused, also with an offered placement |
| `PLACEMENT_UNAVAILABLE` | no front placement exists for this design and product type |
| `ORDER_ID_NOT_STORED` | the created order's id could not be written down |
| `ORDER_CONFIRM_FAILED` | the partner refused to confirm a `NEW` order |
| `ORDER_STATE_UNEXPECTED` | the partner reports a state this stage will not act on |
| `SUBMISSION_FAILED` | the attempt failed in a way that is this backend's bug |

None of them is derived from anything the partner wrote. A provider body, a
token, and a URL can never reach a persisted column or a log line by
construction, because the only thing that travels out of the client is an
enum entry.

## Where the code lives

| File | Contents |
| --- | --- |
| [`spod/SpodEnvironment.kt`](../../../backend/modules/production/src/shop/voenix/production/spod/SpodEnvironment.kt) | The two installations and the base URL each derives in code. |
| [`delivery/spod/SpodClient.kt`](../../../backend/modules/production/src/shop/voenix/production/delivery/spod/SpodClient.kt) | The five calls, the pacer, the request and response shapes, `SpodResult`, `SpodError`. |
| [`delivery/spod/SpodOrderSubmitter.kt`](../../../backend/modules/production/src/shop/voenix/production/delivery/spod/SpodOrderSubmitter.kt) | The worker stage, its creation and confirmation halves, and `SpodSubmissionError`. |
| [`delivery/spod/SpodOrderRepository.kt`](../../../backend/modules/production/src/shop/voenix/production/delivery/spod/SpodOrderRepository.kt) | The two tables, the scan, and the guarded writes. |
| [`delivery/spod/PrintImagePng.kt`](../../../backend/modules/production/src/shop/voenix/production/delivery/spod/PrintImagePng.kt) | WebP → PNG with both budgets, and `PrintImageError`. |
| [`delivery/spod/SpodOpsAlertResolver.kt`](../../../backend/modules/production/src/shop/voenix/production/delivery/spod/SpodOpsAlertResolver.kt) | The ops alert mail of one job, and the reason it is derived from. |
| [`fulfillment/SpodWebhookRoutes.kt`](../../../backend/modules/production/src/shop/voenix/production/fulfillment/SpodWebhookRoutes.kt) | The inbound route, the secret comparison, and the payload reduced to bounded values. |

## Tests

- `PrintImagePngTest` — a WebP original in, PNG out; the pixel cap; the byte
  budget and the bounded shrink; the three failure codes.
- `SpodClientTest` — the exact request of every call including the
  `X-SPOD-ACCESS-TOKEN` header, the pacer's waiting arithmetic on a fake
  clock, `429` → `RATE_LIMITED`, `4xx` known versus `5xx` ambiguous, and that
  neither the token nor a provider body ever reaches a log line.
- `SpodOrderSubmissionIntegrationTest` — the full protocol against a stubbed
  partner that records every request: the happy path, the crash after the id
  was persisted (no second order, no second upload), the first ambiguity
  re-creating and the second quarantining, and the bounded codes for a missing
  phone, a renamed variant, a withdrawn mapping, and a missing print image —
  plus the ops alert the quarantine enqueues exactly once, the refused line set
  of a supplier reassignment, the alert a rejected creation enqueues once, and
  the cancelled order the scan drops while a needs-action one stays.
- `SpodWebhookRouteTest` — the wrong secret refused before the body is read
  (counted in the receive pipeline), the exact `202 [accepted]` ack, the payload
  reduced to bounded values, and every no-op answering like a processed event.
- `SpodWebhookIntegrationTest` — against real PostgreSQL: a shipment reported
  twice ending as one shipment and one customer mail, a shipment for an
  unprepared job shipping it and setting `prepared_at` while a human ship of the
  same job is still refused, the `OTHER` fallback with
  the raw name kept and the partner's link stored nowhere, the fallback
  resolution by this shop's own reference, and a cancellation plus a
  needs-action event producing exactly one alert.
