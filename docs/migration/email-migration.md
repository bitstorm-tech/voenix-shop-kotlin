# Email module migration

This file is the module-specific task brief and analysis record for migrating
the Email source feature from the .NET backend. It supplements the canonical
[`module-migration-guide.md`](module-migration-guide.md); it does not replace
the guide.

## Task parameters

- Target module: `Email`
- Source project: `/Users/joe/projects/joto-ai/voenix-shop`
- Source feature: `backend/Voenix.Api/Features/Email`
- Target project: `/Users/joe/projects/joto-ai/voenix-shop-kotlin`
- Target package: `backend/modules/email/src/shop/voenix/email`
- Analysis checkpoint: `approved-for-implementation — 2026-07-16`
- Implementation status: `completed and verified — 2026-07-16`

Known consumers:

- Auth directly sends five account emails and owns required versus best-effort
  failure handling.
- `PaidOrderProcessor` currently enqueues an Order confirmation after payment;
  this trigger contradicts the expected product behavior and is deferred.
- SFTP enqueues a producer notification after a successful upload.
- The development Email controller exposes two manual routes, but neither has
  a frontend or Kotlin consumer.

The approved target preserves five direct, non-persistent Auth emails and two
durable email kinds. Durable delivery is reference-only, periodically retried,
and explicitly at least once. The complete approved decision set is recorded
once under [Approved decisions](#approved-decisions); deviations remain in the
[Deviation and uncertainty log](#deviation-and-uncertainty-log).

The Order-confirmation trigger, broader notification inventory, and future
Auth/Order/SFTP/Admin composition are explicitly deferred to
[`email-post-migration.md`](email-post-migration.md) and do not block the Email
module.

## Required instructions and sources

Follow [`module-migration-guide.md`](module-migration-guide.md) and the
applicable repository instructions. Email-specific source evidence is:

- `Voenix.Api/Features/Email` and `Voenix.Api.Tests/Features/Email`;
- `SweegoOptions`, its validator, startup registration, error mapping, EF
  mapping, and `20260326223044_AddEmailTasks.cs`;
- Auth, `PaidOrderProcessor`, SFTP upload, and their meaningful tests; and
- source-frontend and Kotlin searches for `/api/emails` consumers.

Relevant external contracts were checked against official
[Ktor client/timeout documentation](https://ktor.io/docs/client-timeout.html),
[FreeMarker documentation](https://freemarker.apache.org/docs/dgui_quickstart_template.html),
[Sweego authentication](https://learn.sweego.io/docs/auth/api_keys),
[send/dry-run](https://www.sweego.io/send-email-sms-api-smtp),
[campaign tracking](https://learn.sweego.io/docs/sending/how_to_send_sms_by_api),
[delivery events](https://learn.sweego.io/docs/webhooks/email_events), and
[API errors](https://learn.sweego.io/docs/api-error-codes), plus RFC 9110
`Retry-After` semantics. General Kotlin, persistence, testing, and completion
rules are intentionally not repeated here.

## Migration boundary

The standalone Email migration should include:

- a product-owned Email module with separate direct-send and durable-outbox
  interfaces so callers cannot accidentally choose the wrong delivery model;
- the seven current transactional email kinds and their German HTML and plain-
  text content;
- a FreeMarker renderer with classpath resources and a shared email layout;
- a Sweego delivery adapter built on one reusable Ktor `HttpClient`;
- a PostgreSQL-backed worker with claim leases, persistent retry scheduling,
  concurrent-worker safety, current-data resolution, and graceful
  cancellation;
- a clean Flyway `email_jobs` table and Exposed mapping;
- typed startup configuration with one safe-default `enabled` switch;
- an exported `UserEmailSender` for the five non-persistent user-interaction
  emails;
- an exported `EmailOutbox` for order confirmations and producer PDF
  notifications that later product modules can call inside their own database
  transaction; and
- an exported `QueuedEmailSource` seam through which the application resolves
  current Order/SFTP data for a claimed business reference before rendering.

This migration must not add placeholder User, Order, Payment, SFTP, or PDF
tables or migrate those modules' routes. It must not add a generic platform job
framework on the evidence of one module. A second durable-job migration may
justify extracting shared mechanics later.

The Email module will not own frontend URL construction, auth tokens, paid-
order transitions, order persistence, SFTP configuration, or upload lifecycle.
Those concerns remain with their producing modules, which expose current data
through `QueuedEmailSource`. Email owns templates, rendering, direct user-email
dispatch, durable background dispatch, provider integration, and delivery
state.

## Source analysis checkpoint — 2026-07-15

### Architectural finding

The source feature combines three distinct responsibilities in one
`EmailService`:

1. synchronous Auth notification rendering and provider delivery;
2. Order and producer-email projection from mutable Order/SFTP state; and
3. a partially durable background queue for those Order-related emails.

The persisted source task is not a complete outbox message. It stores an Order
ID, recipient, subject, type, and retry state, then re-reads Order and SFTP
configuration while processing. The in-memory channel is only a wake-up path,
but startup recovery ignores tasks left in `SENDING`. There is no multi-instance
claim, uniqueness rule, provider idempotency contract, or atomic write with the
paid-order transaction. A crash can therefore lose a task, and a crash after
provider acceptance but before `TRANSMITTED` can cause a duplicate when recovery
is made correct.

ADR 0001 rejects in-memory-only background work and requires durable,
idempotent external writes. The Kotlin design therefore cannot faithfully copy
the source worker mechanics. The two background email kinds need a durable
outbox with explicit at-least-once semantics and a stable provider correlation
identity. The five user-interaction email kinds preserve the source's direct,
non-persistent delivery model.

### Behavior, evidence, classification, Kotlin approach, and verification

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| Disabled configuration accepts no provider credentials; enabled requires API key and sender. Effective sender name is `Voenix Shop`; frontend URL is only used by Auth. | `SweegoOptions`, validator, startup tests | Required; URL ownership deviation | `EmailSettings` defaults to disabled, validates credentials only when enabled, and validates polling 1–1,440 minutes (default 5). Future Auth owns frontend URL. | Settings/YAML tests; Auth follow-up |
| Normal sends POST recipient, sender, subject, HTML, and text to Sweego with `Api-Key`, `channel=email`, and `provider=sweego`; response DTO fields serve only the removed test route. | `SweegoClient`, DTOs, official Sweego docs | Required wire behavior; response DTO incidental | Internal Ktor adapter preserves the request contract; HTTP success is acceptance and provider DTOs do not escape. | Exact request and empty/success response tests |
| .NET inherits a 100-second timeout. | Client registration and .NET docs | Exact source duration incidental; bounded wait required | Ktor request/connect/socket timeouts are 30/10/30 seconds. | Adapter timeout and future Auth response-bound tests |
| Source exceptions/logs can expose provider bodies and recipient addresses. | `SweegoClient`, `EmailService`, error mapping | Security/privacy deviation | Persist/log only bounded safe codes; never recipient, subject, body, token URL, key, or raw response. | Adapter, worker, and capturing-logger redaction tests |
| Provider failure maps to `502`, rendering to `500`; caller cancellation propagates while Auth owns optional notification policy. | Source exceptions, handler, Auth and cancellation tests | Required distinction; hierarchy incidental | Public secret-free `EmailDeliveryException` represents unconfirmed acceptance; programming/rendering failure stays unexpected; cancellation is rethrown. | Direct sender, worker cancellation, and future Auth mapping tests |
| Seven variants use fixed German subjects, separate HTML/text, and one branded inline-styled layout. | Razor templates, layout, subject literals, text builders | Required content; Razor syntax/whitespace incidental | UTF-8 FreeMarker resources preserve wording, dynamic information, layout, and independent text. | Seven golden fixtures, escaping, subject, and renderer-cache tests |
| Duplicate source model fields cannot differ at call sites. | Change-email and Order models/renderers | Incidental representation | Change-email derives displayed address from recipient; Order has one current recipient. | Constructor shape and fixtures |
| Only Order confirmation and producer PDF notification use `EmailTask`; five Auth variants send directly. | `EmailType`, send/enqueue methods | Required persistence boundary, approved | Only two `QueuedEmailReference` variants enter `EmailOutbox`; five `UserEmail` variants never touch `email_jobs`. | Interface, sender, and outbox tests |
| Auth owns confirmation/resend, reset enumeration safety, required change-email confirmation, and best-effort warnings. | Auth controller/tests | Required, consumer-owned | Email exposes five direct variants; resend is a new send, URL construction and failure impact stay in Auth. | Renderer/sender tests and future Auth tests |
| Source has one `Enabled` flag; disabled direct sends no-op, but queued delivery incorrectly marks `SENT`. | Options and send paths | Required switch; queued state defect | Keep one boolean. Disabled direct sends create no row and worker claims nothing; jobs remain `PENDING`. Enabled acceptance becomes `TRANSMITTED`; no runtime dry-run. | Settings, no-op, pause/restart, and live tests |
| Producer inputs validate recipients; Auth token URLs remain process-only. | Request DTOs, Auth/Checkout/SFTP validation | Required seam/security invariant | `EmailRecipient` validates before send; `EmailActionUrl` is validated and redacted. Producer validation remains producer-owned. | Value tests and no-job/no-log token tests |
| Order enqueue snapshots recipient/subject and wakes an in-memory channel; processing re-reads mutable Order data. | Order enqueue/process methods | Required durable intent and send-time data; mechanics incidental | Store stable Order reference/idempotency only; database is queue; resolve current data/template each attempt. Missing/invalid source increments retry count. | Outbox and changed/missing-source worker tests |
| Source triggers confirmation only after `PaidOrderProcessor` commits `PAID`, contrary to expected product behavior. | Paid processor, Payment/Checkout call sites/tests | Material product contradiction, deferred | Wire no Order producer. Future notification story decides trigger, owner, transaction, and idempotency. | Future story and trigger integration tests |
| Order templates format German date/money, zero shipping, totals, addresses, and different HTML/text line detail; source uses UTC calendar date. | Order renderers/fixtures and timestamp schema | Required content; date correction approved | Preserve presentation and use Order-supplied `Europe/Berlin` `LocalDate`; define authoritative item order. | Golden price/address/order and midnight-boundary fixtures |
| Source Order arithmetic can overflow `int`. | `SendOrderConfirmationAsync` | Incidental unsafe edge | Use checked integer-cent arithmetic consistent with Pricing; abort rather than render a false amount. | Arithmetic boundary tests |
| Producer notification follows successful configured SFTP upload; failures are best effort and task ID identifies the send. | `SftpUploadService`, process method/tests | Required, SFTP-owned | Future SFTP supplies stable upload-task reference/key after success and owns recoverability. | Future SFTP and multiple-job tests |
| Producer rendering re-resolves configuration by recipient; quantity sums item quantities; blank name can render badly. | Producer send method/templates/validator | Required current-data intent; ambiguous lookup/name defect | Resolve current server/recipient/name by stable upload task, normalize blank name absent, require server name, and expose total quantity. | Changed-recipient, same-email-server, greeting, and quantity tests |
| Source retries three in-process calls, caps attempts, retries every exception, and has incomplete restart recovery. | Retry delays, worker, tests | Retry/recovery intent required; policy replaced by approval | Periodic database scheduler drains due jobs; every failure returns to `PENDING` and increments an unbounded counter; valid `Retry-After` may defer. | Cadence, batch, failure, counter, restart tests |
| `SENDING` can be stranded; source has no business uniqueness or multi-worker lock. | Worker, EF schema/queries | Incidental defects/limits | Expiring `PROCESSING` lease, unique hashed business key, and `FOR UPDATE SKIP LOCKED`. | Lease, concurrent enqueue, and multi-worker tests |
| Manual non-production routes are unauthenticated and have no consumer. | Controller/tests and repository searches | Observable/security deviation, approved | Migrate no Email routes; use adapter/renderer tests and authorized dry-run smoke test. Future resend is an authenticated owner command. | Absence and adapter tests |
| Source table stores recipient/subject/error indefinitely but no body; Auth tokens remain process-only. | EF mapping/migration, Auth paths | Lifecycle required; content persistence rejected | `email_jobs` stores reference, hashes, state, retry schedule, and bounded error only; no message/recipient/token data or automatic cleanup. | Flyway schema, no-content, retention tests |
| Provider acceptance and local completion have a crash window; `campaign-id` is tracking, not documented deduplication. | Worker/port and Sweego docs | Material ambiguity, approved | Document at-least-once only; use stable campaign correlation without claiming provider idempotency. | Crash-window seam test and operations docs |

### Planned external module interface

The interface makes direct and durable delivery impossible to confuse while
keeping rendering, provider JSON, persistence, retries, and lifecycle internal.

```kotlin
public interface UserEmailSender {
    public suspend fun send(email: UserEmail)
}

public interface EmailOutbox {
    public suspend fun enqueue(
        idempotencyKey: String,
        reference: QueuedEmailReference,
    ): Long
}

public interface QueuedEmailSource {
    public suspend fun resolve(reference: QueuedEmailReference): QueuedEmail?
}
```

`UserEmail` contains the five direct variants `AccountConfirmation`,
`ChangeEmailConfirmation`, `PasswordReset`, `PasswordChangedNotification`, and
`ChangeEmailNotification`. `QueuedEmailReference` contains only stable,
non-secret identifiers for `OrderConfirmation(orderId)` and
`ProducerPdfNotification(uploadTaskId)`. `QueuedEmail` contains the matching
process-only template models resolved for one attempt.

Only `QueuedEmailReference` can enter the database. This type split prevents
Auth email persistence and prevents durable notifications from bypassing the
worker.

The three link-bearing variants accept `EmailActionUrl`, not a raw string. It
holds a complete, already percent-encoded absolute `http` or `https` URI, has a
nonblank host, rejects user-info and control characters, and is bounded to
8,192 characters. `http` remains allowed for local development; production
Auth configuration decides whether only `https` is valid. The class overrides
`toString()` with a redacted value so confirmation/reset tokens do not leak
through an accidental object log. Email renders the explicit underlying value
but never persists or logs it.

For every attempt, `QueuedEmailSource` resolves the current recipient and
placeholder values and Email renders the current templates. No resolved or
rendered message data is persisted. Address, source-data, and template changes
therefore affect the next attempt while the business event and campaign ID stay
stable. After an ambiguous accepted attempt, a retry can reach both an old and
a current address; this is part of the approved at-least-once trade-off.

Resolved models contain only provider-neutral values. Order owns authoritative
business values and `Europe/Berlin` calendar-date conversion; Email owns German
presentation. No Order, SFTP, Exposed, or Ktor type crosses the seam.

Shared values such as `EmailRecipient` enforce the module's safety invariants:
recipient addresses are trimmed, at most 255 characters, contain exactly one
`@` with nonempty local and domain parts, and contain no whitespace or control
characters. This deliberately matches the repository's existing simple
supplier-email shape check instead of introducing a second mail-address parser.
Sender configuration uses the same invariant. Display values are bounded to
255 characters and free from control characters where relevant, counts and
money fields satisfy the producer contract, and queued idempotency keys are
nonblank, bounded, namespaced business-event identities without PII or secret
tokens. These checks guard the internal seam; public request
validation and localized field errors remain with the producing module.

The producer supplies a non-PII business-event idempotency key because only it
knows event identity. The trimmed, 200-character key is namespaced and
versioned, for example `order:confirmation:v1:{orderId}`. Email persists only
SHA-256 digests:

- `idempotency_hash` hashes `email-idempotency:v1\0` plus the normalized key;
- `intent_hash` hashes a hand-authored, length-prefixed
  `email-intent:v1\0` representation of kind and source ID.

JSON, `toString()`, resolved data, and rendered output are never hash inputs.
A duplicate key with the same intent returns the existing job ID; a different
intent is an integration error. Golden fixtures protect the encoding version.
Hashing reduces accidental disclosure but is not encryption or anonymization.
Direct sends have no key or automatic retry; a UI resend is a new send.

Neither interface returns `OperationResult`. Direct send either completes after
Sweego accepts a live request, is a disabled no-op, throws the secret-free
`EmailDeliveryException`, or propagates an unexpected programming/rendering
failure. The exception has an internal constructor and exposes no recipient,
content, credentials, provider body, or DTO.

`EmailOutbox.enqueue` joins an existing Exposed transaction and never commits
independently. Expected duplicates succeed; other persistence failures abort
the producer transaction. Resolution happens later and failures become retry
cycles. The future notification story owns the Order trigger and SFTP's
recoverable boundary with the non-transactional external upload.

### Email variant and content contract

| Variant | Required producer input | Exact subject | Required dynamic content | Failure/ordering owner |
| --- | --- | --- | --- | --- |
| Account confirmation | recipient and complete confirmation URL | `Bitte bestätige deine E-Mail-Adresse` | Registration thanks, activation button and fallback URL, 24-hour statement, resend instruction, ignore-if-unrequested notice | Auth owns required failure handling; user can request another send |
| Change-email confirmation | new recipient and complete confirmation URL | `Bitte bestätige deine neue E-Mail-Adresse` | New address is derived from recipient, confirmation button and fallback URL, 24-hour statement, unchanged-address safety notice | Auth owns required failure handling; user can request another send |
| Password reset | recipient and complete reset URL | `Setze dein Passwort zurück` | Reset request, button and fallback URL, deliberately unspecified limited lifetime, ignore-if-unrequested notice | Auth owns enumeration-safe required handling; user can request another send |
| Password changed | recipient | `Dein Passwort wurde geändert` | Success statement, urgent contact warning, automatic-notification footer in HTML | Auth treats notification as best effort |
| Change-email notification | old recipient and new email | `Änderung deiner E-Mail-Adresse angefordert` | Requested new address, old-address unlink consequence, urgent password-change warning, automatic-notification footer in HTML | Auth treats warning as best effort; confirmation and warning may run in parallel |
| Order confirmation | stable Order ID at enqueue; current recipient, Order calendar date, shipping name, addresses, ordered item values, shipping, and total cents at processing time | `Bestellbestätigung #{orderId}` | Greeting, shipping-follow-up notice, ID/date, ordered lines, subtotal/shipping/total, both addresses; plain text additionally exposes unit price | Trigger/transaction owner is deferred to the notification story; Order owns current-data resolution and authoritative item order |
| Producer PDF notification | stable upload-task ID at enqueue; current recipient/producer name plus upload filename/server, Order date, and item quantity at processing time | `Neue Bestellung #{orderId} – {filename}` | Personal or formal greeting, SFTP upload statement, ID, filename, server, date, total quantity, support footer | SFTP owns recoverable best-effort reference enqueue and current-data resolution after successful upload |

All HTML variants preserve the common German `Voenix Shop` header/footer and
meaningful inline styling. Full Razor-generated whitespace and comments are not
contractual. Golden fixtures compare normalized meaningful markup and exact
plain text. The two confirmation templates' 24-hour statements remain
provisional until the future Kotlin Auth migration defines the corresponding
token lifetimes; Email must not invent or own token expiry.

### Delivery and state contract

| Operation | Input | Success | Failure | Concurrency |
| --- | --- | --- | --- | --- |
| Direct send | typed `UserEmail` and current `enabled` switch | live request accepted or disabled no-op; no job exists | public safe `EmailDeliveryException`; unexpected render/program failure; caller cancellation | one send per invocation; no automatic retry |
| Enqueue | unique business idempotency key and typed `QueuedEmailReference` | new or existing job ID | unexpected persistence failure; mismatched duplicate key/reference | unique database rule, transaction-composable |
| Claim | current database time, `enabled`, and lease duration | one eligible job reference when enabled | no job available or delivery disabled | `FOR UPDATE SKIP LOCKED`; expired leases are eligible |
| Resolve and render | claimed typed reference plus current producer data | one process-only provider-ready message using the current recipient, placeholder values, and templates | missing/invalid/unavailable source or render failure | outside the claim transaction; failure follows the same retry-counter path |
| Deliver | process-only rendered message and stable provider correlation ID | live request accepted | typed safe failure with optional `Retry-After` | outside the claim transaction |
| Complete | claimed job and lease identity | `TRANSMITTED`; no message content to scrub | stale lease update affects no row | conditional state transition |
| Record failure | claimed job, safe failure, and optional valid `Retry-After` | `PENDING`, incremented retry counter, cleared lease, and future `next_attempt_at` | stale lease update affects no row | conditional state transition; no in-process sleep |

The worker provides at-least-once delivery only for the two `QueuedEmail`
variants. Database idempotency prevents duplicate jobs and concurrent claims,
but it cannot prove exactly-once external delivery when the provider accepts a
request and the process stops before the database completion write. Sweego's
current public `/send` request exposes no idempotency key, describes
`campaign-id` as a tracking identifier, and does not document duplicate
suppression for that field or custom headers. Exactly-once delivery therefore
remains unprovable without a separate contractual provider guarantee.

### Rendering and provider design

Email uses FreeMarker directly, without Ktor server templating. Classpath
templates share a layout macro and enable UTF-8 plus HTML auto-escaping. Golden
fixtures cover all seven HTML/text variants, escaping, links, and shared layout;
Razor whitespace is not contractual.

The Sweego adapter uses one module-owned Ktor CIO client, fixes the destination
to `https://api.sweego.io/send`, disables Ktor's default redirect following,
and sends:

- `channel: "email"`;
- `provider: "sweego"`;
- `campaign-type: "transac"`;
- one recipient and the configured sender;
- both `message-html` and `message-txt`;
- for queued delivery, a stable `campaign-id` derived from the Email job ID.

Redirects fail without forwarding `Api-Key`. With `expectSuccess = false`,
Email classifies status and valid `Retry-After`; it drains but never parses,
logs, returns, or persists the response body. No request/body logging plugin or
provider response DTO is used.

Queued sends use stable, non-PII `campaign-id: voenix-email-<job-id>` only for
correlation. Sweego documents no duplicate suppression, so no exactly-once
claim follows. Direct sends omit the field; a custom `Message-ID` adds no
documented value.

Operational logs use only the Email job ID, email kind, retry count,
lifecycle transition, and a bounded provider-neutral error code. They never
contain recipient addresses or names, subjects, rendered content, Auth token
URLs, API keys, or raw provider bodies. Direct user-email logs therefore record
only the variant and outcome. Order and SFTP may log their own event identity
according to their module's policy, but Email does not duplicate that business
identifier into its logs.

Ktor `HttpTimeout` uses named adapter defaults of 30 seconds request, 10 seconds
connect, and 30 seconds socket time. They intentionally replace .NET's inherited
100-second request wait and remain non-configurable until measurements justify
deployment settings.

`UserEmailSender.send` renders and calls the same adapter once when enabled,
without creating a database job or scheduling a retry. A successful HTTP
response means only that Sweego accepted the request, not that the recipient's
mailbox received it. If a direct request has an ambiguous timeout, a later UI
resend may create a duplicate; this is accepted as part of the source-compatible
direct model. A Ktor request, connect, or socket timeout is reported to Auth as
a delivery failure after at most the configured request budget; Auth still
decides whether that particular email is required or best effort. Caller
cancellation remains distinct and is always rethrown.

When `enabled` is false, direct sends return before rendering/provider access
and the worker claims no jobs. Durable intents are still inserted as `PENDING`,
so they resume after an enabled restart. Runtime has no dry-run mode; an
explicitly authorized Sweego dry-run is a separate smoke test.

### Persistence and worker design

Flyway `V5__create_email_jobs.sql` should create `email_jobs` with:

- identity `bigint` primary key;
- unique `bytea` SHA-256 `idempotency_hash` and a separate versioned SHA-256
  `intent_hash`, each constrained to exactly 32 bytes;
- bounded text `email_kind`, one positive `bigint source_id`, and `status`
  columns with the value checks defined below;
- checked status values `PENDING`, `PROCESSING`, and `TRANSMITTED`;
- nonnegative `integer retry_count`, `timestamptz next_attempt_at`, nullable UUID lease
  token and `timestamptz` lease expiry, nullable `varchar(64)` safe error code,
  nullable `varchar(512)` safe error message, and `timestamptz`
  created/updated/completed timestamps;
- a claim index ordered by status, `next_attempt_at`, and ID;
- a lease-recovery index for expired `PROCESSING` jobs; and
- an operations index supporting pending jobs ordered by retry count for the
  future Admin alert query.

No Order/SFTP foreign key or placeholder table is added because those schemas
do not yet exist and the two reference kinds point to different owning tables.
`email_kind` determines whether `source_id` is an Order ID or durable SFTP
upload-task ID. The Email table never stores recipient addresses or names,
subjects, template values, HTML, or plain text in any state. Disabled delivery
leaves jobs `PENDING`; there is no terminal disabled state. `TRANSMITTED` means
only that Sweego accepted a live request. User-email bodies and token links are
likewise never persisted.

Flyway expresses the state machine as checks rather than relying only on worker
code:

- every row has a valid kind and positive source ID;
- `PENDING` has no lease or completion timestamp;
- `PROCESSING` has a nonblank lease token and lease expiry, with no completion
  timestamp;
- `TRANSMITTED` has `completed_at`, no lease, and no error;
- a retried `PENDING` row may retain the preceding bounded safe error for
  operations, and the next success clears it;
- `retry_count` is nonnegative in every state, starts at zero, increments once
  for each unsuccessful processing cycle or recovered ambiguous lease, and has
  no automatic maximum.

Conditional updates still include status and lease token in their predicate;
database checks prevent impossible shapes, while affected-row counts prevent a
stale worker from completing or rescheduling a reclaimed job.

The database table is the queue; no external broker is needed. One
application-lifecycle scheduler starts a scan every configurable interval,
defaulting to five minutes. It claims due `PENDING` rows in batches of at most
100 with `FOR UPDATE SKIP LOCKED`, resolves and renders each claimed job outside
the claim transaction, delivers it, and keeps claiming batches until no due row
remains. One worker does not overlap its own scans; multiple application
instances can safely share the work through leases and row locking. A newly
enqueued durable email can therefore wait up to one scheduler interval before
its first attempt.

Every unsuccessful queued processing cycle returns the job to `PENDING`,
clears its lease, increments `retry_count` exactly once, stores a bounded safe
error code/message, and sets `next_attempt_at` to the next scheduler interval.
There is deliberately no maximum retry count and no terminal `FAILED` state.
This includes missing or invalid current source data, source-read and rendering
failures, provider `4xx`/`5xx`, transport and TLS failures, timeouts,
serialization failures, and other exceptions after a valid reference job
exists. Source and rendering failures receive bounded provider-neutral codes
such as `SOURCE_NOT_FOUND`, `SOURCE_INVALID`, or `RENDERING_FAILED`; no resolved
content enters the error text. Startup configuration validation remains
fail-fast because a non-running application cannot reliably update individual
jobs. Caller cancellation stops the worker and is handled by lease recovery
rather than swallowed as a normal result.

A valid `Retry-After` on `429` or `503` may move `next_attempt_at` later than
the normal scheduler interval, capped at 24 hours. The next scan still visits
the table, but the row is not due until that timestamp. This respects provider
back-pressure without changing Joe's rule that every unsuccessful job remains
retryable.

An expired `PROCESSING` lease is an ambiguous failed cycle: the process may
have stopped before the call, during it, or after Sweego accepted it. Recovery
increments `retry_count`, records a safe `AMBIGUOUS_PROCESS_LOSS` error, and
returns the row to `PENDING` for the next interval. This conservative counter
is an operational signal, not proof that Sweego rejected or failed to deliver
the message. Retrying after provider acceptance can produce the rare duplicate
approved by Joe on 2026-07-16.

The future Admin alert selects only nonterminal jobs with `retry_count` greater
than its configurable threshold. A job that eventually reaches `TRANSMITTED`
is no longer alert-worthy even though its historical retry count is retained. The
standalone Email migration stores and indexes the counter; the dashboard, alert
delivery, threshold ownership, and any future cancel/edit workflow belong to
the Admin/operations migration.

Direct `UserEmail` sends remain outside this scheduler and never create a row
or automatic retry. A direct timeout or provider failure is returned to Auth;
the user-facing action can initiate a new send.

Worker timing uses named defaults: a configurable five-minute polling interval,
a two-minute lease, and batches of at most 100 jobs. The lease is deliberately
longer than the approved 30-second provider request budget plus expected source
resolution and rendering time. The suspending interval is a test seam so worker
tests never sleep.

PostgreSQL `CURRENT_TIMESTAMP` is authoritative for `next_attempt_at`, claim
eligibility, lease expiry, and completion. Application wall clocks do
not decide whether another instance may take a job. PostgreSQL integration
fixtures move `next_attempt_at`, `lease_expires_at`, and terminal timestamps
explicitly to test time-dependent transitions without waiting in real time.

Because message content and recipient data are never stored, pending jobs do
not create the earlier indefinite message-payload retention problem. Terminal
success clears any safe error metadata and is already a minimal tombstone:
job ID, kind, source ID, idempotency/intent hashes, status, retry count, and
timestamps. There is no scheduled redaction, retention, or
deletion worker. Operations may clean the table manually when needed. Manual
cleanup is intentionally an operational decision: deleting a terminal row
removes its idempotency tombstone and can allow a replayed business event to
create another send; deleting a pending row cancels its future delivery. No
automatic system attempts to infer when either consequence is safe.

Concurrent enqueue uses PostgreSQL conflict handling on the idempotency key,
never exception-message or constraint-name inspection. It performs
`INSERT ... ON CONFLICT (idempotency_hash) DO NOTHING RETURNING id`. If no ID
is returned, a following statement selects the row by hash and compares its
`intent_hash` before returning the existing ID. This works inside the caller's
transaction, avoids an exception-aborted transaction, and remains safe when
two producers submit the same event concurrently. A different intent for the
same key is rethrown as a programmer/integration failure; it is not converted
to a successful duplicate. If a stricter caller-owned isolation level produces
a serialization failure, Email lets it abort the outer transaction so that the
producer's transaction retry policy remains authoritative.

The worker is launched in the Ktor application's coroutine lifecycle. It polls
PostgreSQL because polling works across processes and restarts; an in-memory
channel is not the authority. The worker claims nothing while `enabled` is
false. Claiming is a short transaction; source resolution, rendering, and
delivery occur outside it; and
completion is conditional on the claim's lease token. Shutdown cancels active
work, closes the shared Ktor client, and leaves the job reclaimable when its
lease expires.

### Kotlin production type map

| Type | Kind and visibility | Independent meaning |
| --- | --- | --- |
| `EmailRecipient` | public value class | Shared validated provider-neutral recipient address |
| `EmailActionUrl` | public secret-safe class | Complete validated HTTP(S) Auth action URL whose token is redacted from `toString()` |
| `UserEmail` | public sealed interface with five nested variants | Complete provider-neutral intent for a direct, non-persistent user-interaction email |
| `QueuedEmailReference` | public sealed interface with two nested variants | Stable Order or upload-task identity that is the only message-related value persisted with a durable job |
| `QueuedEmail` | public sealed interface with two nested variants | Process-only current placeholder data resolved for one queued processing cycle |
| `QueuedEmailSource` | public interface | Cross-module seam that resolves a stable queued reference into current recipient and template data |
| `UserEmailSender` | public interface | The cross-module direct-send seam without persistence or automatic retries |
| `EmailDeliveryException` | public exception class with internal constructor | Secret-free signal that direct Sweego acceptance was not confirmed, allowing Auth to distinguish an external dependency failure |
| `EmailOutbox` | public interface | The cross-module durable enqueue seam, including idempotency behavior |
| `EmailSettings` | public class | Validated `enabled`, polling, sender, and credential configuration loaded by the application without a secret-bearing generated `toString` |
| `EmailModule` | public runtime handle with narrow capabilities | Exposes only `userEmails` and `outbox`; internally owns source-aware worker, renderer, repository, provider client, installation, and shutdown |
| `EmailService` | internal class implementing both public send/enqueue interfaces | Directly renders and delivers `UserEmail`; hashes and transactionally stores only `QueuedEmailReference` jobs |
| `EmailRenderer` | internal class | Maps typed variants to subjects, FreeMarker HTML, and plain text |
| `RenderedEmail` | internal data class | Process-only provider-ready recipient, subject, HTML, and text for one attempt |
| `EmailJobs` | internal Exposed table | Maps the Flyway-owned durable queue and lifecycle columns |
| `EmailJobStatus` | internal enum | Database lifecycle states independent of provider response strings |
| `EmailJob` | internal data class | One claimed typed business reference plus lease identity |
| `EmailJobRepository` | internal class | Owns reference-only idempotent enqueue, claim leasing, retry scheduling, and terminal updates |
| `EmailWorker` | internal class | Coordinates polling, current-data resolution, rendering, cancellation, delivery classification, retries, and lease-safe completion |
| `EmailDelivery` | internal interface | True-external provider seam used by the Sweego and test adapters |
| `EmailDeliveryResult` | internal sealed interface | Provider-neutral accepted or safe failed outcome with optional provider back-pressure metadata |
| `SweegoEmailDelivery` | internal class | Ktor client adapter for the Sweego send contract |
| `SweegoSendRequest` | internal serializable data class with nested provider DTOs | Exact kebab-case Sweego wire payload without leaking it across the module seam |

The count is justified by two intentionally different delivery contracts, a
real external adapter, a durable concurrent worker for two message kinds, and
seven meaningful product messages. There are no route DTOs, provider response
wrappers, exception hierarchy, in-memory channel wrapper, generic job framework,
or consumer-owned source entities in the module interface.

The public interface and runtime composition types remain in
`shop.voenix.email`. The internal implementation is organized one level below
it: rendering types live in `shop.voenix.email.rendering`, the provider seam
and Sweego adapter in `shop.voenix.email.delivery`, and durable job mechanics
in `shop.voenix.email.outbox`. These packages improve locality without adding
another compilation module or exposing an internal seam.

The runtime shape is:

- public `EmailModule` exposes only `userEmails: UserEmailSender` and
  `outbox: EmailOutbox`, while its implementation owns lifecycle;
- internal `createEmailModule(database, settings, source, delivery)` supports
  focused module tests without exposing implementation types to other modules;
- public `Application.installEmailModule(database, settings, source): EmailModule`
  creates the Sweego adapter, starts the source-aware worker, registers
  shutdown, and returns the two narrow producer capabilities; and
- a narrow internal installation overload accepts direct-sender, outbox, or
  delivery test seams only where an application lifecycle test requires them.

### Application composition and dependencies

Implementation will:

- add `modules/email` to `backend/project.yaml` and the app dependency list;
- add the module to `backend/app/module.yaml`;
- add Ktor client core/CIO/content-negotiation artifacts and FreeMarker to the
  version catalog and Email module dependencies;
- add Email settings to `application.yaml` with `enabled: false` and
  `pollIntervalMinutes: 5` as safe defaults;
- add the installation seam now, but compose and start Email in
  `Application.install` only when the future Order/SFTP migrations can supply a
  real `QueuedEmailSource`; no placeholder source or polling worker is installed
  merely to have an empty runtime;
- subscribe worker/client shutdown to the Ktor application lifecycle; and
- add Flyway V5 under the existing platform migration resources without moving
  Email production code into `platform`.

`FrontendBaseUrl` is not an Email setting in Kotlin. The future Auth module owns
that configuration and passes complete, already encoded URLs.

### Test plan

| Test | Coverage |
| --- | --- |
| `EmailSettingsTest` | Safe `enabled: false` default, `pollIntervalMinutes` range/default five minutes, required provider credentials only when enabled, sender defaults, and secret-free error messages |
| `EmailActionUrlTest` | Absolute HTTP(S), local HTTP allowance, missing host, user-info/control rejection, length boundary, and redacted `toString()` |
| `EmailRendererTest` | All seven subjects, HTML/plain text, German date/money formatting, zero shipping, escaping, complete links, layout reuse |
| Golden template resources | Stable meaningful HTML/text structure without asserting FreeMarker whitespace artifacts |
| `UserEmailSenderTest` | All five direct variants, exactly one provider call when enabled, no database row or automatic retry, disabled no-op before rendering, safe public delivery exception, unexpected renderer failure, and caller cancellation |
| `EmailOutboxIntegrationTest` | Only the two queued references, fresh V5 schema, enqueue while enabled or disabled, domain-separated hashed producer key, kind/source-ID intent digest plus golden fixtures, identical duplicate key, mismatched reference, concurrent duplicate enqueue, outer transaction rollback, and proof that recipient/subject/template/body fields do not exist |
| `EmailWorkerIntegrationTest` | Configurable/default five-minute cadence, disabled jobs remain `PENDING` without resolution/provider access, enabled restart resumes them, no overlapping local scan, due-only selection, 100-row batch draining until empty, current recipient and placeholder/template changes between retries, missing/invalid/source/render failures back to `PENDING`, live `TRANSMITTED`, unbounded retry counter, `Retry-After`, two-minute lease recovery with ambiguous counter increment, future alert query, stale completion, cancellation, and two-worker concurrency |
| `EmailSchemaIntegrationTest` | Empty-database Flyway, every status/reference/lease/retry-counter check, absence of a delivery-mode column, unique idempotency hash, claim/lease/alert indexes, lifecycle timestamps, no persisted recipient/subject/template/body columns, and no Order/SFTP foreign-key dependency |
| `SweegoEmailDeliveryTest` | Exact fixed HTTPS destination, headers/JSON, disabled redirects, no client logging, transactional campaign type, stable queued/absent direct campaign ID, accepted response, drained/unexposed response body, request/connect/socket timeout mapping, safe network/HTTP/serialization failures with optional `Retry-After`, caller cancellation, and error redaction |
| `EmailModuleTest` | Runtime handle starts one source-aware worker, exposes only `UserEmailSender` and `EmailOutbox`, and closes worker/client on application stop |
| Future Auth/Order/SFTP tests | Direct Auth failure/optionality and resend behavior plus queued producer atomicity, business idempotency keys, stable references, and current-data resolution listed in the post-migration file |

Tests use PostgreSQL/Testcontainers for locking, leases, concurrency,
transactions, constraints, and Flyway. Provider tests use Ktor's local mock
engine; no quality gate sends a real email. A separately authorized manual
provider smoke test may use Sweego dry-run, but it is not an application route.

## Approved decisions

| Decision | Outcome | Status |
| --- | --- | --- |
| Persistence scope | Only Order confirmation and producer PDF notification are durable. Five Auth/user emails are direct, non-persistent, and retriggered by their owning workflows. | Joe — approved 2026-07-15 |
| Delivery guarantee | Durable delivery is at least once. Database idempotency, leases, and correlation do not remove the accepted crash-window duplicate because Sweego documents no idempotency guarantee. | Joe — approved 2026-07-16 |
| Persisted content | Store only kind and stable Order/upload-task reference. Resolve current recipient/data and render the current template on every attempt; no recipient, subject, placeholders, HTML, or text is stored. | Joe — approved 2026-07-16 |
| Notification triggers | Do not wire an Order producer in this migration. A separate story inventories all desired events and decides trigger, owner, transaction, idempotency, resend, and template. | Joe — deferred 2026-07-16 |
| Manual routes | Migrate neither unauthenticated `/api/emails` development route. Use automated adapter/renderer tests and an explicitly authorized Sweego dry-run smoke test. | Joe — approved 2026-07-16 |
| Enablement and terminal status | One `enabled` boolean only. Disabled direct sends are no-ops and queued jobs remain `PENDING`; enabled provider acceptance moves them to `TRANSMITTED`, which does not mean mailbox delivery. | Joe — approved 2026-07-16 |
| Scheduler and retries | Poll every configurable interval, default five minutes, drain bounded batches, increment `retry_count` for every known or recovered ambiguous failure, and keep retrying without a maximum or `FAILED` state. | Joe — approved 2026-07-16 |
| Sensitive data and retention | Logs and jobs omit message/recipient/token/provider secrets. Keep minimal reference tombstones without automatic cleanup; manual deletion may cancel pending delivery or remove duplicate protection. | Joe — approved 2026-07-16 |
| Provider metadata | Send `campaign-type: transac` and stable queued `campaign-id` for correlation only; omit custom `Message-ID`. | Email engineering decision |
| Module ownership | Export only `UserEmailSender` and `EmailOutbox` through `EmailModule`; keep provider/worker internal and defer a shared job framework until a second module proves it. | Email engineering decision |
| Order date | Future Order resolution converts the creation instant to `Europe/Berlin` `LocalDate`; Email formats `dd.MM.yyyy`. | Joe — approved 2026-07-16 |
| Provider timeout | Use 30-second request, 10-second connect, and 30-second socket timeouts. Direct failure surfaces to Auth; durable failure returns to `PENDING`. | Joe — approved 2026-07-16 |

All standalone Email implementation decisions are approved. The deferred
notification-trigger story does not block this checkpoint.

## Implementation

Implement only the approved design according to the canonical migration guide.
Keep this analysis, the deviation log below, and
[`email-post-migration.md`](email-post-migration.md) current while working.

Implementation must create or update:

- the Email module, resources, runtime composition, and Flyway migration;
- focused pure, adapter, lifecycle, and PostgreSQL integration tests;
- beginner-oriented Email module documentation under `docs/dev/backend`; and
- application configuration documentation without secrets.

Do not create a Git commit unless explicitly requested.

## Deviation and uncertainty log

| Behavior or contract | Source evidence | Proposed Kotlin behavior | Classification | Approval or owner | Follow-up |
| --- | --- | --- | --- | --- | --- |
| Source enqueues Order confirmation after paid commit, while Joe does not expect payment to trigger an email | `PaidOrderProcessor`, Mollie paid handling, zero-total checkout, and tests; Joe 2026-07-16 | Standalone Email exposes reusable capabilities but wires no Order producer; a separate story inventories all notification triggers and designs each producer | Material source/product contradiction, explicitly deferred | Joe — deferred 2026-07-16; future notification story owns product decision | `email-post-migration.md`; does not block Email core implementation |
| Source may strand `SENDING` | Recovery query | Expiring PostgreSQL lease | Incidental defect fix | Email migration | Worker integration test |
| Three in-process calls, fixed maximum, and no durable scheduling | Retry loop and provider exception path | Periodic batch scan; every failure increments `retry_count` and remains `PENDING` without a maximum; bounded `Retry-After` may defer eligibility | Approved deviation | Joe — approved 2026-07-16 | Scheduler, counter, batch, and recovery tests |
| One `Enabled` flag; disabled background task becomes `SENT`; no source dry-run | Options and `SendWithRetryAsync` | Preserve `enabled`; direct disabled sends create no row, queued jobs remain `PENDING`, enabled sends are live, and only provider acceptance becomes `TRANSMITTED`; manual dry-run is outside runtime | Approved truthful-state deviation | Joe — approved 2026-07-16 | Disabled pause/resume, enabled delivery, schema, and settings tests |
| Unauthenticated development endpoints can send arbitrary tests or duplicate Order confirmations; no caller exists | `EmailController`; commented Admin authorization; source-frontend and Kotlin-repository searches | No Email HTTP routes; automated tests plus explicitly authorized manual Sweego dry-run replace test sending, future resend belongs to authenticated Order workflow | Approved observable/security deviation | Joe — approved 2026-07-16 | Absence and adapter contract tests |
| Raw provider bodies, recipients, and exception detail enter logs/state | `SweegoClient`, `LastError`, and `EmailService` logs | Bounded safe errors and metadata only; no recipient, content, token, key, or raw response | Security/privacy hardening | Email migration engineering decision | Redaction and capturing-logger tests |
| Source retains readable recipient/subject/error metadata indefinitely and no body | EF Email task schema; no cleanup | Never persist recipient, subject, placeholders, HTML, or text; retain reference-based jobs without automatic cleanup; manual deletion accepts cancellation or lost-idempotency consequences | Approved privacy/current-data/operations decision | Joe — approved 2026-07-16 | Schema/no-cleanup test; document manual deletion consequences |
| No provider exactly-once contract | source port; official Sweego `/send` and campaign tracking docs checked 2026-07-15 | Explicit at-least-once guarantee for the two queued types; stable fields are correlation only | Approved material ambiguity | Joe — approved 2026-07-16 | Preserve truthful status and duplicate documentation |
| Frontend base URL belongs to Email settings | `SweegoOptions`, used only by Auth | Future Auth settings own it | Deferred ownership cleanup | Auth migration | `email-post-migration.md` |
| Provider request omits campaign type and correlation | source DTO | `campaign-type: transac` plus one stable queued `campaign-id`; no redundant custom `Message-ID` | Provider-contract hardening | Email migration engineering decision | Adapter contract test |
| “Bestelldatum” formats the UTC `Order.CreatedAt` date directly | Checkout timestamp creation, `timestamptz`, and both queued renderers | Future Order supplies a `Europe/Berlin` calendar date; Email formats `dd.MM.yyyy` | Approved user-visible date correction | Joe — approved 2026-07-16; Order owns business date semantics | Midnight-boundary fixtures |
| Typed Sweego client inherits .NET's 100-second request timeout | `AddHttpClient` registration without timeout override; official .NET default | Ktor request/connect/socket timeouts of 30/10/30 seconds; direct failure surfaces, queued timeout increments `retry_count` and returns to `PENDING` | Approved interactive-latency deviation | Joe — approved 2026-07-16 | Adapter timeout tests and future Auth response-bound test |

## Completion report

Implementation completed on 2026-07-16.

The Kotlin `email` module now contains the five approved direct user messages
and the two approved durable message kinds. Direct messages render and call the
provider once without persistence or an Email-owned retry. Durable messages
persist only domain-separated hashes, kind, and stable source ID; the worker
resolves current data and renders the current template for every attempt. All
seven variants have FreeMarker HTML and plain-text templates. Dynamic template
values are escaped, German dates and money are formatted explicitly, complete
Auth-owned action URLs are accepted without persisting their tokens, and
provider or worker errors are reduced to bounded secret-free classifications.

Flyway V5 creates the reference-only `email_jobs` table with lifecycle checks,
unique idempotency hashes, and claim, lease-recovery, and retry-alert indexes.
PostgreSQL integration tests verified caller-transaction rollback, identical
and conflicting idempotency intents, concurrent enqueue, `SKIP LOCKED` claims,
two concurrent workers, batches larger than 100 jobs, disabled pause and later
resume, current-data resolution on retry, unbounded retry counters,
`Retry-After`, stale lease tokens, cancellation, and ambiguous lease recovery.
Only confirmed Sweego acceptance produces `TRANSMITTED`; the documented crash
window therefore remains explicitly at least once.

The Sweego Ktor adapter uses the fixed HTTPS send endpoint, transactional
campaign metadata, stable queued correlation IDs, disabled redirects, and
30/10/30-second request/connect/socket timeouts. Mock-engine tests verify the
wire payload, success-body draining, safe HTTP failure classification, and
invalid `Retry-After` handling. The application configuration defaults Email
to disabled and keeps credentials in environment variables. The public module
surface remains `UserEmailSender` plus `EmailOutbox`; provider, renderer,
repository, and worker stay internal and are grouped in the semantic
`delivery`, `rendering`, and `outbox` packages. No Email HTTP routes or generic
job framework were introduced.

Verification finished with 31/31 focused Email tests passing and the complete
`./kotlin check` quality gate passing, including repository-wide tests, Ktlint,
and Detekt. The final `./kotlin do ktfmt` run reached its fixed point without
rewriting a file. The checks used local PostgreSQL Testcontainers and Ktor's
mock engine. No real Sweego request was sent, so live provider delivery is not
claimed.

Order, Auth, and SFTP producer composition remains intentionally deferred to
[`email-post-migration.md`](email-post-migration.md). Until a migrated consumer
can supply a real `QueuedEmailSource`, the application composition root does
not install an empty placeholder worker. The unresolved product question is
still which Order events should actually notify customers; it does not block
the standalone Email capability.

## Migration retrospective

Complete this table after implementation, verification, simplification, and
comparison with the original analysis.

| Finding | Evidence | Scope | Earlier signal or check | Destination and action |
| --- | --- | --- | --- | --- |
| Raw Exposed `WITH ... UPDATE ... RETURNING` statements need `StatementType.SELECT` to expose returned rows | The first PostgreSQL worker test received no claimed rows until the statement type was explicit; the database update itself was otherwise valid | Email repository; potentially reusable if another module adopts raw returning statements | PostgreSQL integration test for lease claiming | Kept here and made explicit in `EmailJobRepository`; one occurrence does not yet justify a global guide rule |
| An Order's item sum is not necessarily its payable total | Initial implementation validation equated item totals with the Order total, but legitimate discounts or promotions can make them differ | Email's future Order source contract | Simplification review against real checkout semantics | Removed the equality invariant; retained checked item arithmetic and recorded the boundary here for the future Order producer |
| Worker stage boundaries should reflect independently retryable failure classes | Detekt flagged the first large conditional coordinator; source resolution, rendering, and delivery already have distinct retry classifications | Email worker implementation | Final Detekt gate plus retry-state test matrix | Split the coordinator into `resolve`, `render`, and `deliver`; no shared worker abstraction was created because no second module needs it |

No finding met the promotion threshold for changing the canonical migration
guide or migration skill in this run. The findings are concrete but remain
module-specific or have only one observed occurrence.
