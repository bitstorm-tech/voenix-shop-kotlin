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

### 1. Resolve the order and check the lines

The order comes through the shared `ProductionSource` with the shared
`resolveOrder` codes (`SOURCE_NOT_FOUND`, `SOURCE_INVALID`,
`SOURCE_UNAVAILABLE`). Then each line of the job is checked twice:

- It must carry `spodProduct`, the partner's three ids (product type,
  appearance, size). Missing → `ITEM_WITHOUT_SPOD_PRODUCT`.
- Its **current** composed variant name must equal the name the split
  snapshotted in `production_job_items`. Different → `SPOD_MAPPING_CHANGED`.

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
is `ORDER_CREATE_REJECTED`.

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
  the scan stops handing it out — and a human reads the partner's backoffice.
  T11 attaches the ops-alert mail to this state.

This is why `SpodResult.Failed` carries `ambiguous` next to the error code: a
stated `4xx` refusal is *known* and costs no ambiguity, everything else is
assumed to have taken effect.

### 5. Confirm — but read the state first

The confirmation reads `GET /orders/{id}` and only confirms while the state is
`NEW`. A state of `CONFIRMED` is not a failure but the second crash-recovery
case: the confirm went through and this backend never learned it, so the job
is closed without confirming twice. Any other state is `ORDER_STATE_UNEXPECTED`
— the webhook (T12) owns what happens to an order the partner cancelled or
flagged.

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
  measured on a monotonic clock (`System.nanoTime`). A `429` that happens
  anyway is the retryable code `RATE_LIMITED`.
- **Where and how to authenticate travels with the call.** Each supplier has
  its own destination row — environment, token, timeout — so every call takes
  a `ProductionDeliveryDestination.Spod` and reads all three off it. The base
  URL comes from the `SpodEnvironment` enum, never from a column: no admin
  input can point fulfillment at an arbitrary host.

The pacer's clock and its sleeping function are constructor parameters, which
is how `SpodClientTest` observes the exact waiting arithmetic without a test
that takes seconds.

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

## The bounded error codes

Everything the stage can persist in `last_error_code`. All of them are
retryable — the job keeps its place in the queue — but "retryable" is not
"heals by itself": most of them wait for an admin.

| Code | Meaning |
| --- | --- |
| `SOURCE_NOT_FOUND` / `SOURCE_INVALID` / `SOURCE_UNAVAILABLE` | the shared order-resolution codes |
| `DESTINATION_MISSING` / `DESTINATION_DISABLED` | the supplier has no usable print-on-demand destination |
| `ITEM_WITHOUT_SPOD_PRODUCT` | a variant carries no partner mapping |
| `ITEM_SNAPSHOT_MISSING` | the split and the source disagree about the job's lines |
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
  phone, a renamed variant, a withdrawn mapping, and a missing print image.
