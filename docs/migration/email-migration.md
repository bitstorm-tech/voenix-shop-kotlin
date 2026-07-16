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
durable email kinds. Durable delivery is reference-only, periodically retried
by one active worker, and explicitly at least once. The complete approved decision set is recorded
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
[`kotlinx.html` documentation](https://github.com/Kotlin/kotlinx.html),
[Sweego authentication](https://learn.sweego.io/docs/auth/api_keys),
[send/dry-run](https://www.sweego.io/send-email-sms-api-smtp),
[campaign tracking](https://learn.sweego.io/docs/sending/how_to_send_sms_by_api),
[delivery events](https://learn.sweego.io/docs/webhooks/email_events), and
[API errors](https://learn.sweego.io/docs/api-error-codes). General Kotlin,
persistence, testing, and completion
rules are intentionally not repeated here.

## Migration boundary

The standalone Email migration should include:

- a product-owned Email module with separate direct-send and durable-outbox
  interfaces so callers cannot accidentally choose the wrong delivery model;
- the seven current transactional email kinds and their German HTML and plain-
  text content;
- one typed Kotlin template file per email type using `kotlinx.html`, plain-text
  builders, and shared layout functions;
- a Sweego delivery adapter built on one reusable Ktor `HttpClient`;
- a PostgreSQL-backed single worker with periodic retries, current-data
  resolution, and graceful cancellation;
- a clean Flyway `email_jobs` table and Exposed mapping;
- typed startup configuration with one safe-default `enabled` switch;
- an exported `UserEmailSender` for the five non-persistent user-interaction
  emails;
- an exported `EmailOutbox` for order confirmations and producer PDF
  notifications that later product modules can call inside their own database
  transaction; and
- an exported `QueuedEmailSource` seam through which the application resolves
  current Order/SFTP data for an open business reference before rendering.

This migration must not add placeholder User, Order, Payment, SFTP, or PDF
tables or migrate those modules' routes. It must not add a generic platform job
framework on the evidence of one module. A second durable-job migration may
justify extracting shared mechanics later.

The Email module will not own frontend URL construction, auth tokens, paid-
order transitions, order persistence, SFTP configuration, or upload lifecycle.
Those concerns remain with their producing modules, which expose current data
through `QueuedEmailSource`. Email owns message copy, rendering, direct
user-email dispatch, durable background dispatch, provider integration, and
delivery state.

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
provider acceptance but before its terminal source status can cause a duplicate when recovery
is made correct.

ADR 0001 rejects in-memory-only background work and requires durable external
writes. The Kotlin design therefore cannot faithfully copy
the source worker mechanics. The two background email kinds need a durable
outbox with explicit at-least-once semantics and a stable provider correlation
identity. The initial implementation added distributed-worker coordination and
hashed caller keys; Joe approved removing that speculative complexity on
2026-07-16. The five user-interaction email kinds preserve the source's direct,
non-persistent delivery model.

### Behavior, evidence, classification, Kotlin approach, and verification

| Behavior | Evidence | Classification | Kotlin approach | Verification |
| --- | --- | --- | --- | --- |
| Disabled configuration accepts no provider credentials; enabled requires API key and sender. Effective sender name is `Voenix Shop`; frontend URL is only used by Auth. | `SweegoOptions`, validator, startup tests | Required; URL ownership deviation | `EmailSettings` defaults to disabled, validates credentials only when enabled, and validates polling 1–1,440 minutes (default 5). Future Auth owns frontend URL. | Settings/YAML tests; Auth follow-up |
| Normal sends POST recipient, sender, subject, HTML, and text to Sweego with `Api-Key`, `channel=email`, and `provider=sweego`; response DTO fields serve only the removed test route. | `SweegoClient`, DTOs, official Sweego docs | Required wire behavior; response DTO incidental | Internal Ktor adapter preserves the request contract; HTTP success is acceptance and provider DTOs do not escape. | Exact request and empty/success response tests |
| .NET inherits a 100-second timeout. | Client registration and .NET docs | Exact source duration incidental; bounded wait required | Ktor request/connect/socket timeouts are 30/10/30 seconds. | Adapter timeout and future Auth response-bound tests |
| Source exceptions/logs can expose provider bodies and recipient addresses. | `SweegoClient`, `EmailService`, error mapping | Security/privacy deviation | Persist/log only bounded safe codes; never recipient, subject, body, token URL, key, or raw response. | Adapter, worker, and capturing-logger redaction tests |
| Provider failure maps to `502`, rendering to `500`; caller cancellation propagates while Auth owns optional notification policy. | Source exceptions, handler, Auth and cancellation tests | Required distinction; hierarchy incidental | Public secret-free `EmailDeliveryException` represents unconfirmed acceptance; programming/rendering failure stays unexpected; cancellation is rethrown. | Direct sender, worker cancellation, and future Auth mapping tests |
| Seven variants use fixed German subjects, separate HTML/text, and one branded inline-styled layout. | Razor templates, layout, subject literals, text builders | Required content; Razor syntax/whitespace incidental | Typed Kotlin functions preserve wording, dynamic information, layout, and independent text; `kotlinx.html` escapes dynamic markup values. | Seven renderer cases covering content, escaping, subjects, and formatting |
| Duplicate source model fields cannot differ at call sites. | Change-email and Order models/renderers | Incidental representation | Change-email derives displayed address from recipient; Order has one current recipient. | Constructor shape and fixtures |
| Only Order confirmation and producer PDF notification use `EmailTask`; five Auth variants send directly. | `EmailType`, send/enqueue methods | Required persistence boundary, approved | Only two `QueuedEmailReference` variants enter `EmailOutbox`; five `UserEmail` variants never touch `email_jobs`. | Interface, sender, and outbox tests |
| Auth owns confirmation/resend, reset enumeration safety, required change-email confirmation, and best-effort warnings. | Auth controller/tests | Required, consumer-owned | Email exposes five direct variants; resend is a new send, URL construction and failure impact stay in Auth. | Renderer/sender tests and future Auth tests |
| Source has one `Enabled` flag; disabled direct sends no-op, but queued delivery incorrectly marks `SENT`. | Options and send paths | Required switch; queued state defect | Keep one boolean. Disabled direct sends create no row and the worker does not scan; jobs remain open. Enabled acceptance sets `sent_at`; no runtime dry-run. | Settings, no-op, pause/restart, and live tests |
| Producer inputs validate recipients; Auth token URLs remain process-only. | Request DTOs, Auth/Checkout/SFTP validation | Required seam/security invariant | `EmailRecipient` validates before send; `EmailActionUrl` is validated and redacted. Producer validation remains producer-owned. | Value tests and no-job/no-log token tests |
| Order enqueue snapshots recipient/subject and wakes an in-memory channel; processing re-reads mutable Order data. | Order enqueue/process methods | Required durable intent and send-time data; mechanics incidental | Store only the stable Order reference; database is queue; resolve current data/template each attempt. Missing/invalid source records a failed attempt. | Outbox and changed/missing-source worker tests |
| Source triggers confirmation only after `PaidOrderProcessor` commits `PAID`, contrary to expected product behavior. | Paid processor, Payment/Checkout call sites/tests | Material product contradiction, deferred | Wire no Order producer. Future notification story decides trigger, owner, transaction, and duplicate behavior. | Future story and trigger integration tests |
| Order templates format German date/money, zero shipping, totals, addresses, and different HTML/text line detail; source uses UTC calendar date. | Order renderers/fixtures and timestamp schema | Required content; date correction approved | Preserve presentation and use Order-supplied `Europe/Berlin` `LocalDate`; define authoritative item order. | Golden price/address/order and midnight-boundary fixtures |
| Source Order arithmetic can overflow `int`. | `SendOrderConfirmationAsync` | Incidental unsafe edge | Use checked integer-cent arithmetic consistent with Pricing; abort rather than render a false amount. | Arithmetic boundary tests |
| Producer notification follows successful configured SFTP upload; failures are best effort and task ID identifies the send. | `SftpUploadService`, process method/tests | Required, SFTP-owned | Future SFTP supplies stable upload-task reference/key after success and owns recoverability. | Future SFTP and multiple-job tests |
| Producer rendering re-resolves configuration by recipient; quantity sums item quantities; blank name can render badly. | Producer send method/templates/validator | Required current-data intent; ambiguous lookup/name defect | Resolve current server/recipient/name by stable upload task, normalize blank name absent, require server name, and expose total quantity. | Changed-recipient, same-email-server, greeting, and quantity tests |
| Source retries three in-process calls, caps attempts, retries every exception, and has incomplete restart recovery. | Retry delays, worker, tests | Retry/recovery intent required; policy replaced by approval | One periodic worker scans every open job; every attempt increments an unbounded counter and failures remain open for the next scan. | Cadence, failure, counter, and restart tests |
| `SENDING` can be stranded; source has no business uniqueness or multi-worker lock. | Worker, EF schema/queries | Incidental defect; multi-worker support deferred | Do not persist an in-progress state. `sent_at IS NULL` stays retryable after a restart; `(email_kind, source_id)` is unique. Support exactly one active worker until deployment needs coordination. | Schema, duplicate enqueue, restart, and single-worker tests |
| Manual non-production routes are unauthenticated and have no consumer. | Controller/tests and repository searches | Observable/security deviation, approved | Migrate no Email routes; use adapter/renderer tests and authorized dry-run smoke test. Future resend is an authenticated owner command. | Absence and adapter tests |
| Source table stores recipient/subject/error indefinitely but no body; Auth tokens remain process-only. | EF mapping/migration, Auth paths | Lifecycle required; content persistence rejected | `email_jobs` stores the reference, attempt count, `sent_at`, and bounded error code only; no message/recipient/token data or automatic cleanup. | Flyway schema, no-content, retention tests |
| Provider acceptance and local completion have a crash window; `campaign-id` is tracking, not documented deduplication. | Worker/port and Sweego docs | Material ambiguity, approved | Document at-least-once only; use stable campaign correlation without claiming provider idempotency. | Crash-window seam test and operations docs |

### Planned external module interface

The interface makes direct and durable delivery impossible to confuse while
keeping rendering, provider JSON, persistence, retries, and lifecycle internal.

```kotlin
public interface UserEmailSender {
    public suspend fun send(email: UserEmail)
}

public interface EmailOutbox {
    public suspend fun enqueue(reference: QueuedEmailReference): Long
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
placeholder values and Email renders a fresh message with the deployed Kotlin
renderer. No resolved or rendered message data is persisted. Address and
source-data changes therefore affect the next attempt while the business event
and campaign ID stay stable. After an ambiguous accepted attempt, a retry can
reach both an old and a current address; this is part of the approved
at-least-once trade-off.

Resolved models contain only provider-neutral values. Order owns authoritative
business values and `Europe/Berlin` calendar-date conversion; Email owns German
presentation. No Order, SFTP, Exposed, or Ktor type crosses the seam.

Shared values such as `EmailRecipient` enforce the module's safety invariants:
recipient addresses are trimmed, at most 255 characters, contain exactly one
`@` with nonempty local and domain parts, and contain no whitespace or control
characters. This deliberately matches the repository's existing simple
supplier-email shape check instead of introducing a second mail-address parser.
Sender configuration uses the same invariant. Display values are bounded to
255 characters and free from control characters where relevant, and counts and
money fields satisfy the producer contract. These checks guard the internal seam; public request
validation and localized field errors remain with the producing module.

The typed reference is the complete persisted business identity. Email maps it
to `email_kind` plus `source_id`, and PostgreSQL makes that pair unique. Repeated
enqueue calls for the same kind and source return the existing job ID. The
initial caller-defined key and its idempotency/intent hashes duplicated this
same information and were removed by Joe's simplification decision on
2026-07-16. Direct sends have no persisted identity or automatic retry; a UI
resend is a new send.

Neither interface returns `OperationResult`. Direct send either completes after
Sweego accepts a live request, is a disabled no-op, throws the secret-free
`EmailDeliveryException`, or propagates an unexpected programming/rendering
failure. The exception has an internal constructor and exposes no recipient,
content, credentials, provider body, or DTO.

`EmailOutbox.enqueue` joins an existing Exposed transaction and never commits
independently. Duplicate references succeed; other persistence failures abort
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
| Enqueue | typed `QueuedEmailReference` | new or existing job ID | unexpected persistence failure | unique `(email_kind, source_id)` rule, transaction-composable |
| Scan | current `enabled` setting | all open job references when enabled | no open job or delivery disabled | one active worker; no persisted claim state |
| Resolve and render | open typed reference plus current producer data | one process-only provider-ready message using the current recipient, placeholder values, and templates | missing/invalid/unavailable source or render failure | outside database transactions; failure leaves the job open |
| Deliver | process-only rendered message and stable provider correlation ID | live request accepted | typed safe failure code | outside database transactions |
| Start attempt | open job ID | incremented `attempt_count` | job was already sent | conditional update on `sent_at IS NULL` |
| Complete | attempted job ID | `sent_at` set and error cleared | job was already sent | conditional update on `sent_at IS NULL` |
| Record failure | attempted job ID and safe code | job remains open with its latest safe error | job was already sent | next periodic scan retries it |

The worker provides at-least-once delivery only for the two `QueuedEmail`
variants. Database uniqueness prevents duplicate jobs, but the single worker
cannot prove exactly-once external delivery when the provider accepts a request
and the process stops before the database completion write. Sweego's
current public `/send` request exposes no idempotency key, describes
`campaign-id` as a tracking identifier, and does not document duplicate
suppression for that field or custom headers. Exactly-once delivery therefore
remains unprovable without a separate contractual provider guarantee.

### Rendering and provider design

Email uses `kotlinx.html` directly, without Ktor server templating. Each email
type has one file in `shop.voenix.email.template` containing its subject, HTML,
and plain text. Typed Kotlin functions share layout functions, and normal DSL
writes HTML-escape dynamic values. Renderer tests cover all seven HTML/text
variants, escaping, links, and shared layout; Razor
whitespace is not contractual.

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
Email classifies the response status; it drains but never parses,
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
and the worker scans no jobs. Durable references are still inserted as open,
so they resume after an enabled restart. Runtime has no dry-run mode; an
explicitly authorized Sweego dry-run is a separate smoke test.

### Persistence and worker design

Flyway `V5__create_email_jobs.sql` should create `email_jobs` with:

- identity `bigint` primary key;
- bounded `email_kind` and one positive `bigint source_id`, unique together;
- nonnegative `integer attempt_count`;
- nullable `varchar(64) last_error_code`; and
- `timestamptz created_at` plus nullable `timestamptz sent_at`.

No Order/SFTP foreign key or placeholder table is added because those schemas
do not yet exist and the two reference kinds point to different owning tables.
`email_kind` determines whether `source_id` is an Order ID or durable SFTP
upload-task ID. The Email table never stores recipient addresses or names,
subjects, template values, HTML, or plain text in any state. `sent_at IS NULL`
means open; a non-null value means Sweego accepted the live request. User-email
bodies and token links are likewise never persisted.

The database table is the queue; no external broker is needed. One active
application-lifecycle worker scans every open row at the configurable interval,
defaulting to five minutes. Before resolving current source data it increments
`attempt_count`. Missing or invalid source data, rendering failures, provider
failures, timeouts, serialization failures, and other normal delivery failures
leave the row open and store only a bounded provider-neutral code such as
`SOURCE_NOT_FOUND`, `RENDERING_FAILED`, or `PROVIDER_HTTP_503`. The next scan
tries it again. There is no maximum attempt count or terminal failed state.

Provider acceptance sets `sent_at` with PostgreSQL `CURRENT_TIMESTAMP` and
clears the previous error. Caller cancellation propagates and leaves the row
open; its already-started attempt remains visible in `attempt_count`. A crash
after provider acceptance but before `sent_at` is written can cause a duplicate
on restart, which is the documented at-least-once trade-off.

The worker intentionally does not coordinate multiple application instances.
The deployment must run one active Email worker. Claim state, leases, fencing
tokens, per-job scheduling, and `Retry-After` persistence were removed because
no current deployment requires distributed workers or individual retry times.
They may be introduced later when an observed requirement justifies them.

Direct `UserEmail` sends remain outside this scheduler and never create a row
or automatic retry. A direct timeout or provider failure is returned to Auth;
the user-facing action can initiate a new send.

The suspending polling interval is a test seam so worker tests never sleep.
Because message content and recipient data are never stored, open jobs do not
create the earlier indefinite message-payload retention problem. A sent row is
already a minimal tombstone: job ID, kind, source ID, attempt count, and
timestamps. There is no scheduled redaction, retention, or deletion worker.
Operations may clean the table manually when needed. Manual
cleanup is intentionally an operational decision: deleting a terminal row
removes duplicate protection and can allow a replayed business event to create
another send; deleting an open row cancels its future delivery. No
automatic system attempts to infer when either consequence is safe.

Concurrent enqueue uses PostgreSQL conflict handling on the unique
`(email_kind, source_id)` pair. It inserts or selects the existing row inside
the caller's transaction, so repeated or concurrent submissions return one job
ID without storing a second identity representation. If a stricter caller-owned
isolation level produces a serialization failure, Email lets it abort the outer
transaction so the producer's retry policy remains authoritative.

The worker is launched in the Ktor application's coroutine lifecycle. It polls
PostgreSQL because polling works across processes and restarts; an in-memory
channel is not the authority. The worker scans nothing while `enabled` is
false. Source resolution, rendering, and delivery happen outside database
transactions. Shutdown cancels active work, closes the shared Ktor client, and
leaves an unfinished job open for the next application start.

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
| `EmailOutbox` | public interface | The cross-module durable enqueue seam for a typed source reference |
| `EmailSettings` | public class | Validated `enabled`, polling, sender, and credential configuration loaded by the application without a secret-bearing generated `toString` |
| `EmailModule` | public runtime handle with narrow capabilities | Exposes only `userEmails` and `outbox`; internally owns source-aware worker, renderer, repository, provider client, installation, and shutdown |
| `EmailService` | internal class implementing both public send/enqueue interfaces | Directly renders and delivers `UserEmail`; transactionally stores only `QueuedEmailReference` jobs |
| `UserEmailRenderer` | internal functional interface | Narrow rendering seam for direct `UserEmail` values |
| `QueuedEmailRenderer` | internal functional interface | Narrow rendering seam for current `QueuedEmail` values resolved by the worker |
| `EmailRenderer` | internal class | Selects the per-email template and prepares formatted presentation values |
| `*EmailTemplate` | internal object per email type | Owns one email's subject, `kotlinx.html` output, and Kotlin-built plain text in one file |
| `RenderedEmail` | internal data class | Process-only provider-ready recipient, subject, HTML, and text for one attempt |
| `EmailJobs` | internal Exposed table | Maps the Flyway-owned minimal durable queue columns |
| `EmailJob` | internal data class | One open typed business reference plus its attempt count |
| `EmailJobRepository` | internal class | Owns unique reference enqueue, open-job scans, attempt counting, errors, and sent timestamps |
| `EmailWorker` | internal class | Coordinates single-worker polling, current-data resolution, rendering, cancellation, delivery classification, and retries |
| `EmailDelivery` | internal interface | True-external provider seam used by the Sweego and test adapters |
| `EmailDeliveryResult` | internal sealed interface | Provider-neutral accepted or safe failed outcome with optional provider back-pressure metadata |
| `SweegoEmailDelivery` | internal class | Ktor client adapter for the Sweego send contract |
| `SweegoSendRequest` | internal serializable data class with nested provider DTOs | Exact kebab-case Sweego wire payload without leaking it across the module seam |

The count is justified by two intentionally different delivery contracts, a
real external adapter, a durable single worker for two message kinds, and
seven meaningful product messages. There are no route DTOs, provider response
wrappers, exception hierarchy, in-memory channel wrapper, generic job framework,
or consumer-owned source entities in the module interface.

The public interface and runtime composition types remain in
`shop.voenix.email`. The internal implementation is organized one level below
it: renderer orchestration lives in `shop.voenix.email.rendering`, one file per
email lives in `shop.voenix.email.template`, the provider seam and Sweego
adapter in `shop.voenix.email.delivery`, and durable job mechanics in
`shop.voenix.email.outbox`. These packages improve locality without adding
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
- add Ktor client core/CIO/content-negotiation and `kotlinx.html` artifacts to
  the version catalog and Email module dependencies;
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
| Typed renderer cases | Stable meaningful HTML/text structure without asserting incidental whitespace artifacts |
| `UserEmailSenderTest` | All five direct variants, exactly one provider call when enabled, no database row or automatic retry, disabled no-op before rendering, safe public delivery exception, unexpected renderer failure, and caller cancellation |
| `EmailOutboxIntegrationTest` | Only the two queued references, unique kind/source identity, identical and concurrent duplicate enqueue, distinct kinds for one numeric source ID, outer transaction rollback, and minimal stored data |
| `EmailWorkerIntegrationTest` | Configurable/default five-minute cadence, disabled open jobs, enabled restart, full open-job scan, current data on retry, missing/source/render/provider failures, `sent_at`, unbounded attempt counter, cancellation, and single-worker behavior |
| `EmailSchemaIntegrationTest` | Empty-database Flyway, exact seven-column schema, kind/source/attempt checks, unique kind/source rule, no extra indexes, no persisted message content, and no Order/SFTP foreign key |
| `SweegoEmailDeliveryTest` | Exact fixed HTTPS destination, headers/JSON, disabled redirects, no client logging, transactional campaign type, stable queued/absent direct campaign ID, accepted response, drained/unexposed response body, request/connect/socket timeout mapping, safe network/HTTP/serialization failures, caller cancellation, and error redaction |
| `EmailModuleTest` | Runtime handle starts one source-aware worker, exposes only `UserEmailSender` and `EmailOutbox`, and closes worker/client on application stop |
| Future Auth/Order/SFTP tests | Direct Auth failure/optionality and resend behavior plus queued producer atomicity, stable unique references, and current-data resolution listed in the post-migration file |

Tests use PostgreSQL/Testcontainers for duplicate enqueue concurrency,
transactions, constraints, and Flyway. Provider tests use Ktor's local mock
engine; no quality gate sends a real email. A separately authorized manual
provider smoke test may use Sweego dry-run, but it is not an application route.

## Approved decisions

| Decision | Outcome | Status |
| --- | --- | --- |
| Persistence scope | Only Order confirmation and producer PDF notification are durable. Five Auth/user emails are direct, non-persistent, and retriggered by their owning workflows. | Joe — approved 2026-07-15 |
| Delivery guarantee | Durable delivery is at least once. Unique references and correlation do not remove the accepted crash-window duplicate because Sweego documents no idempotency guarantee. | Joe — approved 2026-07-16 |
| Persisted content | Store only kind and stable Order/upload-task reference. Resolve current recipient/data and render a fresh message on every attempt; no recipient, subject, placeholders, HTML, or text is stored. | Joe — approved 2026-07-16 |
| Notification triggers | Do not wire an Order producer in this migration. A separate story inventories all desired events and decides trigger, owner, transaction, duplicate behavior, resend, and template. | Joe — deferred 2026-07-16 |
| Manual routes | Migrate neither unauthenticated `/api/emails` development route. Use automated adapter/renderer tests and an explicitly authorized Sweego dry-run smoke test. | Joe — approved 2026-07-16 |
| Enablement and terminal status | One `enabled` boolean only. Disabled direct sends are no-ops and queued jobs remain open; enabled provider acceptance sets `sent_at`, which does not mean mailbox delivery. | Joe — approved 2026-07-16 |
| Scheduler and retries | One active worker scans every open job at the configurable interval, default five minutes; every started attempt increments `attempt_count`, and failures remain open without a maximum. | Joe — simplified 2026-07-16 |
| Job identity and coordination | `(email_kind, source_id)` is the complete unique job identity. Do not store caller keys or hashes. Do not add claim, lease, fencing, or per-job scheduling fields until a real multi-worker or back-pressure requirement exists. | Joe — approved simplification 2026-07-16 |
| Sensitive data and retention | Logs and jobs omit message/recipient/token/provider secrets. Keep minimal reference tombstones without automatic cleanup; manual deletion may cancel open delivery or remove duplicate protection. | Joe — approved 2026-07-16 |
| Provider metadata | Send `campaign-type: transac` and stable queued `campaign-id` for correlation only; omit custom `Message-ID`. | Email engineering decision |
| Module ownership | Export only `UserEmailSender` and `EmailOutbox` through `EmailModule`; keep provider/worker internal and defer a shared job framework until a second module proves it. | Email engineering decision |
| Order date | Future Order resolution converts the creation instant to `Europe/Berlin` `LocalDate`; Email formats `dd.MM.yyyy`. | Joe — approved 2026-07-16 |
| Provider timeout | Use 30-second request, 10-second connect, and 30-second socket timeouts. Direct failure surfaces to Auth; durable failure leaves the job open with a counted attempt. | Joe — approved 2026-07-16 |

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
| Source may strand `SENDING` | Recovery query | Persist no in-progress state; an unfinished job remains open across restart | Incidental defect fix and approved simplification | Joe — approved 2026-07-16 | Worker restart and cancellation tests |
| Three in-process calls, fixed maximum, and no durable scheduling | Retry loop and provider exception path | One periodic scan; every started attempt increments `attempt_count`, failures remain open without a maximum, and no per-job `Retry-After` schedule is stored | Approved deviation | Joe — simplified 2026-07-16 | Scheduler, counter, failure, and restart tests |
| One `Enabled` flag; disabled background task becomes `SENT`; no source dry-run | Options and `SendWithRetryAsync` | Preserve `enabled`; direct disabled sends create no row, queued jobs remain open, enabled sends are live, and only provider acceptance sets `sent_at`; manual dry-run is outside runtime | Approved truthful-state deviation | Joe — approved 2026-07-16 | Disabled pause/resume, enabled delivery, schema, and settings tests |
| Unauthenticated development endpoints can send arbitrary tests or duplicate Order confirmations; no caller exists | `EmailController`; commented Admin authorization; source-frontend and Kotlin-repository searches | No Email HTTP routes; automated tests plus explicitly authorized manual Sweego dry-run replace test sending, future resend belongs to authenticated Order workflow | Approved observable/security deviation | Joe — approved 2026-07-16 | Absence and adapter contract tests |
| Raw provider bodies, recipients, and exception detail enter logs/state | `SweegoClient`, `LastError`, and `EmailService` logs | Bounded safe errors and metadata only; no recipient, content, token, key, or raw response | Security/privacy hardening | Email migration engineering decision | Redaction and capturing-logger tests |
| Source retains readable recipient/subject/error metadata indefinitely and no body | EF Email task schema; no cleanup | Never persist recipient, subject, placeholders, HTML, or text; retain reference-based jobs without automatic cleanup; manual deletion accepts cancellation or lost duplicate protection | Approved privacy/current-data/operations decision | Joe — approved 2026-07-16 | Schema/no-cleanup test; document manual deletion consequences |
| No provider exactly-once contract | source port; official Sweego `/send` and campaign tracking docs checked 2026-07-15 | Explicit at-least-once guarantee for the two queued types; stable fields are correlation only | Approved material ambiguity | Joe — approved 2026-07-16 | Preserve truthful status and duplicate documentation |
| Frontend base URL belongs to Email settings | `SweegoOptions`, used only by Auth | Future Auth settings own it | Deferred ownership cleanup | Auth migration | `email-post-migration.md` |
| Provider request omits campaign type and correlation | source DTO | `campaign-type: transac` plus one stable queued `campaign-id`; no redundant custom `Message-ID` | Provider-contract hardening | Email migration engineering decision | Adapter contract test |
| “Bestelldatum” formats the UTC `Order.CreatedAt` date directly | Checkout timestamp creation, `timestamptz`, and both queued renderers | Future Order supplies a `Europe/Berlin` calendar date; Email formats `dd.MM.yyyy` | Approved user-visible date correction | Joe — approved 2026-07-16; Order owns business date semantics | Midnight-boundary fixtures |
| Typed Sweego client inherits .NET's 100-second request timeout | `AddHttpClient` registration without timeout override; official .NET default | Ktor request/connect/socket timeouts of 30/10/30 seconds; direct failure surfaces, queued timeout leaves the job open after incrementing `attempt_count` | Approved interactive-latency deviation | Joe — approved 2026-07-16 | Adapter timeout tests and future Auth response-bound test |

## Completion report

Implementation completed on 2026-07-16.

The Kotlin `email` module now contains the five approved direct user messages
and the two approved durable message kinds. Direct messages render and call the
provider once without persistence or an Email-owned retry. Durable messages
persist only kind, stable source ID, attempt count, safe error code, and
created/sent timestamps; the worker resolves current data and renders the
message afresh for every attempt. All seven variants have typed `kotlinx.html`
output and Kotlin-built plain text. Dynamic HTML values are escaped, German
dates and money are formatted explicitly, complete Auth-owned action URLs are
accepted without persisting their tokens, and provider or worker errors are
reduced to bounded secret-free classifications.

Flyway V5 creates the seven-column reference-only `email_jobs` table. The
unique `(email_kind, source_id)` rule is the only secondary index. PostgreSQL
integration tests verify caller-transaction rollback, repeated and concurrent
duplicate enqueue, full open-job scans, disabled pause and later resume,
current-data resolution on retry, unbounded attempt counts, cancellation, and
the absence of speculative queue columns. Only confirmed Sweego acceptance
sets `sent_at`; the documented crash window therefore remains explicitly at
least once. The deployment must run exactly one active Email worker.

The Sweego Ktor adapter uses the fixed HTTPS send endpoint, transactional
campaign metadata, stable queued correlation IDs, disabled redirects, and
30/10/30-second request/connect/socket timeouts. Mock-engine tests verify the
wire payload, success-body draining, and safe HTTP failure classification. The
application configuration defaults Email
to disabled and keeps credentials in environment variables. The public module
surface remains `UserEmailSender` plus `EmailOutbox`; provider, renderer,
repository, and worker stay internal and are grouped in the semantic
`delivery`, `rendering`, `template`, and `outbox` packages. No Email HTTP routes
or generic job framework were introduced.

Verification finished with 28/28 focused Email tests passing and the complete
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
| Raw Exposed `UPDATE ... RETURNING` statements need `StatementType.SELECT` to expose returned rows | The first worker implementation received no returned rows until the statement type was explicit | Email repository; potentially reusable if another module adopts raw returning statements | PostgreSQL integration test for conditional job updates | Kept here and made explicit in `EmailJobRepository`; one occurrence does not yet justify a global guide rule |
| An Order's item sum is not necessarily its payable total | Initial implementation validation equated item totals with the Order total, but legitimate discounts or promotions can make them differ | Email's future Order source contract | Simplification review against real checkout semantics | Removed the equality invariant; retained checked item arithmetic and recorded the boundary here for the future Order producer |
| Worker stage boundaries should reflect independently retryable failure classes | Detekt flagged the first large conditional coordinator; source resolution, rendering, and delivery already have distinct retry classifications | Email worker implementation | Final Detekt gate plus retry-state test matrix | Split the coordinator into `resolve`, `render`, and `deliver`; no shared worker abstraction was created because no second module needs it |
| Designing a distributed queue before a concrete producer exists creates speculative interface and schema complexity | The first implementation added caller keys, two hashes, three lifecycle states, leases, fencing, per-job scheduling, three indexes, and tests for multiple workers; none was required by an installed producer or current deployment | Email durable delivery design; reusable migration heuristic | Review proposed concurrency and identity fields against current consumers and deployment before implementation | Simplified Email to a unique typed reference and one active worker; record remains module-specific because the canonical guide already says to avoid speculative infrastructure |

No finding met the promotion threshold for changing the canonical migration
guide or migration skill in this run. The findings are concrete but remain
module-specific or have only one observed occurrence.
