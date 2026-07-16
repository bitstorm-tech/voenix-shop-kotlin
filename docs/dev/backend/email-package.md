# Email module

The Email module renders and delivers the shop's transactional emails. It has
two deliberately different entry points:

- `UserEmailSender` sends an email directly for an interactive user action;
- `EmailOutbox` stores a durable business reference for unattended delivery.

Keeping these entry points separate prevents a confirmation or password-reset
link from accidentally entering the database, and prevents an Order or SFTP
notification from bypassing durable retries.

The Kotlin code lives in
[`backend/modules/email`](../../../backend/modules/email). The Flyway-owned
queue table is created by
[`V5__create_email_jobs.sql`](../../../backend/modules/platform/resources/db/migration/V5__create_email_jobs.sql).

## Package structure

The root package `shop.voenix.email` contains the public module interface:
message values, producer capabilities, settings, and runtime composition. A
caller therefore does not need to know how rendering, Sweego delivery, or the
durable worker are implemented.

The internal implementation is grouped by responsibility:

| Package | Responsibility |
| --- | --- |
| `shop.voenix.email.rendering` | Turns typed email values into provider-neutral HTML and plain text. |
| `shop.voenix.email.delivery` | Defines the internal delivery seam and implements its Sweego adapter. |
| `shop.voenix.email.outbox` | Persists, claims, retries, and completes durable email jobs. |

These packages organize the implementation; they are not separate Kotlin
modules. The `email` compilation module remains the actual visibility boundary,
so its `internal` declarations can collaborate across all three packages but
cannot be imported by Auth, Order, SFTP, or the application module.

## Direct user emails

The five `UserEmail` variants are account confirmation, change-email
confirmation, password reset, password-changed notification, and the warning
sent to the old address during an email change.

A future Auth operation creates a validated `EmailRecipient`, builds a complete
`EmailActionUrl`, and calls the capability:

```kotlin
userEmails.send(
    UserEmail.AccountConfirmation(
        recipient = EmailRecipient("customer@example.com"),
        confirmationUrl = EmailActionUrl(completeEncodedUrl),
    )
)
```

`EmailActionUrl.toString()` is redacted, so accidentally logging the value does
not reveal a confirmation or reset token. Email renders the URL, but never logs
or persists it.

When Email is disabled, a direct send is a no-op. When enabled, it makes exactly
one Sweego request. A successful call means that Sweego accepted the request;
it does not prove mailbox delivery. A provider or timeout failure becomes the
secret-free `EmailDeliveryException`. The owning Auth operation decides whether
that email is required or best effort.

## Durable queued emails

Only Order confirmations and producer PDF notifications use `EmailOutbox`.
The producer supplies a stable, namespaced idempotency key and a reference:

```kotlin
outbox.enqueue(
    idempotencyKey = "order:confirmation:v1:$orderId",
    reference = QueuedEmailReference.OrderConfirmation(orderId),
)
```

This call must run inside the producer's existing Exposed transaction. Email
joins that transaction and never opens or commits an independent transaction.
If the business change rolls back, its Email job rolls back too.

The database stores SHA-256 hashes of the idempotency key and intent, the email
kind, and a positive source ID. It does not store recipients, names, subjects,
template values, HTML, plain text, or Auth URLs. Submitting the same key and
intent again returns the existing job ID. Reusing a key for a different intent
is an integration error and aborts the caller's transaction.

## Worker lifecycle

`QueuedEmailSource` is implemented later by the Order and SFTP owning modules.
For every processing attempt it resolves the current recipient and current
business values. The worker then renders the current template and delivers it.
Changing an address or template before a retry therefore changes the next
attempt without rewriting persisted message data.

The worker uses these database states:

| State | Meaning |
| --- | --- |
| `PENDING` | The job is waiting for its next eligible scan. Disabled Email jobs stay here. |
| `PROCESSING` | One worker owns an expiring lease for the job. |
| `TRANSMITTED` | Sweego accepted a request. Mailbox delivery is not proven. |

Workers claim at most 100 rows with PostgreSQL `FOR UPDATE SKIP LOCKED`, process
them outside the claim transaction, and condition completion on the lease
token. A scan drains all currently due batches before waiting for the next
poll. The default poll interval is five minutes and the lease is two minutes.

Every unsuccessful attempt returns to `PENDING`, increments `retry_count`, and
stores only a bounded safe error code and message. There is no retry maximum or
terminal `FAILED` state. An expired lease records `AMBIGUOUS_PROCESS_LOSS`
because Sweego might have accepted the request before the process stopped.

The queue guarantees at-least-once delivery, not exactly-once delivery. The
database prevents duplicate jobs and simultaneous claims, but it cannot close
the crash window between Sweego acceptance and the `TRANSMITTED` update. The
stable `campaign-id` is correlation metadata, not a claimed provider
idempotency guarantee.

## Rendering and provider boundary

FreeMarker loads UTF-8 HTML and plain-text templates from the Email module's
classpath resources. HTML templates auto-escape dynamic values and reuse one
branded layout. Subjects and the two bodies remain provider-neutral until the
internal Sweego adapter builds its JSON request.

The adapter always targets `https://api.sweego.io/send`, refuses redirects,
uses request/connect/socket timeouts of 30/10/30 seconds, and sends both HTML
and text with `campaign-type: transac`. It drains but does not parse, persist,
or log provider response bodies.

## Configuration

The safe committed defaults keep delivery disabled:

```yaml
Email:
  Enabled: false
  PollIntervalMinutes: 5
  ApiKey: ""
  FromEmail: ""
  FromName: "Voenix Shop"
```

The development launcher reads these environment variables:

```dotenv
EMAIL_ENABLED=false
EMAIL_POLL_INTERVAL_MINUTES=5
SWEEGO_API_KEY=
EMAIL_FROM_ADDRESS=
EMAIL_FROM_NAME='Voenix Shop'
```

API key and sender address are required only when `EMAIL_ENABLED=true`.
Configuration errors and `EmailSettings.toString()` never include the API key.
The polling interval must be between 1 and 1,440 minutes.

The application has the Email module dependency and installation seam, but it
does not start Email yet. Order and SFTP have not been migrated and cannot
supply a real `QueuedEmailSource`; installing a placeholder source would hide
missing product composition. Their deferred work is recorded in
[`email-post-migration.md`](../../migration/email-post-migration.md).

## Operations and manual cleanup

There is intentionally no public Email HTTP route, automatic cleanup worker,
or generic job framework. Operational logs use job ID, kind, retry count,
state, and safe error code only.

Manual deletion has product consequences:

- deleting a pending row cancels its future delivery;
- deleting a transmitted tombstone removes duplicate protection if the
  business event is replayed.

An authenticated operations UI, retry/cancel commands, alerts, and delivery
webhooks should be added only with a concrete support workflow.
