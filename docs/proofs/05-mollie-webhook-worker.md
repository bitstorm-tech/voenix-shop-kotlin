# Mollie Webhook and Background Worker Proof

## Result

The Kotlin port now has a proof-only payment webhook path:

- `POST /payments/mollie/webhook` accepts Mollie's form callback shape: `id=<payment id>`.
- The route fetches payment state through `MolliePaymentStatusLookup`; it does not trust posted status.
- A paid Mollie payment marks the matching proof order as `paid`.
- The paid transition creates two persisted side-effect jobs: email and SFTP upload.
- Duplicate webhooks keep the order paid and do not duplicate side-effect jobs.
- `PaymentSideEffectWorker.runDueBatch` claims due jobs, calls fake/no-op sinks, records success, and reschedules failures.

No full payment, order, email, or SFTP workflow was ported.

## Reconciliation Approach

Mollie's webhook carries only the payment ID. The safe flow is:

1. Receive `id` from the form POST.
2. Fetch the payment from Mollie by ID.
3. Process only fetched `paid` state.
4. Match `mollie_payment_id` to a local proof order.
5. Persist order state and side-effect jobs in one DB transaction.

The proof uses an injected lookup interface, not a real Mollie HTTP client. A production port should add API credentials, HTTP timeout/retry policy, response mapping, and audit logging around this seam.

## Idempotency

The proof boundary is database-backed:

- `orders.mollie_payment_id` is unique when present.
- `payment_side_effects.idempotency_key` is unique.
- Duplicate side-effect inserts use insert-ignore semantics.
- Paid reconciliation always calls the same job keys: `order:<id>:email` and `order:<id>:sftp_upload`.
- Worker sinks receive the idempotency key they would pass to real external systems.
- Worker claims use row locks with `SKIP LOCKED` to keep concurrent workers from taking the same job.

This proves duplicate-event behavior for repeated webhook delivery and the core multi-worker claim boundary.

## Worker Lifecycle

`PaymentSideEffectWorker` is intentionally a batch runner, not a daemon:

- `runDueBatch(nowEpochSeconds)` processes one bounded batch.
- Failed jobs move to `failed` with `next_attempt_epoch_seconds`.
- Successful jobs move to `succeeded` and are not run again.
- In-progress jobs can be reclaimed by `reclaimInProgressSideEffects` after an owner-defined timeout.
- The app wires the webhook route only; no always-on background loop starts in this proof.

Production can wrap the batch runner in a hosted coroutine/service. Shutdown should stop accepting new batches, let the current batch finish or time out, and leave uncompleted jobs retryable.

## Retry Notes

- Lookup failure returns `503`, letting Mollie retry the webhook.
- Unknown or non-paid payments return `200` after no local transition.
- Side-effect failure is stored on the job and retried after the configured delay.
- Jobs are marked `in_progress` before the sink call; reclaiming abandoned jobs is explicit so production can choose the timeout.

## Sources

- Mollie webhook docs: https://docs.mollie.com/reference/webhooks
