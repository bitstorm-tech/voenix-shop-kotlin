# SPOD fulfillment: the print-on-demand channel

This page explains how the job of a SPOD article (a t-shirt, a tote bag, any
product the print-on-demand partner makes) is produced by the TypeScript code in
[`backend-ts/src/modules/production/spod`](../../../../backend-ts/src/modules/production/spod)
and [`fulfillment`](../../../../backend-ts/src/modules/production/fulfillment).
It belongs to the [Production package guide](production-package.md), which
describes everything the two channels share; here we look only at the second
one, the partner **Spreadconnect (SPOD)**.

The eight decisions the channel was built on, and the alternatives they beat,
are listed at the end in [The design decisions](#the-design-decisions); the
code refers to them by number ("SPOD fulfillment decision 7").

## Two channels, one worker

A mug reaches its producer as a PDF pushed over SFTP. A SPOD article reaches
its producer as a conversation with a REST API. The split decides which of the
two a job belongs to when it creates the job, from the supplier's enabled
destinations, and freezes that decision in `production_jobs.fulfillment_channel`.

| | SFTP job | SPOD job |
| --- | --- | --- |
| Artifact | an immutable PDF, generated once | none at all |
| Delivery rows | one per enabled destination | none at all |
| Remote state | none | `production_spod_orders` + `production_spod_designs` |
| Ready to ship | `prepared_at` set when the PDF exists | `prepared_at` set when the remote order is confirmed |
| Shipment reported by | a person on the fulfillment page | the webhook (and a person as fallback) |

`prepared_at` is the shared column, and it is what the guarded ship update
reads.

## The one fact that shapes the whole design

The partner's API has **no idempotency mechanism**:

- `POST /orders` accepts no idempotency key. Sending it twice creates two
  orders.
- There is no endpoint that lists orders, and none that finds one by *our*
  `externalOrderReference`.
- The only way to read an order back is `GET /orders/{id}` with the id the
  creation answered with.

So: **if the order id is lost, the order is lost.** Two properties of the API
make that survivable:

- An order can be created in state `NEW`, and a `NEW` order **produces nothing
  and charges nothing** until `POST /orders/{id}/confirm`. An orphaned `NEW`
  order is litter, not damage.
- `GET /orders/{id}` reports the state, so a repeated attempt can tell "still
  `NEW`" from "already confirmed".

Everything below is built out of exactly those two properties.

## The submission stage, step by step

[`submitter.ts`](../../../../backend-ts/src/modules/production/spod/submitter.ts)
is the fourth stage of the production worker. Each scan takes the SPOD jobs
whose `prepared_at` is still `NULL`, oldest first, and makes **one** attempt
per job. Nothing retries inside the attempt; a failure records a bounded code
and the job waits for the next scan.

Two kinds of job are left out of the scan on purpose: one quarantined as
`create_state = 'OUTCOME_UNKNOWN'` (no automatic call until a person has
looked) and one whose `remote_state` is `'CANCELLED'` (finished remotely; re-
reading it would record `ORDER_STATE_UNEXPECTED` once a minute forever).
`NEEDS_ACTION` stays in the scan: somebody resolves it in the backoffice, and
the next scan continues.

### One worker, and why that matters more here

The protocol assumes a single process with a single production worker. On the
SFTP side a second worker would at worst render a PDF twice. Here it would
create a **second paid order**: the guarded writes protect this shop's rows,
not a call already in flight, and the id of the second order would be the one
nobody stores. Do not run two instances against one database without giving
this stage a lock first.

### 1. Resolve the order and check the lines

The order comes through the `productionData` read with the shared codes
(`SOURCE_NOT_FOUND`, `SOURCE_INVALID`, `SOURCE_UNAVAILABLE`), and production
resolves each line against today's catalog. The supplier's SPOD destination
must exist (`DESTINATION_MISSING`) and be enabled (`DESTINATION_DISABLED`).
Then the lines of the job are checked, in this order:

- The live lines of this supplier must be **exactly** the snapshotted set: same
  count, every snapshotted position present, never empty. Different →
  `ITEM_SET_CHANGED`.
- Each must carry `spodProduct`, the partner's three ids (product type,
  appearance, size) from **today's** catalog. Missing →
  `ITEM_WITHOUT_SPOD_PRODUCT`.
