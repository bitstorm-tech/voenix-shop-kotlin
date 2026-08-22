# Email post-migration to-do list

This file owns work that must wait for modules which consume Email capabilities
or operate Email jobs. The standalone Email migration is defined in
[`email-migration.md`](email-migration.md).

Do not create placeholder User, Order, Payment, SFTP, PDF, or operational UI
types inside Email merely to complete these items early. Auth owns its
retriggerable confirmation/reset/change-email flows and its best-effort warning
policy; Email sends those messages directly. Order and SFTP own the business
event, transaction boundary, and stable source reference for their queued messages.
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
  transaction boundary, current-data resolution policy, and tests for repeated
  trigger delivery. The source reference must uniquely identify one intended
  message because Email uses `(email_kind, source_id)` as the job identity.
- [x] Revisit the Order confirmation explicitly. Decided by Joe on 2026-07-31
  (deviation D23 in [`order-migration.md`](order-migration.md)): the trigger
  **is** `PAID`, and the job is enqueued inside the `markPaid` transaction. The
  2026-07-16 statement that becoming paid should not trigger an email is
  superseded; the contradiction this file recorded is resolved. The remaining
  bullets of this inventory still apply to every *other* notification.
- [ ] Add new email kinds only when this inventory establishes a real product
  event and owner. Do not generalize every email into persistence: retriggerable
  user interactions may still use direct delivery, while unattended required
  notifications may need durable jobs.
- [ ] Record the chosen notification matrix and end-to-end acceptance tests in
  the owning future migrations before connecting their producers to Email.

## Application runtime composition

