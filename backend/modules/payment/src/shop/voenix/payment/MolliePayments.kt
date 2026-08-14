package shop.voenix.payment

import shop.voenix.order.OrderPaymentStatus
import shop.voenix.order.PayableOrder

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
     * [order] is the order module's stored snapshot, and turning it into what this provider wants —
     * one address line out of the shop's two fields, an E.164 phone number or none at all — is this
     * adapter's job. Nothing upstream of it shapes data for Mollie.
     *
     * [idempotencyKey] is fresh for every attempt. Mollie caches a key's answer for an hour and
     * replays it for an identical repeat, refuses the same key with different parameters with
     * `400`, and refuses a concurrent second use with `409` — so a per-attempt key is the one
     * choice that can never collide with those rules while still protecting a retried *transport*
     * from creating two payments.
     */
    suspend fun create(
        order: PayableOrder,
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

/**
 * What Mollie says about one payment, in the four facts this module uses.
 *
 * [amountCents] is the amount *Mollie* names, which is deliberately not assumed to equal the amount
 * this shop asked for: comparing the two on `PAID` is the whole point of deviation D11.
 *
 * [checkoutUrl] is nullable because Mollie answers it only while the payment can still be paid — a
 * `GET` on a settled payment carries no checkout link, and that is normal rather than a failure.
 * `MolliePayments.create` refuses an answer without one, so everything downstream of a creation has
 * a URL to send the customer to.
 */
internal data class MolliePayment(
    val id: String,
    val status: OrderPaymentStatus,
    val amountCents: Int,
    val checkoutUrl: String?,
)
