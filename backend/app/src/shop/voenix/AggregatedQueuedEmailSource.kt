package shop.voenix

import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.QueuedEmailSource

/**
 * App-owned, late-bound composition of the [QueuedEmailSource] handed to `installEmailModule`.
 *
 * The email module needs its source at installation while Production needs the returned
 * `EmailOutbox` — a pure wiring-order concern this class absorbs: the application installs the
 * email module with this aggregate, creates the Production module with the email outbox, and then
 * binds `ProductionModule.producerNotifications` via [bindProducerNotifications]. Compile-time
 * dependencies stay acyclic (`production -> email -> platform`).
 *
 * Resolving a variant whose owner is not bound yet throws [IllegalStateException]; the email worker
 * records that as the retryable `SOURCE_UNAVAILABLE`, so a job enqueued before binding completes
 * simply recovers on a later scan. The order-confirmation branch arrives with the Order migration.
 */
internal class AggregatedQueuedEmailSource : QueuedEmailSource {
    @Volatile private var producerNotifications: QueuedEmailSource? = null

    internal fun bindProducerNotifications(source: QueuedEmailSource) {
        check(producerNotifications == null) { "Producer notification source is already bound" }
        producerNotifications = source
    }

    override suspend fun resolve(reference: QueuedEmailReference): QueuedEmail? =
        when (reference) {
            is QueuedEmailReference.OrderConfirmation ->
                error("Order confirmation resolution is not wired yet")
            is QueuedEmailReference.ProducerPdfNotification ->
                checkNotNull(producerNotifications) {
                        "Producer notification source is not bound yet"
                    }
                    .resolve(reference)
        }
}
