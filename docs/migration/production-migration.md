# Production module migration

This file is the task brief and durable decision record for migrating the
legacy production-PDF and SFTP behavior into the Kotlin `production` module.
General migration rules remain in
[`module-migration-guide.md`](module-migration-guide.md).

## Task parameters

Target module:

`Production`

Source project:

`/Users/joe/projects/joto-ai/voenix-shop`

Source feature:

- `/Users/joe/projects/joto-ai/voenix-shop/backend/Voenix.Api/Features/SftpUpload`
- `/Users/joe/projects/joto-ai/voenix-shop/backend/Voenix.Api/Features/Order/PdfDocument.cs`
- `/Users/joe/projects/joto-ai/voenix-shop/backend/Voenix.Api/Features/Order/Services/PdfService.cs`
- `/Users/joe/projects/joto-ai/voenix-shop/backend/Voenix.Api/Features/Order/Services/PaidOrderProcessor.cs`

Target project:

`/Users/joe/projects/joto-ai/voenix-shop-kotlin-worktrees/migrate-production-module`

Target package:

`/Users/joe/projects/joto-ai/voenix-shop-kotlin-worktrees/migrate-production-module/backend/modules/production/src/shop/voenix/production`

Analysis checkpoint:

`approved` — the material product and persistence decisions were resolved with
Joe in the brainstorming session on 2026-07-22. The decision record below is
authoritative. A small set of engineering defaults was adopted without an
explicit veto opportunity and is listed separately under "Adopted engineering
defaults".

Known consumers:

- the future Order/payment-completion operation that requests production;
- the existing admin/development PDF download contract
  `GET /api/orders/{id}/pdf` (retained only behind admin auth if an
  operational consumer is confirmed);
- the Email module for the post-delivery producer notification
  (`QueuedEmailReference.ProducerPdfNotification`); and
- the future admin frontend, which will present production destination
  configuration on the supplier page (backend API owned by Production).

Approved deviations from current behavior:

- The Kotlin feature is one `production` compilation module rather than a
  source-shaped `SftpUpload` feature.
- PDF generation belongs in `shop.voenix.production.pdf` and durable delivery
  orchestration belongs in `shop.voenix.production.delivery`.
- Delivery supports multiple channel adapters behind an internal seam. SFTP is
  the only implemented channel for now; email remains a notification, not a
  delivery channel.
- Delivery intents and retry state are persisted rather than relying on an
  in-memory channel.
- Destinations are routed per supplier and configured in the database
  (admin-managed), not in static application configuration. Legacy
  config-based fan-out to all enabled servers is not migrated. No hybrid or
  compatibility layer: the legacy configuration shape is dead.
- An order may contain items from multiple suppliers. Production splits the
  order into one production job per supplier; each supplier receives a PDF
  containing only its items.
- The generated PDF artifact is persisted once on the local filesystem
  (single source of truth); the database stores only metadata including a
  SHA-256 digest.

Explicitly deferred work:

- The future Order migration owns the exact production trigger and the real
  `ProductionSource` implementation.
- Application installation remains deferred until every source needed by the
  configured Production and queued Email workers can be composed without a
  placeholder.
- An authenticated operational retry/cancel/inspection UI is deferred until a
  concrete support workflow exists.
- The admin frontend UI for destination configuration; the backend admin API
  ships with this module, rows can be maintained via API or SQL until then.
- Artifact retention/cleanup policy: artifacts are kept indefinitely
  initially; an explicit cleanup job is future operational work.

## Outcome

Create a cohesive Production module that, for each production-ready order,
produces one stable production PDF **per involved supplier** and delivers each
artifact to that supplier's configured destinations through channel adapters.
The module, rather than any individual adapter, owns durable intent, retry
coordination, and truthful delivery state.

Email is **not** a delivery channel. The producer email remains a
notification that a file was delivered, enqueued through the existing
`EmailOutbox` (`QueuedEmailReference.ProducerPdfNotification`) in the same
database transaction that records `delivered_at` — making "delivered +
notification queued" atomic, which is stronger than the legacy best-effort
behavior. A real PDF-by-email channel (or other channels) may come later and
would slot in as an additional delivery adapter; nothing is built for it now.

