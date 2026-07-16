# Email post-migration to-do list

This file owns work that must wait for modules which consume Email capabilities
or operate Email jobs. The standalone Email migration is defined in
[`email-migration.md`](email-migration.md).

Do not create placeholder User, Order, Payment, SFTP, PDF, or operational UI
types inside Email merely to complete these items early. Auth owns its
retriggerable confirmation/reset/change-email flows and its best-effort warning
policy; Email sends those messages directly. Order and SFTP own the business
event, transaction boundary, and idempotency key for their queued messages.
Email owns rendering, direct provider integration, and durable delivery for the
two queued types.

## Future notification story

Joe deferred the product-level email-trigger design on 2026-07-16. This is a
separate story after the standalone Email migration; it must not be solved
incrementally while wiring the technical Email module.

- [ ] Inventory all situations in which customers, producers, or operators
  should receive an email. Use the .NET behavior as evidence, not as the final
  product specification, and include missing but product-useful notifications.
- [ ] For every situation, decide the exact business trigger, recipient,
  owning module, template/content, direct versus durable delivery, failure
  impact, user/admin resend path, duplicate tolerance, and whether an Admin
  alert is needed.
- [ ] For every durable notification, define a stable source reference,
  idempotency key, transaction boundary, current-data resolution policy, and
  tests for repeated or concurrent trigger delivery.
- [ ] Revisit the Order confirmation explicitly. The current .NET code
  enqueues it after `PaidOrderProcessor` marks the Order `PAID`, while Joe does
  not expect payment to be the product trigger. Decide whether Kotlin sends it
  at checkout/order completion, after payment, through another event, or not
  automatically.
- [ ] Add new email kinds only when this inventory establishes a real product
  event and owner. Do not generalize every email into persistence: retriggerable
  user interactions may still use direct delivery, while unattended required
  notifications may need durable jobs.
- [ ] Record the chosen notification matrix and end-to-end acceptance tests in
  the owning future migrations before connecting their producers to Email.

## Auth email composition

- [ ] When the application-owned Auth feature is migrated, make its module
  depend on the exported `UserEmailSender` capability rather than the Sweego
  adapter, renderer, repository, `EmailOutbox`, or Email job table.
- [ ] Move `FrontendBaseUrl` into Auth configuration. Build and percent-encode
  complete confirmation, change-email, and reset URLs in Auth, require HTTPS in
  non-local environments, and construct `EmailActionUrl` before creating the
  typed `UserEmail` value.
- [ ] Send account confirmation, change-email confirmation, password reset,
  password-changed notification, and old-address warning directly. These five
  email kinds must not create `email_jobs` rows or receive automatic worker
  retries.
- [ ] Preserve the user-facing resend operations. A deliberate resend is a new
  direct send; there is no persisted Email job to reopen or retry.
- [ ] Preserve enumeration-safe resend-confirmation and forgot-password
  responses. Whether an account exists must not be observable through response
  shape, timing tests, or Email errors.
- [ ] Preserve caller-cancellation behavior. Decide in Auth whether password-
  changed and old-address warnings remain best effort when direct delivery
  fails; do not hide cancellation or required confirmation failures.
- [ ] Map public `EmailDeliveryException` as an external email dependency
  failure, preserving the source's `502` distinction where Auth exposes an HTTP
  failure. Treat renderer/programming failures as internal `500` outcomes and
  never expose exception text, recipient data, or provider details.
- [ ] Preserve the approved interactive timeout contract. With the
  30-second Email request budget, required confirmation/reset/change-email
  flows receive a delivery failure instead of waiting for the source client's
  inherited 100-second timeout; optional warnings may remain best effort. A
  timeout must not be reported as proof that Sweego rejected the message.
- [ ] Interpret a successful direct call according to `enabled`: false means a
  no-op and true means Sweego request acceptance rather than mailbox delivery.
  If an enabled request times out ambiguously, a later user resend may produce
  a duplicate; do not claim exactly-once behavior for direct sends.
- [ ] Verify that template statements about token lifetime match the Kotlin
  Auth token policy. The source confirmation templates say 24 hours; Email must
  not own or guess token expiry.
- [ ] Add integration tests for enumeration resistance, direct-delivery failure
  and cancellation, confirmation resend, password reset, password change, both
  sides of change-email, disabled no-op behavior, and the invariant that Auth
  sends never create an Email job.