- Its **current** composed variant name (today's catalog) must equal the name
  the split snapshotted in `production_job_items`. Different →
  `SPOD_MAPPING_CHANGED`.
- It must carry `spodHotspot`, the placement the order line snapshotted at
  placement (for example `MEDIUM_FRONT`). Missing → `PLACEMENT_UNAVAILABLE`.

The first check is the one a per-line check cannot do, because it is about a
line that is *gone*. Reassigning an article to another supplier after the
split shortens this supplier's share of the order; without the check a fully
paid order would be ordered as a partial one, or as an order with nothing in
it.

The second and third checks are the safety net of decision 8. The three ids
are read from today's master data on every load, deliberately: a mapping an
admin fixes today heals every order still waiting. But the same liveness would
let a corrected mapping silently turn a paid "Black / M" into another product,
so the snapshotted name is the tripwire: production refuses rather than
guesses. Since the catalog is synced, the partner can trip it too; see
[`SPOD_MAPPING_CHANGED` after a rename in the backoffice](#spod_mapping_changed-after-a-rename-in-the-backoffice).

The hotspot check is different, because the hotspot is **not** live. The sync
measures one hotspot per product type, and the editor shows the customer
exactly that frame. So `order_items.spod_hotspot` (`V31`) stores it when the
order is placed, the `productionData` read answers it per line, and production
prints with that one, **never with today's catalog value and never with a
fallback** (#260, decisions 8 and 14). A line without one has no placement this
stage could honestly print, so the job waits for a person before a single
partner call. In practice it does not happen: an article without a hotspot is
inactive and cannot be bought.

### 2. Convert and upload the designs

Print images are stored as WebP; the partner's `POST /designs/upload` takes PNG
of at most 10 MB.
[`printImagePng`](../../../../backend-ts/src/modules/production/spod/print-image.ts)
converts with sharp, under two budgets:

- the longest edge is scaled down to at most **4096 px**;
- the PNG must stay under **9.5 MiB**. PNG is lossless, so the only way to make
  it smaller is to make the image smaller: it is halved and re-encoded, at most
  **three** times. The bound keeps a pathological image from looping forever.

Transparency is kept. The three codes are `PRINT_IMAGE_MISSING`,
`PRINT_IMAGE_UNREADABLE`, and `PRINT_IMAGE_TOO_LARGE`.

Uploads are grouped **per distinct print image**; the design id is written to
`production_spod_designs` **per item position**. That row is what makes a
re-scan skip an upload it already did, and what lets two lines that print the
same image share one design. Whatever was uploaded is recorded even when a later
upload of the same attempt fails.

### 3. Create the order, always `NEW`

The request is built in one place (`request` in `submitter.ts`) and carries:

- `state: "NEW"`, never `CONFIRMED`, and `shipping.preferredType: "STANDARD"`
  (both added by the `spod` module's client);
- the customer's `email` and `phone`, both required by the partner. Checkout
  requires a phone whenever the cart holds a SPOD article, so a missing one is
  a defensive `PHONE_MISSING`, checked *before* a design is uploaded;
- `externalOrderReference = "ORD-{orderId}-JOB-{jobId}"`, deterministic so the
  webhook can find the job and a person reads the same string on both screens;
- `oneTimeItems`, grouped the partner's way: one entry per (product type,
  design, hotspot, unit price), a `quantityItems` line per size and colour, and
  one `configurations` entry placing the design at `view: FRONT` with the
  **hotspot the order line snapshotted**. Two lines previewed with different
  hotspots cannot share an entry;
- on every entry `customerPricePerItem`, the line's `unitPriceCents` (variant
  price plus prompt price, before an order discount) in euros. The partner
  requires it and answers a bare `400` without it (see
  [the order request](spod-package.md#the-order-request)); two lines of one
  product at different prices are two entries, because the price belongs to
  the entry.

If the partner *refuses* the creation with a `4xx`, it has stated that it
created nothing, and the job fails with `ORDER_CREATE_REJECTED`, which alerts
an operator. There is **no** second attempt with another placement: print what
was previewed, or nothing (#260, decision 8). See
[`ORDER_CREATE_REJECTED` after a hotspot change](#order_create_rejected-after-a-hotspot-change).

### 4. Persist the id, in its own transaction, before anything else

`recordCreatedOrder` in
[`repository.ts`](../../../../backend-ts/src/modules/production/spod/repository.ts)
writes `external_reference` and flips `create_state` to `CREATED` in a
transaction of its own, guarded by `create_state = 'PENDING'`, *before* the
confirmation is attempted. A crash between creation and confirmation then costs
one confirm call on the next scan instead of an untraceable order. A refused
write (the row moved underneath the attempt) is `ORDER_ID_NOT_STORED`.

An **ambiguous** outcome (a timeout, a reset connection, an unreadable answer,
a `5xx`) may or may not have created an order:

- The first ambiguity increments `create_ambiguous_count`, leaves the state
  `PENDING`, and the next scan creates again. Worst case: one inert `NEW`
  orphan.
- The second sets `create_state = 'OUTCOME_UNKNOWN'` in the same statement, and
  the same transaction enqueues the ops alert. The job is quarantined;
  [Reconciling a quarantined job](#reconciling-a-quarantined-job-outcome_unknown)
  is the operator's checklist.

This is why a failed `SpodResult` carries `ambiguous` next to the error: a
stated `4xx` costs no ambiguity, everything else is assumed to have taken
effect.

### 5. Confirm, but read the state first

The confirmation reads `GET /orders/{id}` and confirms only while the state is
`NEW`. `CONFIRMED` is the second crash-recovery case: the confirm went through
and this process never learned it, so the job is closed without confirming
twice. Any other state is `ORDER_STATE_UNEXPECTED`; a refused confirmation is
`ORDER_CONFIRM_FAILED`. Success writes `confirmed_at`, `remote_state =
'CONFIRMED'`, and the job's `prepared_at` in **one** transaction: this channel's
counterpart of "the PDF exists".

## The client

The client lives in the [`spod` module](spod-package.md), shared with the
article module's catalog sync; this stage takes its four calls (`uploadDesign`,
`createOrder`, `getOrder`, `confirmOrder`). What matters here: nothing the
partner writes reaches a log line, the client never retries (retries belong to
this worker), it paces itself to 60 requests per minute for the whole
application, and every call takes the destination's `SpodAccess` (environment,
token, timeout); the base URL comes from the environment, never from a column.

## The tables

`V24__production_spod_orders.sql` adds two.

`production_spod_orders`, one row per job, created on the job's first attempt:

| Column | Meaning |
| --- | --- |
| `production_job_id` | primary key, `RESTRICT` against `production_jobs` |
| `external_reference` | the partner's order id; `NULL` until created, unique once set |
| `create_state` | `PENDING` \| `CREATED` \| `OUTCOME_UNKNOWN` |
| `create_ambiguous_count` | how many creations left the outcome undecided |
| `attempt_count`, `last_error_code` | the retry bookkeeping, bounded codes only |
| `confirmed_at`, `remote_state` | `CONFIRMED` here; the webhook writes `NEEDS_ACTION` / `CANCELLED` |

Two checks keep the state honest: a `CREATED` row and a confirmed one must have
an `external_reference`.

`production_spod_designs`, one row per item position: `(production_job_id,
position)` and the `design_id`, cascading with the order row.

## The webhook: how a shipment comes back

`POST /api/production/webhooks/spod/{secret}` follows the Mollie webhook of the
payment module (decision 5). It exists only in a deployment with SPOD settings.

- **No auth subtree.** The partner has no session and sends no CSRF token, so
  the secret in the path takes their place. It is compared in constant time
  (`timingSafeEqual`) **before the body is read**; a wrong one answers
  `403 {"message":"Forbidden"}`. A route test counts the reads of the body
  stream and pins zero. One caveat lives in the platform: a request with
  `Transfer-Encoding: chunked` and no `Content-Length` is buffered by the
  application-wide body limit before any route runs, so for such a request the
  guarantee is "the body is held in memory up to 30,000,000 bytes, but not
  parsed, before the secret is checked". The port left it that way on purpose
  (recorded in `docs/migration/typescript-port.md`): exempting the webhook
  paths from the limit would remove the only transfer bound these routes have,
  the partner sends its small JSON event with a `Content-Length`, and a
  per-route limit is more code than the caveat warrants.
- **The answer is always `202` with the body `[accepted]`.** The partner demands
  exactly that within eight seconds and redelivers anything else, so every
  no-op answers it too: an unknown reference, a job that shipped hours ago, an
  event type this shop does not act on, a body that is not JSON. Only a
  database failure becomes a `500` (the handler throws), because that is the one
  thing a redelivery fixes.
- **The handler is synchronous**: two lookups and one guarded transaction.

Three events are acted on:

| Event | What happens |
| --- | --- |
| `Shipment.sent` | the job goes through the **same guarded ship transaction** a person uses: `shipped_by_channel = 'SPOD'`, `shipped_by_user_id` `NULL`, one customer mail |
| `Order.cancelled` | `remote_state = 'CANCELLED'` and an ops alert, if the state changed |
| `Order.needs-action` | `remote_state = 'NEEDS_ACTION'` and an ops alert, if the state changed |

There is one difference between that ship and a person's: the `prepared_at`
guard. A person's ship keeps it (`NOT_READY`). A channel ship drops it, because
the shipment proves the remote order exists and was confirmed, and because the
`202` means nothing will ever redeliver the event. The one real case is a job
quarantined as `OUTCOME_UNKNOWN` whose order an operator adopted by hand. So
the statement sets `prepared_at = COALESCE(prepared_at, now())`.

[`webhook.ts`](../../../../backend-ts/src/modules/production/fulfillment/webhook.ts)
reduces the untrusted body to bounded values. Ids may arrive as numbers or
strings and are read as text; a field of any other shape counts as absent;
unknown fields are ignored. The job is found by the partner's order id
(`production_spod_orders.external_reference`) first and, when none is stored
(the case an ambiguous creation leaves behind), by this shop's
`ORD-{orderId}-JOB-{jobId}`, whose two ids are then checked against a SPOD job
of that order. References are trimmed and at most 128 characters; the tracking
number at most 128 and free of control characters. The carrier name is mapped
case- and separator-insensitively onto the bounded list (`Deutsche Post`,
`deutsche_post`, and `DEUTSCHE POST` are one carrier), anything else is
`OTHER`, and the raw name is kept in `shipping_carrier_reported`. The partner's
**tracking URL is not even a field** of the payload: the shop builds every link
from its own carrier list (decision J2 of issue #119). A parse failure is
logged by its class, never by its message, which would quote the body.

### At-least-once delivery

The partner delivers every event at least once, and the payload outbox (#248)
no longer has a unique `(kind, source_id)` that could deduplicate a mail. So
"once" lives in the guarded writes:

- the ship transaction is guarded on `shipped_at IS NULL`: a redelivered
  `Shipment.sent` touches no row and enqueues no second mail;
- the remote-state write is `UPDATE … WHERE remote_state IS DISTINCT FROM $1`,
  and the alert is enqueued **only when that update changed a row**.

The result is one alert per distinct state transition: a redelivered
cancellation sends nothing, while a cancellation followed by a needs-action
event sends two alerts. That is the decision recorded on #274, and it is more
informative than the Kotlin behaviour, where the second reason was swallowed.

## The ops alert

Four situations end on a person's desk, and all of them enqueue the same mail
kind, `SPOD_OPS_ALERT`, rendered by `renderSpodOpsAlert` inside the
transaction that records the situation:

| Situation | Reason in the mail |
| --- | --- |
| the partner cancelled the order | `CANCELLED` |
| the partner flagged it as needing action | `NEEDS_ACTION` |
| the submission quarantined the job (second ambiguity) | `OUTCOME_UNKNOWN` |
| the submission recorded a code no scan can heal | `SUBMISSION_BLOCKED` |

The last row is the set of codes that wait for a *person*:
`ORDER_CREATE_REJECTED`, `PLACEMENT_UNAVAILABLE`, `PHONE_MISSING`,
`ITEM_WITHOUT_SPOD_PRODUCT`, `SPOD_MAPPING_CHANGED`, `ITEM_SET_CHANGED`. The
failure write is guarded like the remote state: `last_error_code IS DISTINCT
FROM $1`, so a code every scan records again sends no second mail, while the
same code recorded after a different one in between (a transient error, say)
sends a new alert. Every other code stays silent: it heals by itself.

The mail goes to `PRODUCTION_SPOD_ALERT_EMAIL` and names the job number, the
order number, and the partner's order id, with one sentence per reason. It
carries no customer data, because an alert lands in a shared mailbox, and the
reason travels as an enum, so nothing the partner wrote reaches a mail.

## The ops runbook

Everything below happens in the admin UI, the deployment's environment, or the
partner's backoffice, never in code.

### The two credentials, and why they live apart

| Credential | Where it lives | Who sets it |
| --- | --- | --- |
| The partner's **access token** | `production_destination_spod.access_token`, one row per supplier destination | an admin, in the admin UI |
| The **webhook secret** | `PRODUCTION_SPOD_WEBHOOK_SECRET` | whoever deploys |

The token is per supplier and changes as often as a supplier does, so it is
master data. The secret is one per deployment, the last path segment of the
callback URL, and must survive a database restore independently, so it is
configuration. The token is write-only everywhere: stored as entered, answered
by no read, and kept when an update leaves it empty.

### Configuration

```sh
PRODUCTION_SPOD_WEBHOOK_SECRET=   # at least 32 characters
PRODUCTION_SPOD_ALERT_EMAIL=
```

Both are blank in a deployment without a print-on-demand supplier. A deployment
that has one must set **both**, and the application enforces it:

- one set and the other blank, or a secret under 32 characters → `loadSettings`
  fails (`PRODUCTION_SPOD_WEBHOOK_SECRET must be at least 32 characters`,
  `PRODUCTION_SPOD_ALERT_EMAIL is required when the SPOD channel is configured`);
- both blank while a `channel = 'SPOD'` destination exists → the production
  module refuses to start with
  `A print-on-demand destination exists, so production.spod.webhookSecret and production.spod.alertEmail are required`,
  before its worker exists;
- the write side: `POST`/`PUT /api/admin/production/destinations` refuses a SPOD
  destination with a `channel` field error while the block is missing, enabled
  or not.

A configured block without any SPOD destination is allowed: that is how a
deployment is set up.

### Setting a supplier up, staging first

The step-by-step manual is
[Configuring a SPOD article](../../guides/configuring-spod-articles.md); this
section keeps the reasons. The `environment` column alone picks the
installation:

| `environment` | Base URL |
| --- | --- |
| `STAGING` | `https://rest.spod-staging.com` |
| `PRODUCTION` | `https://rest.spod.com` |

The installations have **separate tokens and data**; a token of one is refused
by the other as `REFUSED`. The order:

1. Create the supplier and its SPOD destination (`channel: "SPOD"`, a label,
   `enabled`, the notification address, the `spod` block).
2. Create the article in the Spreadconnect backoffice and press *Sync from
   Spreadconnect*: the sync writes the three product ids and measures the
   hotspot and the print frame (SPOD sync decisions 1 and 7).
3. Register the webhook subscriptions (next section) against the **same**
   installation.
4. Order one article end to end and watch the job: it must reach `prepared_at`,
   then arrive as a shipment through the webhook.

Only one **enabled** SPOD destination per supplier is allowed, so switching
from staging to production means disabling the staging row first, or editing
it, which keeps the job history attached.

### Registering the webhook subscriptions

```sh
SPOD_BASE=https://rest.spod-staging.com   # or https://rest.spod.com
for event in Shipment.sent Order.cancelled Order.needs-action; do
  curl -X POST "$SPOD_BASE/subscriptions" \
    -H "X-SPOD-ACCESS-TOKEN: $SPOD_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"eventType\":\"$event\",\"url\":\"https://<shop-host>/api/production/webhooks/spod/$PRODUCTION_SPOD_WEBHOOK_SECRET\"}"
done
```

Two checks: `curl -i -X POST https://<shop-host>/api/production/webhooks/spod/wrong-secret`
must answer `403`, and the real secret `202` with `[accepted]` for any body.
The URL must be reachable from the public internet; a shop behind a VPN gets
no shipments and no error. Rotating the secret means deploying the new value
and re-registering; the old URL answers `403` from then on.

### Reading a job's state

The admin page *Logistics* (`GET /api/admin/production/jobs`) shows per job
the `fulfillmentChannel`, the `externalReference`, the `remoteState`, and who
reported the shipment. SPOD jobs never offer a PDF. The submission's
`attempt_count` and `last_error_code` are read in the database:

```sql
SELECT j.id, j.fulfillment_channel, j.prepared_at,
       o.create_state, o.create_ambiguous_count,
       o.attempt_count, o.last_error_code, o.external_reference, o.remote_state
FROM production_jobs j
LEFT JOIN production_spod_orders o ON o.production_job_id = j.id
WHERE j.fulfillment_channel = 'SPOD' AND j.prepared_at IS NULL
ORDER BY j.id;
```

Every job is retried on every scan (once a minute), with exactly one exception,
the quarantine.

### Reconciling a quarantined job (`OUTCOME_UNKNOWN`)

The scan excludes `create_state = 'OUTCOME_UNKNOWN'`, and no endpoint releases
a job: only a person looking at the partner's backoffice can tell whether it
holds zero, one, or two orders for this job.

1. Take the job and order number from the alert mail.
2. Search the backoffice for `ORD-{orderId}-JOB-{jobId}`.
3. Decide:

| What the backoffice shows | What it means | What to do |
| --- | --- | --- |
| No order with that reference | Both attempts really failed | Release the job (below); the next scan creates cleanly. |
| Exactly one order, state `NEW` | The order exists, unconfirmed | Write its id into the job and release it; the next scan confirms it. |
| Exactly one order, already confirmed | The confirmation went through | Write its id in and release; the next scan sees `CONFIRMED` and only sets `prepared_at`. |
| Two orders with the same reference | The first ambiguity created an orphan | Cancel the extra `NEW` order at the partner, then treat it as the single-order case. |

Releasing is a manual `UPDATE`, deliberately not an endpoint: it starts a paid
production and must not be one click away.

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

Never invent an `external_reference`: a wrong id confirms somebody else's
order. If the order cannot be produced at all, the job stays and the customer
is refunded manually (decision 7).

### Handling an alert

| Reason in the mail | What happened | The first move |
| --- | --- | --- |
| *SPOD hat den Auftrag storniert* | `Order.cancelled`; `remote_state = 'CANCELLED'` | Check the backoffice for the reason, then refund or place again. |
| *SPOD meldet, dass der Auftrag eine Rückmeldung braucht* | `Order.needs-action`; `remote_state = 'NEEDS_ACTION'` | Answer what is open in the backoffice; the partner continues. |
| *Zweimal in Folge blieb offen …* | the submission quarantined the job | Follow "Reconciling a quarantined job". |
| *Der Auftrag kann bei SPOD nicht angelegt werden …* | the submission recorded a code that heals only by hand | Read `last_error_code` (SQL above): fix a mapping or a phone in the admin surface; an `ORDER_CREATE_REJECTED` is usually an order to refund. |

Each state transition sends its own alert, and a redelivered event sends none.
The mail is a nudge; the job is the truth.

### `SPOD_MAPPING_CHANGED` after a rename in the backoffice

Renaming a colour in the backoffice and syncing rewrites `color_name`, and with
it today's composed name of every variant of that colour. The tripwire then
refuses every waiting job of that colour. **That is a false alarm, accepted on
purpose** (SPOD sync decision 8; Joe's decision D1 on issue #224): the three ids
are untouched.

1. Compare the **three SPOD ids** of today's variant (`spod_product_type_id`,
   `spod_size_id`, and its colour's `spod_appearance_id`) with the order.
2. Unchanged ids, changed name: update `production_job_items.variant_name` to
   today's composed name (`"<colour> / <size>"`); the next scan submits.
3. **Changed ids** are the real alarm. Do not touch the snapshot; look at the
   order before anything is printed.

Issue #225 removes the false alarm by snapshotting the three ids.

### `ORDER_CREATE_REJECTED` after a hotspot change

The order line stores the hotspot the customer previewed. If the article was
synced again with a **different** hotspot, or the partner stopped offering the
old one, the partner may refuse the creation. Nothing is retried with another
placement.

1. Compare the line's snapshot with today's article:

   ```sql
   SELECT oi.order_id, oi.position, oi.spod_hotspot AS ordered_hotspot,
          a.spod_hotspot AS current_hotspot
   FROM voenix.order_items oi
   JOIN voenix.article_spod_articles a ON a.id = oi.article_id
   WHERE oi.order_id = <order id>;
   ```

2. **Re-sync** when the partner should accept the ordered hotspot again; the
   next scan still sends the snapshot.
3. Otherwise **order the job by hand** in the backoffice in state `NEW`, with
   the ordered hotspot and the reference `ORD-{orderId}-JOB-{jobId}`, and adopt
   it like the "the order exists" case above.
4. If no placement matches the preview, refund manually (decision 7).

## The bounded error codes

Everything the stage persists in `last_error_code`. All are retryable, but
most wait for an admin.

| Code | Meaning |
| --- | --- |
| `SOURCE_NOT_FOUND` / `SOURCE_INVALID` / `SOURCE_UNAVAILABLE` | the shared order-resolution codes |
| `DESTINATION_MISSING` / `DESTINATION_DISABLED` | the supplier has no usable SPOD destination |
| `ITEM_WITHOUT_SPOD_PRODUCT` | a variant carries no partner mapping today |
| `ITEM_SET_CHANGED` | the live lines are no longer the snapshotted set |
| `SPOD_MAPPING_CHANGED` | today's variant name is not the snapshotted one |
| `PHONE_MISSING` | the order has no phone |
| `PRINT_IMAGE_MISSING` / `PRINT_IMAGE_UNREADABLE` / `PRINT_IMAGE_TOO_LARGE` | the conversion produced no PNG |
| `RATE_LIMITED` | the partner answered `429` |
| `PROVIDER_UNAVAILABLE` | unreachable, timed out, or `5xx` |
| `PROVIDER_ANSWER_UNREADABLE` | an answer that could not be read |
| `REFUSED` | the partner refused a call with a `4xx` (an upload or a state read) |
| `ORDER_CREATE_REJECTED` | the partner refused the creation; never retried with another placement |
| `PLACEMENT_UNAVAILABLE` | a line carries no snapshotted hotspot |
| `ORDER_ID_NOT_STORED` | the created order's id could not be written down |
| `ORDER_CONFIRM_FAILED` | the partner refused to confirm a `NEW` order |
| `ORDER_STATE_UNEXPECTED` | the partner reports a state this stage will not act on |
| `SUBMISSION_FAILED` | the attempt threw: this backend's bug |

None is derived from anything the partner wrote.

## The design decisions

These eight decisions were made when the first SPOD article arrived (issue
#205); issue #260 made them hold for every SPOD article and amended decision 4.
The port keeps all eight; decision 5's "one alert per job" became "one alert
per state transition" with the payload outbox (#248, recorded on #274).

The facts of the [partner's API](https://api.spreadconnect.app/docs/) behind
them: no idempotency; a non-blank phone **and** e-mail on every order; a webhook
acknowledged with **202 and `[accepted]`** within 8 seconds, delivered at least
once; `Shipment.sent` carries carrier, tracking code, tracking URL, and our
reference; shipping can be preset `STANDARD`; placement is a `view` plus a
`hotspot`.

### Decision 1: The channel is a property of the job, decided at split time

`production_jobs.fulfillment_channel` is derived from the supplier's enabled
destinations when the split creates the job, and frozen. Routing stays live
master data; a job in flight never changes its lifecycle.

### Decision 2: A PDF is not a universal job artifact

SFTP jobs keep their lifecycle. SPOD jobs create **no PDF and no deliveries**;
their remote lifecycle lives in two tables of their own. Joe decided this
against a 2:1 council majority, and the majority's price was accepted:
`production_job_items` is written in the split transaction for both channels
(a rename between split and generation can make page and PDF of an SFTP job
disagree about a name, for minutes), and the ship guard became the
channel-neutral `prepared_at`.

### Decision 3: Destinations get per-channel detail tables

The base row keeps identity, supplier, channel, label, `enabled`, and the
notification fields; each channel's columns are `NOT NULL` in a table of their
own, linked by the composite key `(id, channel)`. The SPOD row carries
`environment` and a write-only token; the base URL is derived in code. A
supplier has at most one **enabled** SPOD destination.

### Decision 4: The submission protocol and its idempotency

Upload the designs → `POST /orders` always `NEW` with `STANDARD` shipping, the
customer's phone and e-mail, and `ORD-{orderId}-JOB-{jobId}` → **persist the id
before anything else** → confirm → `prepared_at`. Placement is `view: FRONT`
with the hotspot snapshotted on the order line; no fallback: a refusal holds
the job with an alert (`ORDER_CREATE_REJECTED`), a line without a hotspot is
`PLACEMENT_UNAVAILABLE` (#260, decisions 8 and 14). This replaced a default
`MEDIUM_FRONT` with a fallback to the available-hotspots endpoint, which could
print something smaller than the preview. One ambiguous creation is re-created;
a second quarantines the job with an alert. A confirmation retry reads the
state first. The client paces itself; the worker retries.

### Decision 5: The inbound webhook follows the Mollie pattern

No session; the secret from configuration (at least 32 characters) compared in
constant time before the body is read; a synchronous handler; every outcome
answers `202 [accepted]`. `Shipment.sent` reuses the guarded ship transaction
with the channel as the reporter, the carrier mapped onto the bounded list,
and the partner's tracking URL discarded. `Order.cancelled`,
`Order.needs-action`, and the quarantine set the state and alert the operator,
once per transition. A person's ship stays allowed as the fallback for a lost
webhook.

### Decision 6: The order carries the customer's contact data

The partner gets the **customer's** phone and e-mail (Joe). Checkout requires a
phone whenever the cart contains a SPOD line; the `productionData` read carries
both, the supplier's `fulfillmentOrders` read carries neither.

### Decision 7: No checkout country gate

A delivery country the partner does not support fails the submission
(`ORDER_CREATE_REJECTED`) with an alert, and the operator refunds by hand. No
`checkout → production` dependency.

### Decision 8: What reaches production stays live

The three partner ids are resolved at submission time from today's catalog,
like the supplier: a mapping fix heals every pending order. The safety net:
today's composed variant name must equal the snapshotted one, so a paid
"Black / M" never silently becomes another product. The hotspot is the
exception, a snapshot (decision 4). A backoffice rename trips the check as a
false alarm, accepted until issue #225 compares ids. Orders stay
`oneTimeItems` and never use the synced articles' SKUs (SPOD sync decision 3).

## File map

| File | Contents |
| --- | --- |
| [`spod/submitter.ts`](../../../../backend-ts/src/modules/production/spod/submitter.ts) | the stage (line checks, uploads, creation, confirmation), the order request, `spodOrderReference` and its parser |
| [`spod/repository.ts`](../../../../backend-ts/src/modules/production/spod/repository.ts) | the two tables, the scan, the guarded writes with their alerts, the destination read, `PERMANENT_FAILURES` |
| [`spod/print-image.ts`](../../../../backend-ts/src/modules/production/spod/print-image.ts) | WebP → PNG with both budgets |
| [`fulfillment/webhook.ts`](../../../../backend-ts/src/modules/production/fulfillment/webhook.ts) | the payload reduced to bounded values, the secret comparison |
| [`fulfillment/routes.ts`](../../../../backend-ts/src/modules/production/fulfillment/routes.ts) | the webhook route next to the two job surfaces |
| [`fulfillment/service.ts`](../../../../backend-ts/src/modules/production/fulfillment/service.ts) | `handleWebhook`: finding the job, the channel ship, the remote state |
| `spod` module: [`client.ts`](../../../../backend-ts/src/modules/spod/client.ts) | `SpodAccess`, the environments, the calls, the pacer, `SpodResult`, `SpodError` |

## Differences to the Kotlin code

- **Alerts are rendered and guarded, not deduplicated.** See
  [At-least-once delivery](#at-least-once-delivery) and
  [The ops alert](#the-ops-alert); the reason is fixed when the alert is
  enqueued instead of being derived from the row at send time.
- **The name tripwire compares today's catalog name.** The Kotlin
  `ProductionItem.variantName` was the order line's snapshot, the same value the
  split had written, so the comparison could never fire; the port compares
  today's composed name from `articleCatalog`, which is what the guide and the
  runbook always described.
- **sharp instead of ImageIO** for the PNG conversion, with the same budgets.

## Tests

- `spod/submission.test.ts`, against a recording stub of the client: the happy
  path with the exact order request (snapshotted hotspot, reference, address,
  grouping) and no call on a re-scan; one design for two lines and two entries
  for two hotspots; `PLACEMENT_UNAVAILABLE` before any partner call with one
  alert over two scans; the crash after the id was persisted (no second order);
  the first ambiguity re-creating and the second quarantining with one alert;
  a refused creation sent once per scan with the same hotspot, one alert; the
  master-data codes before any upload and which of them alert; the renamed and
  the reassigned line; the cancelled job left out of the scan while the
  needs-action one stays.
- `pdf/pdf.test.ts`: the PNG conversion, the pixel cap, the bounded shrink, the
  three codes.
- `fulfillment/fulfillment.test.ts`: the wrong secret refused with zero reads of
  the body; a shipment reported twice ending as one shipment by channel and one
  mail, carrier mapped and the partner's link stored nowhere; the shop-reference
  fallback with a mismatching order as a no-op, shipping an unprepared job while
  a person's ship is refused; a redelivered cancellation alerting once and a
  needs-action alerting again; every no-op answering `202 [accepted]`; no route
  without SPOD settings.
- The client itself is tested in the [`spod` module](spod-package.md#tests).