## Source slice and material consumers

The analyzed source slice includes the complete SFTP implementation, SFTP
configuration and schema migration, PDF implementation, meaningful PDF/SFTP
tests, `PaidOrderProcessor`, Email producer-notification behavior, application
registration, and repository consumer searches. No frontend consumer of the
PDF or SFTP development routes was found.

The source has two hidden workflows:

1. payment completion creates one SFTP task for every enabled server in the
   same database transaction that marks the Order paid, then wakes an
   in-memory worker; and
2. that worker regenerates a PDF from current Order, Article, mug-layout, and
   image-file data before attempting all open SFTP tasks for the Order.

The producer email is a third workflow triggered only after an SFTP task is
marked successful. Its content says that the file was uploaded to a named SFTP
server; it does not attach or otherwise transmit the production PDF.

## Behavior analysis

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| A missing Order fails PDF generation. | `PdfService`, `PdfServiceTests` | Required | `ProductionSource.load(orderId)` returns no source; public generation maps that to a typed not-found result and background processing records a safe retry error. | Service and worker tests |
| The file name is `ORD-{orderId}.pdf`. | PDF route, SFTP service and tests | Required current contract | Keep `ORD-{orderId}.pdf` as the producer-facing remote file name. It is unique per destination because each supplier only ever receives its own jobs. On disk, artifacts are stored under a job-scoped path (`{jobId}/ORD-{orderId}.pdf`) so two suppliers' PDFs for the same order never collide. | Generator and adapter tests |
| The first PDF page is a 239 mm by 99 mm shipping-address page with a rotated Order label. | `PdfDocument` | Required layout | Recreate explicitly with a JVM PDF library, keeping dimensions and visible content independent of the library API. Every per-supplier PDF gets its own address page. | Rendered-page image comparison and text/dimension checks |
| Each physical quantity creates one item page and item numbering spans expanded quantities. | `PdfService`, `PdfDocument`, tests | Required | Expand immutable production items before rendering and assign stable 1-based indexes **within the supplier's job**. | Pure mapping tests and rendered page count |
| Mug details may override page size, print area, and bottom margin. | `PdfService`, `PdfDocument` | Required | Keep optional per-item layout values in the Production source contract and renderer. | Focused layout fixtures |
| Item pages show the generated image when available plus rotated article, supplier article number, and variant text. | `PdfDocument` | Required; silent missing image was unclear | Preserve content. Decided: a missing production image is a retryable generation failure, never a blank item page. | Golden fixtures for image, metadata, Unicode, and missing-image behavior |
| One task is created for every enabled SFTP server when the Order becomes paid. | `PaidOrderProcessor` and tests | Superseded product behavior | Decided: routing hangs off the producer, not the order. The trigger inserts one durable production request per order; the worker splits it into one job per involved supplier and fans out to that supplier's enabled destinations only. | PostgreSQL atomicity, duplicate trigger, and routing/splitting tests |
| No task means no PDF generation or external call. | `SftpUploadService` and tests | Required efficiency and safety | Worker scans durable production work only. | Worker test |
| Pending and failed tasks retry after restart; successful tasks are skipped. | SFTP worker/service and tests | Required | Derive open/completed state from `delivered_at`; every open row remains retryable after restart. | PostgreSQL restart tests |
| PDF generation happens once per Order processing call and is shared by all server attempts in that call. | `SftpUploadService` | Required intent, weak source mechanism | Generate each supplier artifact once, persist it on disk with a SHA-256 digest, then give the same bytes to every adapter and every retry. | Hash equality across destinations/retries |
| Failure of one server does not block another server. | SFTP tests | Required | Process and record every delivery independently. | Mixed adapter-result test |
| A missing configured server marks the task failed; a disabled server leaves it pending. | SFTP service/tests | Required recoverability, exact status incidental | Missing/disabled destination remains open with a safe reason and can recover after configuration changes (destinations are DB rows, so recovery is an admin action). | Settings-change tests |
| Source SFTP performs up to three immediate attempts with 1, 5, and 15 second delays. | `SftpUploadService` | Approved deviation | One external attempt per worker scan; every attempt persisted. No nested retry loops in the worker. | Attempt counter and poll-cycle tests |
| Startup recovery is supplemented by an unbounded in-memory channel. | background service/channel | Recovery required; channel incidental | Poll PostgreSQL on startup and at a configured interval. An in-memory wake-up optimization is unnecessary initially. | Startup and no-lost-wakeup tests |
| SFTP uses password authentication, a remote path, and a timeout, but does not verify the server host key. | `SftpClientFactory`, settings | Password/path/timeout required; missing verification is a security defect | Require a pinned host-key fingerprint for every enabled destination. Never provide a permissive verifier. | Settings validation and embedded-SFTP tests |
| Cancellation stops processing and is not stored as a delivery failure. | catch filters and background loops | Required | Always rethrow `CancellationException`; leave unfinished work open. | Cancellation and restart tests |
| After SFTP success, blank producer email skips notification; configured email queues one best-effort notification per successful server. | SFTP service/tests and Email migration | Required source behavior | Decided: keep the notification (email is not a delivery channel). On delivery success, if the destination has a notification email, enqueue `QueuedEmailReference.ProducerPdfNotification(deliveryId)` via `EmailOutbox` in the same transaction that sets `delivered_at`. | End-to-end delivery + outbox tests |
| The PDF and SFTP test routes have commented-out authorization and no known client. | controllers and consumer search | Approved security deviation | Do not migrate `/api/sftp/test/{orderId}`. Keep any PDF download under the shared admin policy only if an operational consumer is confirmed. | Route absence/auth tests |
| Source stores raw external exception messages and can strand `UPLOADING`. | SFTP entity/service | Incidental defects | Store bounded safe error codes and no in-progress status. Open work remains open until confirmed delivery. | Schema and failure tests |
| Source regenerates from mutable Article layout and image files on later retries. | `PdfService` plus retry flow | Incidental and unsafe for manufacturing consistency | Persist one generated artifact per job so every destination receives identical bytes. | Change source between retries and compare artifact hash |

