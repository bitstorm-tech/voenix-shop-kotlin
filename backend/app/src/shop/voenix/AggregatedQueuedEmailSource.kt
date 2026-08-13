package shop.voenix

import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.QueuedEmailSource

/**
 * App-owned, late-bound composition of the [QueuedEmailSource] handed to `installEmailModule`.
 *
 * The email module needs its source at installation while its two suppliers need what the email
 * module returns — production needs the `EmailOutbox`, order needs it too — a pure wiring-order
 * concern this class absorbs: the application installs the email module with this aggregate,
 * creates production and order against the email outbox, and then binds each branch to the module
 * that owns it. Compile-time dependencies stay acyclic (`order -> production -> email ->
 * platform`).
 *
 * There are two branches, not one per mail kind: a kind belongs to the module that owns it, and
 * production owns two of the three — the producer PDF notification and the customer's shipping
 * notification. Which of its own resolvers a reference goes to is production's business, not this
 * aggregate's.
 *
 * Resolving a variant whose owner is not bound yet throws [IllegalStateException]; the email worker
 * records that as the retryable `SOURCE_UNAVAILABLE`, so a job enqueued before binding completes
 * simply recovers on a later scan.
 */
internal class AggregatedQueuedEmailSource : QueuedEmailSource {
    @Volatile private var productionEmails: QueuedEmailSource? = null

    @Volatile private var orderConfirmations: QueuedEmailSource? = null

    internal fun bindProductionEmails(source: QueuedEmailSource) {
        check(productionEmails == null) { "Production email source is already bound" }
        productionEmails = source
    }

    internal fun bindOrderConfirmations(source: QueuedEmailSource) {
        check(orderConfirmations == null) { "Order confirmation source is already bound" }
        orderConfirmations = source
    }

    override suspend fun resolve(reference: QueuedEmailReference): QueuedEmail? =
        when (reference) {
            is QueuedEmailReference.OrderConfirmation ->
                checkNotNull(orderConfirmations) { "Order confirmation source is not bound yet" }
                    .resolve(reference)
            is QueuedEmailReference.ProducerPdfNotification,
            is QueuedEmailReference.ShippingNotification ->
                checkNotNull(productionEmails) { "Production email source is not bound yet" }
                    .resolve(reference)
        }
}
