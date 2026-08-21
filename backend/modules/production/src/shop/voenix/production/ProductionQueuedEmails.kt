package shop.voenix.production

import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.QueuedEmailSource

/**
 * Production's one branch of the application's queued-email source, covering all three mails this
 * module owns: the producer PDF notification, the customer's shipping notification, and the
 * operations alert of the print-on-demand channel.
 *
 * It is one source rather than two because the application aggregate should see one production
 * branch — production knows which of its own resolvers a reference belongs to, and the composition
 * root should not have to.
 *
 * The shipping branch is bound late, and only from inside this module: its resolver needs the order
 * module's [shop.voenix.production.fulfillment.ShippingNotificationOrderSource], which exists only
 * after the order module is installed, while the producer branch is ready the moment the production
 * module is created. Resolving before the binding throws [IllegalStateException], which the email
 * worker records as the retryable `SOURCE_UNAVAILABLE` — a job enqueued in those startup
 * milliseconds simply recovers on a later scan.
 */
internal class ProductionQueuedEmails(
    private val producerNotifications: QueuedEmailSource,
    private val spodOpsAlerts: QueuedEmailSource,
) : QueuedEmailSource {
    @Volatile private var shippingNotifications: QueuedEmailSource? = null

    fun bindShippingNotifications(source: QueuedEmailSource) {
        check(shippingNotifications == null) { "Shipping notification source is already bound" }
        shippingNotifications = source
    }

    override suspend fun resolve(reference: QueuedEmailReference): QueuedEmail? =
        when (reference) {
            is QueuedEmailReference.ProducerPdfNotification ->
                producerNotifications.resolve(reference)
            is QueuedEmailReference.ShippingNotification ->
                checkNotNull(shippingNotifications) {
                        "Shipping notification source is not bound yet"
                    }
                    .resolve(reference)
            is QueuedEmailReference.SpodOpsAlert -> spodOpsAlerts.resolve(reference)
            is QueuedEmailReference.OrderConfirmation ->
                throw IllegalArgumentException("Production resolves none of the order's own mails")
        }
}