Apache PDFBox is a reasonable JVM foundation for the renderer: it supports PDF
creation on the JVM and keeps the exact physical layout under Production's
control. This remains a layout spike rather than a claim of QuestPDF API
parity; real source fixtures must be rendered and compared before the library
choice is considered verified.

## Operation contract

| Operation | Required input | Success | Expected failures | Ordering/identity |
| --- | --- | --- | --- | --- |
| Request production | positive Order/source ID within the owning transaction | stable production request ID; repeated identical trigger returns the same ID | invalid ID; persistence failure | one request per order (unique `order_id`); cheap reference-only insert, no source resolution in the caller transaction |
| Split request into jobs (worker) | open production request | one job per distinct supplier among the order's items, plus one delivery row per enabled destination of that supplier | missing/invalid source; item without supplier; supplier without enabled destination | idempotent via unique `(request, supplier)` and `(job, destination)`; failures keep the request open with a safe error code |
| Generate PDF for an authorized download | positive Order/source ID and supplier scope | `application/pdf` bytes and stable file name | source not found; source invalid; rendering failure | source item order must be explicit |
| Process job generation (worker) | production job needing an artifact | one immutable artifact written to disk once, digest recorded | missing/invalid source; image read; PDF render; cancellation | one generation attempt per scan; temp-file write plus atomic rename |
| Process delivery (worker) | delivery row, immutable artifact, destination row | `delivered_at` set after adapter confirmation; producer notification enqueued in the same transaction when configured | disabled destination; connection/auth/transfer failure; cancellation | rows scanned by ascending ID; failures do not block siblings |

No public API should expose adapter DTOs, credentials, remote paths, raw
exceptions, PDF-library types, or Exposed types. Destination admin responses
never contain the SFTP password (write-only field).

## Persistence model