Done as the inherited composition work of the Account migration (2026-07-23,
GitHub issue #6): the application operates the Email runtime. The last open
branch, the order confirmation, was bound by the Order migration on 2026-07-31
(see "Order-confirmation trigger and composition" below), so the aggregate has
no unbound variant left in a started application.

- [x] Load `EmailSettings` in the application composition root, assemble the
  real `QueuedEmailSource`, call `installEmailModule` exactly once, and pass
  only `EmailModule.userEmails` and `EmailModule.outbox` to the modules that
  consume those capabilities. An invalid enabled configuration fails the
  startup before Flyway touches the database.
- [x] Start the queued worker only when its `QueuedEmailSource` can resolve
  every queued reference kind that the composed application can enqueue.
  The worker launches on `ApplicationStarted`, after the composition root has
  bound both branches of the app-owned aggregate — Production's
  producer-notification resolver and, since the Order migration, the order
  module's confirmation resolver. A branch that is not bound yet fails loudly
  and retryably (`SOURCE_UNAVAILABLE`) instead of dropping or faking a job,
  which now only covers the milliseconds of startup between the two installs.
- [x] Deploy exactly one active queued Email worker. The application installs
  one worker per process and is deployed as a single process. If the
  application later needs multiple active instances, design claim coordination
  from measured deployment requirements before enabling the worker in more
  than one process.
- [x] If Auth needs direct `UserEmailSender` delivery before Order or SFTP can
  provide a real queued source, split direct-delivery composition from queued
  worker startup through an explicit runtime seam. Not needed: Production
  supplied the first real queued branch, the aggregate's retryable unbound
  behavior covered the order-confirmation kind until the Order migration bound
  it, and direct delivery and the queued worker share one installation without
  a dummy source. The wiring itself now lives in the app's `installEmailRuntime`,
  which `Application.kt` and the composition tests share.
- [x] Add an application-composition test that proves Email is installed once,
  the exported capabilities reach their consumers, startup fails cleanly on
  invalid enabled configuration, and application shutdown cancels the worker
  and closes the provider client. `EmailRuntimeCompositionIntegrationTest`
  proves the composed wiring end to end (a queued producer notification is
  delivered against real PostgreSQL through the real adapter pointed at a
  local stub); `EmailModuleTest` proves install-once, shutdown cancellation,
  and provider-client close at the module seam, and
  `ApplicationDatabaseIntegrationTest` proves the clean startup failure.
- [x] Update `docs/dev/backend/packages/email-package.md` and
  `docs/dev/backend/conventions/module-architecture.md` when the application begins
  installing Email, so they no longer describe the runtime as deferred.

## Auth email composition

The application-owned Auth feature became the `account` module, which the
Account migration put on `main` on 2026-07-24; see
[`account-migration.md`](account-migration.md). That migration built almost
everything this section asked for, but nobody ticked the boxes at the time. The
list below was checked bullet by bullet against the code on 2026-08-11. Two
bullets were narrower than what they demanded; both were closed on the same
day — the `500`/`502` separation was built, and the enumeration timing channel
was decided to stay open on purpose. Nothing in this section is open now.

- [x] When the application-owned Auth feature is migrated, make its module
  depend on the exported `UserEmailSender` capability rather than the Sweego
  adapter, renderer, repository, `EmailOutbox`, or Email job table. Delivered by
  the Account migration (2026-07-24), verified 2026-08-11: `AccountMailer` and
  `AccountModule` import nothing from Email except `UserEmailSender`,
  `UserEmail`, `EmailRecipient`, and `EmailActionUrl`, and `Application.kt`
  hands the module `emails.userEmails` only. The adapter, renderer, and job
  repository are `internal` to Email, so the module could not reach them.
- [x] Move `FrontendBaseUrl` into Auth configuration. Build and percent-encode
  complete confirmation, change-email, and reset URLs in Auth, require HTTPS in
  non-local environments, and construct `EmailActionUrl` before creating the
  typed `UserEmail` value. Delivered by the Account migration (2026-07-24),
  verified 2026-08-11: `AccountSettings.frontendBaseUrl` is a required
  `account.*` configuration value that rejects non-HTTPS hosts outside
  `localhost` (`AccountSettingsTest`), and `AccountMailer.actionUrl` builds each
  link with `encodeURLParameter` and wraps it in `EmailActionUrl` at the call
  site of `UserEmailSender.send`.
- [x] Send account confirmation, change-email confirmation, password reset,
  password-changed notification, and old-address warning directly. These five
  email kinds must not create `email_jobs` rows or receive automatic worker
  retries. Delivered by the Account migration (2026-07-24), verified
  2026-08-11: `AccountMailer` sends exactly these five `UserEmail` variants
  through `UserEmailSender`, `EmailService.send` never touches
  `EmailJobRepository`, and `AccountServiceIntegrationTest` asserts
  `countRows("email_jobs") == 0` after a registration against real PostgreSQL.
- [x] Preserve the user-facing resend operations. A deliberate resend is a new
  direct send; there is no persisted Email job to reopen or retry. Delivered by
  the Account migration (2026-07-24), verified 2026-08-11:
  `AccountService.resendConfirmation` and `forgotPassword` issue a fresh token
  and send again directly. `AccountServiceIntegrationTest` proves that a
  reissued link invalidates the previous one, and `AccountFlowIntegrationTest`
  walks the recovery path in "a failed required delivery answers 502 and the
  resend flow recovers".
- [x] Preserve enumeration-safe resend-confirmation and forgot-password
  responses. Whether an account exists must not be observable through response
  shape, timing tests, or Email errors. Two of the three channels are closed
  since the Account migration (2026-07-24): the response is always `204`
  regardless of the account state (`AccountFlowIntegrationTest`), and
  `AccountService.enumerationSafe` swallows delivery and database failures
  (`AccountServiceIntegrationTest`). The timing channel stays open. Token
  issuing and the Sweego request happen synchronously on the request thread
  only for existing accounts, so the latency of the two branches differs.
  **Decided by Joe on 2026-08-11: accepted permanently, not built.** This is an
  e-commerce shop, not a high-security site, and the timing difference is a
  tolerable leak; the send stays synchronous on the request path rather than
  buying an asynchronous send queue for it. The response shape and the
  swallowed Email errors remain closed and must stay closed — timing is the
  only channel left open, and it is open on purpose.
- [x] Preserve caller-cancellation behavior. Decide in Auth whether password-
  changed and old-address warnings remain best effort when direct delivery
  fails; do not hide cancellation or required confirmation failures. Delivered
  by the Account migration (2026-07-24), verified 2026-08-11: `AccountMailer`
  decided both warnings are best effort and only logs their failures, while
  every `catch` rethrows `CancellationException` before handling `Exception`.
  `AccountServiceIntegrationTest` ("cancellation is rethrown instead of being
  converted into a result") and `UserEmailSenderTest` ("caller cancellation
  propagates") hold that line; required confirmations still surface as `502`.
- [x] Map public `EmailDeliveryException` as an external email dependency
  failure, preserving the source's `502` distinction where Auth exposes an HTTP
  failure. Treat renderer/programming failures as internal `500` outcomes and
  never expose exception text, recipient data, or provider details. The `502`
  half and the secrecy half were done by the Account migration (2026-07-24):
  `RegisterResult.DeliveryFailed` and `ChangeEmailResult.DeliveryFailed` become
  `HttpStatusCode.BadGateway` with a fixed message in `AccountRoutes`, and
  `EmailDeliveryException` carries no provider text at all
  (`UserEmailSenderTest`). **Done on 2026-08-11** for the `500` half:
  `AccountMailer` no longer catches every `Exception` in its two required
  sends — `sendAccountConfirmation` and the confirmation half of
  `sendChangeEmail` catch `EmailDeliveryException` and nothing else. Anything
  else a send throws now travels on to the caller's `databaseOperation` guard,
  which logs it and answers `RegisterResult.UnexpectedFailure` respectively
  `ChangeEmailResult.UnexpectedFailure`; `AccountRoutes` turns those into a
  plain `500` with the fixed message `Internal server error`, so no exception
  text, recipient address, or provider detail reaches the client. The narrow
  catch also makes the `CancellationException` rethrow unnecessary in those two
  paths: cancellation is not an `EmailDeliveryException` and passes through by
  construction. `EmailDeliveryException`'s constructor became public in the
  same change, because a test fake of the public `UserEmailSender` interface
  must be able to signal the provider failure. Two account tests hold the line:
  `AccountServiceIntegrationTest` ("a mail failure that is not the provider
  becomes an internal failure, not a 502") and `AccountFlowIntegrationTest`
  ("a mail bug of ours answers 500 without leaking the recipient or the
  cause"). Deliberately unchanged: the **best-effort** sends (password-changed
  notification, old-address warning) keep their broad catch with the
  `CancellationException` rethrow. Their business change is already stored, so
  a bug in a notification must not fail it either — the same
  `IllegalStateException` that makes a registration answer `500` still leaves a
  password change answering `204`.
- [x] Preserve the approved interactive timeout contract. With the
  30-second Email request budget, required confirmation/reset/change-email
  flows receive a delivery failure instead of waiting for the source client's
  inherited 100-second timeout; optional warnings may remain best effort. A
  timeout must not be reported as proof that Sweego rejected the message.
  Verified 2026-08-11: `SweegoEmailDelivery` configures a 30-second request and
  socket timeout and a 10-second connect timeout, so a required flow answers
  `502` inside that budget. A timeout becomes its own failure code
  (`REQUEST_TIMEOUT`, `CONNECT_TIMEOUT`, `SOCKET_TIMEOUT`) instead of a
  `PROVIDER_HTTP_*` rejection, and the exception the caller sees says only that
  acceptance was not confirmed.
- [x] Interpret a successful direct call according to `enabled`: false means a
  no-op and true means Sweego request acceptance rather than mailbox delivery.
  If an enabled request times out ambiguously, a later user resend may produce
  a duplicate; do not claim exactly-once behavior for direct sends. Verified
  2026-08-11: `EmailService.send` returns before rendering when `enabled` is
  false, and one enabled send makes exactly one provider request without a
  retry — both proved by `UserEmailSenderTest`. `docs/dev/backend/packages/email-package.md`
  states the interpretation for readers and claims at-least-once only for the
  queued path.
- [x] Verify that template statements about token lifetime match the Kotlin
  Auth token policy. The source confirmation templates say 24 hours; Email must
  not own or guess token expiry. Verified 2026-08-11: `AccountService` issues
  every token with `TOKEN_LIFETIME_HOURS = 24`, which
  `AccountFlowIntegrationTest` proves through a clock the test advances, and the
  two confirmation templates say exactly "24 Stunden" while
  `PasswordResetEmailTemplate` avoids a number. The sentence still lives in the
  Email templates, so changing the account token policy means changing those two
  templates in the same commit.
- [x] Add integration tests for enumeration resistance, direct-delivery failure
  and cancellation, confirmation resend, password reset, password change, both
  sides of change-email, disabled no-op behavior, and the invariant that Auth
  sends never create an Email job. Delivered by the Account migration
  (2026-07-24), verified 2026-08-11: `AccountServiceIntegrationTest` covers
  enumeration resistance for unknown and already confirmed accounts, the failing
  sender, cancellation, resend, reset, password change, both change-email
  mails, and the `email_jobs` invariant; `AccountFlowIntegrationTest` walks the
  same flows over HTTP. The disabled no-op is proved one layer down by the Email
  module's `UserEmailSenderTest` rather than by an account test, and the
  composed application relies on it in `LoginClaimCompositionIntegrationTest`,
  whose registrations answer `204` with email disabled.

## Order-confirmation trigger and composition

Delivered by the Order migration on 2026-07-31; see
[`order-migration.md`](order-migration.md).

- [x] Resolve the source/product contradiction in the future notification
  story. Joe decided on 2026-07-31 (deviation D23) that the trigger is `PAID`,
  which is the legacy behavior and supersedes his 2026-07-16 statement. The
  reasoning: a confirmation before the money arrived would confirm something
  that may never happen.
- [x] Decide whether Kotlin enqueues the confirmation at Order/checkout
  completion, after confirmed payment, through another explicit event, or not
  automatically. It is enqueued **after confirmed payment**, inside the
  `markPaid` transaction. `EmailOutbox` is a constructor capability of
  `OrderService`; no Email persistence or provider type crosses the boundary.
- [x] Enqueue `QueuedEmailReference.OrderConfirmation(orderId)` and no rendered
  message, recipient, subject, or placeholder values. The reference and its
  unique kind/source pair are the durable notification intent.
- [x] Implement the Order branch of `QueuedEmailSource`.
  `OrderService.orderConfirmation` reads the stored order again on every
  attempt and returns a process-only `QueuedEmail.OrderConfirmation`. One
  detail differs from the wording above: the recipient is the **order's own**
  e-mail column, not the account's current address — an order may have been
  placed by a guest, and what was confirmed is the address the customer gave
  for that order. A correction to that column reaches the next attempt, which
  is what `OrderConfirmationMailTest` pins.
- [x] Convert the Order creation instant to the approved business calendar
  date whenever resolving the Email model. `StoredOrder.orderDate` converts
  `created_at` to `Europe/Berlin`, and the same value feeds the producer
  notification, so the two can never name different days. Both sides of
  midnight are tested.
- [x] Once the trigger is chosen, insert the Email job in the same PostgreSQL
  transaction as that durable business event whenever both share the database.
  The enqueue joins the `markPaid` transaction: a rollback leaves neither the
  paid order nor its notification intent, proven in
  `OrderPaymentIntegrationTest`.
- [x] Make `EmailOutbox.enqueue` join the caller's Exposed transaction. Prove
  that it does not commit independently and that an insert failure leaves the
  chosen trigger event retryable. Resolution or rendering happens later and
  follows the worker retry path.
- [x] Verify that one Order ID represents exactly one automatic Order
  confirmation. Two rules guarantee it: `markPaid` answers `AlreadyPaid`
  without doing anything a second time, and the unique kind/source pair returns
  the existing job if it is enqueued again. If the product ever needs several
  distinct automatic confirmations for one order, introduce a durable event id
  as the source reference instead of adding an independent key.
- [x] Preserve item order deliberately. `order_items.position` is written at
  placement with `UNIQUE (order_id, position)` and every read orders by it, so
  the mail, the production PDF, and the customer's own view list the lines the
  way the customer put them together (deviation D20).
- [x] Add PostgreSQL tests for the chosen trigger, atomic commit/rollback,
  repeated trigger events, changed recipient and placeholder/template values
  between attempts, missing or invalid Order data, and worker delivery of the
  resulting job. `OrderPaymentIntegrationTest` covers the trigger and the
  rollback, `OrderConfirmationMailTest` the per-attempt resolution, and
  `OrderConfirmationRuntimeIntegrationTest` the delivery through the real
  worker and adapter. The German amount formatting is covered by
  `EmailRendererTest`, which the Order migration extended with the 100 %-coupon
  case that the relaxed `total >= 0` invariant made possible (deviation D12).
- [ ] If the product needs an admin resend action, model it as a new authorized
  Order-owned business command with a distinct durable resend event ID. Do not restore the
  unauthenticated development Email route.

## SFTP producer notification

Done with the Production migration (2026-07-23): the reference payload is now
the production delivery ID
(`QueuedEmailReference.ProducerPdfNotification(deliveryId)`); the legacy
upload-task meaning is gone without residue. Production enqueues the
notification through `EmailOutbox` in the same transaction that sets
`delivered_at` — stronger than the legacy best-effort behavior, because a
failed enqueue rolls the delivery completion back and both retry together.
Production resolves the reference (`ProductionModule.producerNotifications`; the
handle is called `ProductionModule.queuedEmails` since issue #119, which made it
production's one branch for the producer notification *and* the customer's
shipping notification), and the application composes the late-bound aggregated
`QueuedEmailSource`.
See the "Producer notification" section in
[production-package.md](../dev/backend/packages/production-package.md).

## Operations, delivery feedback, and retention

- [x] Document and expose the scheduler's `pollIntervalMinutes`; the Email
  migration uses five minutes by default. The single active worker scans every
  open job once per non-overlapping cycle.
- [ ] Preserve truthful queued status semantics in every operational view:
  `sent_at IS NULL` means open, and a populated `sent_at` means Sweego accepted
  a request but has not proven mailbox delivery.
- [ ] Expose open durable jobs with their `attempt_count` and last safe error.
  There is no automatic maximum or terminal failed state; every unsuccessful
  job is retried by the next scheduler scan.
- [ ] Add the Admin alert requested by Joe when an open job has
  `attempt_count` greater than the configured threshold. Do not alert for a
  sent job merely because it has historical attempts.
- [ ] Present a restart after an ambiguous provider call as an unknown outcome,
  not proof of non-delivery. A later scan may cause the rare duplicate accepted
  on 2026-07-16.
- [ ] Measure Sweego request latency and timeout frequency before making the
  adapter's 30-second request, 10-second connect, and 30-second socket defaults
  configurable. Add deployment settings only when operations has a concrete
  tuning need.
- [ ] Keep automatic retries restricted to Order confirmation and producer PDF
  notification jobs. Operational tooling must not suggest that Auth/user emails
  have a persisted attempt counter; their resend actions create new direct sends.
- [ ] Keep Email logs free of recipients, subjects, rendered bodies, token URLs,
  API keys, and raw provider responses. Use job ID, kind, attempt count, outcome,
  and bounded error code for correlation; let Order/SFTP own business-event
  logging under their own retention policy.
- [x] Verify `enabled` changes across a restart. Durable jobs created while
  disabled remain open without source resolution or provider access and
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
  deleting an open row cancels its future delivery.
- [ ] Add an authenticated operational inspection/retry interface only when a
  real support workflow exists. It must not expose process-only rendered
  bodies, API keys, unrestricted provider errors, or provider-specific DTOs.
  Auth token links never belong to this interface because they are not
  persisted.
- [ ] If manual retry is added, define whether it reuses the original provider
  correlation identity or creates a new delivery event. Do not silently turn a
  sent job back into a second send.
- [ ] Evaluate Sweego delivery webhooks for accepted, delivered, bounced, and
  complained states when the product needs delivery observability. Provider
  feedback is a separate inbound integration; `sent_at` means only that Sweego
  accepted a request.
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
  retry, cancellation, and cleanup requirements with Email's implementation.
- [ ] Add claim or lease coordination to Email only when deployment needs more
  than one active Email worker; do not extract speculative shared machinery.
- [ ] Extract shared infrastructure only when both modules need the same policy
  for the same reason. Keep provider payloads, status meaning, retry
  classification, and business identity inside their owning modules.