## Order-confirmation trigger and composition

- [ ] Resolve the source/product contradiction in the future notification story
  before wiring an Order producer. The current .NET `PaidOrderProcessor`
  enqueues the confirmation after changing the Order to `PAID`; Mollie `paid`
  handling and zero-total checkout call it. Joe stated on 2026-07-16 that
  becoming paid should not trigger an email.
- [ ] Decide whether Kotlin enqueues the confirmation at Order/checkout
  completion, after confirmed payment, through another explicit event, or not
  automatically. Put `EmailOutbox` into the owning operation only after that
  decision, without exposing Email persistence or provider types.
- [ ] Enqueue `QueuedEmailReference.OrderConfirmation(orderId)` and no rendered
  message, recipient, subject, or placeholder values. The reference and its
  idempotency key are the durable notification intent.
- [ ] Implement the Order branch of `QueuedEmailSource`. For every claimed
  cycle, load the current account email address plus the authoritative stored
  Order values and return a process-only `QueuedEmail.OrderConfirmation` for
  rendering. A changed email address must affect the next attempt.
- [ ] Convert the Order creation instant to the approved business calendar date
  whenever resolving the Email model. The approved zone is
  `Europe/Berlin`; test both sides of UTC/local midnight rather than letting
  Email or the database default zone choose implicitly.
- [ ] Once the trigger is chosen, insert the Email job in the same PostgreSQL
  transaction as that durable business event whenever both share the database.
  A rollback must leave neither the event nor its notification intent.
- [x] Make `EmailOutbox.enqueue` join the caller's Exposed transaction. Prove
  that it does not commit independently and that an insert failure leaves the
  chosen trigger event retryable. Resolution or rendering happens later and
  follows the worker retry path.
- [ ] Derive a stable idempotency key from the chosen Order-confirmation event
  so repeated webhooks or commands return the existing Email job rather than
  enqueueing duplicates. The namespaced key must not contain personal data or
  credentials; Email persists only its digest.
- [ ] Preserve item order deliberately. The source relies on the loaded
  collection order without an explicit database order, so the Order migration
  must define an authoritative line order before constructing the email.
- [ ] Add PostgreSQL tests for the chosen trigger, atomic commit/rollback,
  repeated trigger events,
  changed recipient and placeholder/template values between attempts, missing
  or invalid Order data, German totals, and worker delivery of the resulting
  job. Confirm that `email_jobs` contains no message or recipient data.
- [ ] If the product needs an admin resend action, model it as a new authorized
  Order-owned business command with a new idempotency key. Do not restore the
  unauthenticated development Email route.

## SFTP producer notification

- [ ] When SFTP upload is migrated, enqueue
  `QueuedEmailReference.ProducerPdfNotification(uploadTaskId)` only after one
  server upload succeeds and its durable upload state is authoritative.
- [ ] Implement the SFTP branch of `QueuedEmailSource`. Resolve current producer
  email/name from a stable server or producer identity attached to the upload
  task, not by searching configuration for a previously stored email address.
  Also resolve the upload filename/server and authoritative Order date/item
  count for the process-only template model.
- [ ] Reuse the same approved Order calendar date semantics as the customer
  confirmation; producer notification and Order confirmation must not display
  different dates for one Order.
- [ ] Derive the Email idempotency key from the durable SFTP upload task or
  upload-success event so two servers sharing one producer address still own
  distinct notifications. Keep personal data and credentials out of the key;
  Email persists only its digest.
- [ ] Keep the current best-effort relationship explicit: an Email enqueue
  failure must not falsely describe the external upload as failed. The source
  logs and swallows this failure after marking the upload successful, which can
  permanently lose the notification intent.
- [ ] Choose a recoverable Kotlin boundary after successful external upload:
  either commit the database upload-success state and Email job together, with
  lease recovery prepared for a possible repeated upload, or persist a separate
  notification-pending action that can enqueue the Email job later. PostgreSQL
  cannot be atomic with the SFTP server; merely logging an enqueue failure is
  not durable recovery.
- [ ] Add tests for blank producer email, disabled server, failed upload, one
  message per successful server, same-recipient servers, a changed producer
  address between retries, missing source data, retries, and caller
  cancellation.

## Operations, delivery feedback, and retention