Splitting happens in the worker, not in the trigger transaction: payment
completion has already taken the customer's money, so a routing problem
(item without supplier, supplier without destination) must be a retryable
background failure, never an abort of the payment transaction. The trigger
therefore inserts only a reference — the same shape as `EmailOutbox`.

### `production_requests`

- `id` bigint primary key;
- `order_id` bigint not null unique — one production run per order; reprints
  and complaints become new orders;
- `attempt_count` integer not null default 0 (split/resolution attempts);
- `last_error_code` bounded text nullable;
- `created_at` timestamptz not null; and
- `processed_at` timestamptz nullable — set when all jobs and deliveries have
  been created.

### `production_jobs`

- `id` bigint primary key;
- `request_id` bigint not null, foreign key to `production_requests`;
- `supplier_id` bigint not null, foreign key to `suppliers`;
- unique `(request_id, supplier_id)`;
- `file_name` text not null (producer-facing, `ORD-{orderId}.pdf`);
- `content_sha256` text nullable until generation succeeds;
- `generation_attempt_count` integer not null default 0;
- `last_generation_error_code` bounded text nullable;
- `created_at` timestamptz not null; and
- `generated_at` timestamptz nullable.

The artifact bytes live on the local filesystem under a production-owned
private root at `{jobId}/{file_name}`, written via temp file plus atomic
rename. The digest in the database proves that every destination and retry
received identical bytes and allows an integrity check on load. Worker and
storage share one host — the same deployment assumption the image module
already makes. Artifacts are retained indefinitely for now (see deferred
work).

### `production_destinations`

Admin-managed rows owned by Production, presented in the admin frontend on
the supplier page. Cross-module foreign keys to owned tables are established
practice (`suppliers -> countries`).

- `id` bigint primary key;
- `supplier_id` bigint not null, foreign key to `suppliers`;
- `channel` text not null, check constraint, initially only `SFTP`;
- `label` text not null (operator-facing name, also used in the producer
  notification);
- `enabled` boolean not null default true;
- `host` text not null; `port` integer not null default 22;
- `username` text not null;
- `password` text not null — stored in the database, **write-only** through
  the admin API (never returned by any read endpoint, never logged);
  at-rest encryption is possible later and intentionally not built now;
- `host_key_fingerprint` text not null — pinned host key, verification is
  mandatory for every connection;
- `remote_path` text not null default `/`;
- `timeout_seconds` integer not null;
- `notification_email` text nullable; `notification_name` text nullable;
- `created_at`, `updated_at` timestamptz not null.

A destination referenced by deliveries cannot be hard-deleted (FK restrict);
`enabled = false` is the operational off-switch.

### `production_deliveries`

- `id` bigint primary key;
- `production_job_id` bigint not null, foreign key to `production_jobs`;
- `destination_id` bigint not null, foreign key to `production_destinations`;
- unique `(production_job_id, destination_id)`;
- `attempt_count` integer not null default 0;
- `last_error_code` bounded text nullable;
- `created_at` timestamptz not null; and
- `delivered_at` timestamptz nullable.

The database enforces non-negative counters, known channels, and the unique
identities above. Credentials never appear in job/delivery rows; recipient
addresses live only on the destination row; rendered email bodies, raw
errors, and provider responses are stored nowhere.

## Delivery architecture

`shop.voenix.production.delivery` owns:

- request splitting: resolve the source, group items by supplier, create jobs
  and deliveries from the supplier's enabled destinations at split time
  (a snapshot; later destination changes affect later orders);
- job, delivery, and destination persistence;
- the single worker lifecycle and retry policy (three idempotent scan stages:
  split open requests, generate missing artifacts, attempt open deliveries);
- loading the immutable artifact and verifying its digest;
- adapter selection by channel;
- safe result classification;
- marking only confirmed adapter acceptance as delivered; and
- enqueuing the producer notification through `EmailOutbox` atomically with
  `delivered_at` when the destination configures a notification email.

An internal `ProductionDeliveryAdapter` is the true-external seam. The module
factory receives a list of adapters, rejects duplicate channel registrations,
and builds a channel registry. Adding a channel later (for example real
PDF-by-email) requires an adapter, destination configuration, tests, and a
Flyway check-constraint change; it does not require changing the worker
algorithm.

