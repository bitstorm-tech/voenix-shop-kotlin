package shop.voenix.payment

/**
 * The payment provider, in the three calls this shop makes of it.
 *
 * Every way a call can go wrong ends in `null` or `false`: a refusal, an unreadable answer, a
 * timeout, an unreachable host, and a status word this module does not know all mean the same thing
 * to the service — "Mollie did not tell me anything I can act on" — so the distinction lives in the
 * log instead of in the type. Only a `CancellationException` passes through, because the request
 * ending is not a provider failure.
 *
 * The port exists mainly so the service's decisions can be tested without a network, but it is also
 * where the repo's provider-logging rule is enforceable: no implementation of it may put a provider
 * body, an error message from a decoder, or an unknown status value into a log line. That rule is
 * the reason this module talks to Mollie by hand instead of through an SDK.
 */
internal interface MolliePayments {
    /**
     * Creates the payment the customer is about to be sent to, or answers `null` when Mollie
     * refused, was unreachable, or answered without a checkout URL.
     *
     * [idempotencyKey] is fresh for every attempt. Mollie caches a key's answer for an hour and
     * replays it for an identical repeat, refuses the same key with different parameters with
     * `400`, and refuses a concurrent second use with `409` — so a per-attempt key is the one
     * choice that can never collide with those rules while still protecting a retried *transport*
     * from creating two payments.
     */
    suspend fun create(
        request: PaymentRequest,
        idempotencyKey: String,
    ): MolliePayment?

    /** What Mollie currently says about a payment, or `null` when it cannot say anything usable. */
    suspend fun find(molliePaymentId: String): MolliePayment?

    /**
     * Best-effort cancellation of a payment nobody will ever be sent to.
     *
     * `false` is not an error the caller acts on — the payment may already be paid, already
     * cancelled, or Mollie may be down — it only says the attempt did not succeed, which is worth a
     * log line and nothing more.
     */
    suspend fun cancel(molliePaymentId: String): Boolean
}