- [x] Document and expose the scheduler's `pollIntervalMinutes`; the Email
  migration uses five minutes by default. Each non-overlapping scan
  drains all due jobs in bounded batches, while multiple instances coordinate
  through row locks and leases rather than an external broker.
- [ ] Preserve truthful queued status semantics in every operational view:
  `PENDING` includes jobs waiting while Email is disabled, `PROCESSING` has an
  active lease, and `TRANSMITTED` means Sweego accepted a request but has not
  proven mailbox delivery.
- [ ] Expose pending durable jobs with their `retry_count`, last safe error, and
  next eligible time. There is no automatic maximum and no terminal `FAILED`;
  every unsuccessful job remains `PENDING` and is retried by the scheduler.
- [ ] Add the Admin alert requested by Joe when a nonterminal job has
  `retry_count` greater than the configured threshold. Do not alert for an
  `TRANSMITTED` job merely because it has historical retries.
- [ ] Present a lease-recovered `AMBIGUOUS_PROCESS_LOSS` as an unknown provider
  outcome, not proof of non-delivery. Recovery increments `retry_count` and may
  cause the rare duplicate accepted on 2026-07-16.
- [ ] Measure Sweego request latency and timeout frequency before making the
  adapter's 30-second request, 10-second connect, and 30-second socket defaults
  configurable. Add deployment settings only when operations has a concrete
  tuning need.
- [ ] Keep automatic retries restricted to Order confirmation and producer PDF
  notification jobs. Operational tooling must not suggest that Auth/user emails
  have a persisted retry counter; their resend actions create new direct sends.
- [ ] Keep Email logs free of recipients, subjects, rendered bodies, token URLs,
  API keys, and raw provider responses. Use job ID, kind, retry count, state,
  and bounded error code for correlation; let Order/SFTP own business-event
  logging under their own retention policy.
- [x] Verify `enabled` changes across a restart. Durable jobs created while
  disabled remain `PENDING` without source resolution or provider access and
  resume when Email is explicitly enabled.
- [ ] Keep the reference-only persistence rule visible in operational tooling:
  `email_jobs` contains no recipient, subject, placeholder data, HTML, or text.
  The Admin view may resolve a safe current recipient summary only if a real
  support workflow later requires it and authorization permits it.
- [ ] Because retries have no maximum, provide an authorized resolution or
  cancellation path for jobs whose source reference can no longer be resolved.
  This prevents a permanent alert loop; it is no longer needed to clean up a
  persisted message payload because no such payload exists.
- [ ] Do not build an automatic retention or cleanup system for `email_jobs`.
  Joe decided on 2026-07-16 that operations cleans the table manually when
  needed. The runbook must state the consequences: deleting a terminal
  tombstone removes duplicate protection if its source event is replayed;
  deleting a pending row cancels its future delivery.
- [ ] Add an authenticated operational inspection/retry interface only when a
  real support workflow exists. It must not expose process-only rendered
  bodies, API keys, unrestricted provider errors, or provider-specific DTOs.
  Auth token links never belong to this interface because they are not
  persisted.
- [ ] If manual retry is added, define whether it reuses the original provider
  correlation identity or creates a new delivery event. Do not silently turn a
  terminal idempotent job back into a second send.
- [ ] Evaluate Sweego delivery webhooks for accepted, delivered, bounced, and
  complained states when the product needs delivery observability. Provider
  feedback is a separate inbound integration; worker `TRANSMITTED` means only that
  Sweego accepted a request.
- [ ] Confirm with Sweego whether `campaign-id` has a contractual duplicate-
  suppression guarantee. The public documentation checked on 2026-07-15
  defines it as tracking metadata, not an idempotency contract. Until Sweego
  confirms a stronger guarantee contractually, keep queued email delivery at
  least once.
- [ ] Run an explicitly authorized Sweego dry-run smoke test before production
  credentials are enabled. Automated quality gates must continue to use a
  local adapter and never send real email.

## Reusable background-job infrastructure

- [ ] When a second durable-job module such as SFTP is migrated, compare its
  claim, lease, retry, cancellation, and cleanup requirements with Email's
  implementation.
- [ ] Extract shared infrastructure only when both modules need the same policy
  for the same reason. Keep provider payloads, status meaning, retry
  classification, and business idempotency inside their owning modules.