The SFTP adapter lives under `shop.voenix.production.delivery.sftp`. It owns
SFTP protocol configuration and maps network/protocol outcomes to safe
provider-neutral delivery results. The implementation uses a maintained JVM
SSH/SFTP library and requires host-key verification against the pinned
fingerprint. A local embedded SFTP server must verify authentication, remote
path, timeout, temporary upload/final rename, overwrite/restart behavior, and
cancellation.

External delivery remains at least once. A process can lose power after the
remote system accepted a file but before PostgreSQL records success. A stable
final file name plus temporary upload and rename reduces partial-file risk,
but a producer hotfolder may consume the file before a retry can observe it.

## Email boundary (decided)

Email is not a delivery channel. The boundary is:

- Production owns delivery intent, retry, and truthful state in
  `production_deliveries`.
- On delivery success, Production enqueues
  `QueuedEmailReference.ProducerPdfNotification(deliveryId)` via the public
  `EmailOutbox` inside the transaction that sets `delivered_at`. The
  reference key changes from the legacy upload-task ID to the production
  delivery ID.
- Email owns rendering, provider settings, and the retry state of the
  notification itself (`email_jobs`), exactly as for other queued emails.
  Exactly one module owns retries for one external send: Production for the
  file transfer, Email for the notification mail.
- Production implements the resolution of `ProducerPdfNotification`
  references (it knows delivery, destination label, recipient, order and
  item data via its source) and exposes that as a capability the app wires
  into the `QueuedEmailSource` given to `installEmailModule`.

Composition note: `installEmailModule` needs a `QueuedEmailSource` while
Production needs the returned `EmailOutbox`, so the app composes a small
late-bound aggregate source (app-owned) that delegates
`ProducerPdfNotification` to Production once it is installed. This is a
wiring-order concern only; compile-time dependency stays
`production -> email -> platform` with no cycle.

## Planned module interface and type map

The final names may be shortened during implementation, but every planned type
currently carries a distinct responsibility.

| Package/type | Visibility | Responsibility |
| --- | --- | --- |
| `production.ProductionModule` | public | Runtime handle exposing PDF generation, the durable request capability, and the producer-notification resolver while owning the worker lifecycle |
| `production.createProductionModule` | internal | Assemble source, generator, repositories, adapters, and worker for tests |
| `production.installProductionModule` | public | Build real adapters, install routes/worker, and register shutdown |
| `production.ProductionOutbox` | public interface | Join the caller transaction and insert one durable production request per order |
| `production.ProductionPdfGenerator` | public interface | Generate an authorized on-demand PDF without exposing renderer internals |
| `production.ProductionSource` | public interface | Resolve the immutable Order/item/image inputs Production actually needs, including each item's supplier |
| `production.ProductionData` | public data class | Process-only source values shared across the future Order/Production boundary |
| `production.ProductionItem` | public data class | One logical line plus quantity, supplier identity, and production-layout/image data |
| `production.pdf.ProductionPdfRenderer` | internal class | Expand quantities and create the physical PDF artifact for one supplier's items |
| `production.pdf.ProductionPdf` | internal data class | File name, media type, bytes, and digest passed to persistence/delivery |
| `production.pdf.ProductionArtifactStore` | internal class | Filesystem persistence of artifacts (temp write, atomic rename, digest verification on load) |
| `production.delivery.ProductionRequestRepository` | internal class | Persist requests, split attempts, and processed state |
| `production.delivery.ProductionJobRepository` | internal class | Persist jobs, generation attempts, artifact metadata, and lookup |
| `production.delivery.ProductionDeliveryRepository` | internal class | Persist deliveries, attempts, safe errors, and delivered timestamps |
| `production.delivery.ProductionDestinationRepository` | internal class | Persist admin-managed destinations; password handled write-only |
| `production.delivery.ProductionWorker` | internal class | Coordinate the split, generation, and delivery scans |
| `production.delivery.ProductionDeliveryAdapter` | internal interface | Channel-neutral external delivery seam |
| `production.delivery.ProductionDeliveryResult` | internal sealed interface | Accepted or safe failed result |
| `production.delivery.sftp.SftpProductionDelivery` | internal class | SFTP adapter with mandatory host-key verification and atomic-file strategy |
| `production.DestinationRoutes` (working name) | internal | Admin CRUD routes for destinations under the shared admin policy; responses never include the password |

The public `ProductionSource` data types are justified cross-module contract
models, not copies of future Order entities. Production must not query another
module's internal tables or import its entities. The supplier FK targets the
supplier module's table at schema level only; Production never queries it.

## Runtime composition and Flyway changes

- Register `modules/production` in `backend/project.yaml` and add it to the
  app dependency graph (`production -> platform, email, supplier?` — the
  supplier dependency is schema-only and needs no compile dependency).
- Add Production's Exposed, PDF, SSH/SFTP, and Ktor dependencies as actually
  required to `libs.versions.toml`.
- Add the four Flyway tables to the platform-owned global migration chain
  (next free versions).
- Keep the application from installing Production until a real
  `ProductionSource` exists. Standalone module tests use an in-memory source,
  not a production placeholder.
- When Order is migrated, call `ProductionOutbox.request(orderId)` inside the
  durable business-trigger transaction. A database rollback must leave no
  production request.
- Install one active Production worker initially. Do not extract Email and
  Production into a generic job framework merely because both poll tables.

## Test plan

| Test area | Required coverage |
| --- | --- |
| Source mapping | Explicit item order, quantity expansion, all shipping and mug-layout fields, readable/missing images, invalid dimensions, overflow, Unicode |
| Supplier splitting | Single-supplier order, multi-supplier order (items partitioned correctly, per-job numbering restarts), item without supplier keeps request open with safe error, supplier without enabled destination keeps request open, idempotent re-split after partial failure |
| PDF renderer | PDF magic bytes, exact page count and millimetre dimensions, visible address/item text, rotation, image placement, blank optional supplier number, real-fixture rendered image comparison |
| Production outbox | Caller-transaction commit/rollback, repeated and concurrent request identity, no stored credentials/personal content |
| Generation worker | Generate once per job, temp-write/rename atomicity, stable bytes/hash, retry source/render failures, cancellation remains open, changed source after generation does not change artifact |
| Delivery worker | Stable ID order, independent sibling failures, one attempt per scan, restart recovery, disabled destination, accepted timestamp, notification enqueued atomically with `delivered_at`, no notification without configured email, unbounded attempts |
| Destinations admin | CRUD under shared admin policy, password write-only (absent from every response), validation (host key required, email format), FK restrict on referenced destinations, enable/disable behavior |
| SFTP adapter | Host-key verification (reject mismatch), password authentication, exact path/name, temporary upload and rename, connection/auth/transfer failure classification, timeouts, cancellation, server compatibility fixture |
| Schema | Empty-database Flyway, foreign keys, unique request/job/delivery identities, channel/counter checks, bounded safe errors |
| Runtime | One worker, narrow capabilities, no duplicate adapter registration, clean cancellation and adapter/client close on application stop |
| Routes | Shared admin authentication and absence of the legacy unauthenticated SFTP test route |

Tests use PostgreSQL/Testcontainers where transaction, uniqueness, and
concurrency semantics matter. PDF layout verification renders fixture PDFs to
images; whole-file byte equality is not used as a renderer correctness test.

## Decision record (resolved 2026-07-22 with Joe)

| Decision | Resolution |
| --- | --- |
| Meaning of email | Email stays a post-delivery notification without attachment; it is not a delivery channel. The adapter seam remains so future channels (PDF-by-email or others) can be added, but nothing is built for them now. |
| Artifact persistence | Generate once per job (single source of truth), store on the local filesystem under a job-scoped path; database keeps metadata plus SHA-256 digest. Not stored as `bytea`. |
| Artifact retention | Keep indefinitely for now; explicit cleanup is deferred operational work. |
| Trigger and identity | Exactly one production request per order (unique `order_id`). Reprints/complaints become new orders. Splitting into per-supplier jobs happens in the worker, never in the payment transaction. |
| Destination routing | Routing hangs off the producer (supplier == producer). Destinations are admin-managed database rows owned by Production with a supplier FK, presented on the supplier page in the admin frontend. DB-first: no config-based interim step, no hybrid, no legacy compatibility layer. |
| Multi-supplier orders | Supported from the start (a second supplier is imminent). One job per supplier per order; each supplier's PDF contains only its items. |
| Credentials | SFTP password stored in the database, write-only through the admin API, never in responses or logs. No config hybrid. At-rest encryption may come later. |

### Adopted engineering defaults

Adopted without an explicit veto opportunity; raise objections before or
during implementation:

- Missing production image fails generation with a retryable error instead of
  rendering a blank item page.
- Retry cadence: one attempt per non-overlapping poll, no maximum initially,
  with safe error codes and attempt counts.
- The legacy SFTP test route is not migrated; any PDF download stays behind
  the shared admin policy.
- SFTP host-key verification against a pinned fingerprint is mandatory; no
  permissive fallback.
- No `UPLOADING`-style in-progress status; open/complete state derives from
  nullable timestamps.
- Remote file name stays `ORD-{orderId}.pdf` (unique per destination because
  suppliers only receive their own jobs); disk paths are job-scoped.

## Deviation and uncertainty log

| Behavior or contract | Source evidence | Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| Producer email is post-SFTP notification, not PDF delivery | SFTP service, Email template and tests | Notification kept, enqueued atomically with `delivered_at`, keyed by delivery ID | Decided | Joe — 2026-07-22 | Adjust Email module reference payload to delivery ID |
| Source regenerates mutable PDF on each retry | PDF/SFTP services | Persist generated artifact once on disk with digest | Decided correctness deviation | Joe — 2026-07-22 | Retention/cleanup deferred |
| Source creates tasks for all enabled servers on paid | Paid processor/tests | Per-supplier routing from DB destinations; per-order request, worker-side split | Decided product behavior | Joe — 2026-07-22 | Order migration calls `ProductionOutbox.request` |
| Static config destinations | `SftpOptions` | Admin-managed DB destinations, write-only password | Decided; legacy config shape not migrated | Joe — 2026-07-22 | Admin frontend UI later |
| Missing image renders a blank item page | `PdfDocument` | Retryable generation failure | Adopted default | Engineering default — veto possible | Approve before renderer implementation is final |
| Three immediate retries | SFTP service | One attempt per worker scan, open indefinitely | Adopted default | Engineering default — veto possible | Revisit with operational alerting needs |
| `UPLOADING` may strand and raw messages persist | entity/service | No in-progress state; bounded safe error | Reliability/security correction | Engineering decision | Schema and restart tests |
| SFTP does not verify host identity | `SftpClientFactory` | Required pinned host-key verification | Security correction | Engineering decision | Destination validation and connection tests |
| Unauthenticated SFTP/PDF development routes | controllers, no consumer found | Remove SFTP test route; admin-protect PDF if retained | Adopted security default | Engineering default — veto possible | Confirm operational PDF download need |
| No unique source/server rule or Order foreign key | legacy migration | Database-enforced request/job/delivery identities; order FK deferred until Order schema exists | Integrity correction/deferred relationship | Production and future Order migrations | PostgreSQL concurrency tests |
| One active worker only | current Kotlin Email deployment model; no multi-instance requirement | One active Production worker, no lease initially | Engineering default | Revisit with deployment scaling | Do not extract shared queue framework yet |

## Implementation

The analysis checkpoint is approved. Implement the module, tests, Flyway
migrations, and beginner-oriented documentation under `docs/dev/backend`,
keeping this record current as decisions refine.

Do not create a Git commit unless explicitly requested.

## Migration retrospective

Complete this table after implementation, verification, simplification, and
comparison with this analysis.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| Pending until implementation | Implementation has not started | Production migration | Complete implementation and simplification review | Keep in this record |
