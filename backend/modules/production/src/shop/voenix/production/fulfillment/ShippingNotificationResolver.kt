package shop.voenix.production.fulfillment

import shop.voenix.email.EmailActionUrl
import shop.voenix.email.EmailRecipient
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.QueuedEmailSource

/**
 * Turns a [QueuedEmailReference.ShippingNotification] — keyed by the shipped production job — into
 * the mail of one send attempt.
 *
 * The values come from two places, and the split is the data minimization of this mail: what was
 * shipped, with which carrier and under which number, comes from production's own rows; whom to
 * write to and which link to offer comes from the order module through
 * [ShippingNotificationOrderSource]. Neither side learns the other's data.
 *
 * Everything is resolved freshly per attempt, so a corrected address reaches the next one. `null`
 * means the mail cannot be built right now — unknown job, job not shipped (yet), unknown order, or
 * an item snapshot that is not there — which the email worker records as the retryable
 * `SOURCE_NOT_FOUND`. A reference of a foreign kind is a wiring bug in the application's source
 * composition and rejected loudly, exactly like the producer-notification resolver does.
 *
 * The tracking link is derived here from [ShippingCarrier], never read from a caller-supplied
 * column: the mail goes out under the shop's name, so the shop decides where its links point.
 */
internal class ShippingNotificationResolver(
    private val repository: FulfillmentRepository,
    private val orders: ShippingNotificationOrderSource,
) : QueuedEmailSource {
    override suspend fun resolve(reference: QueuedEmailReference): QueuedEmail? {
        require(reference is QueuedEmailReference.ShippingNotification) {
            "Production resolves only shipping notifications"
        }
        // Three things have to be there, and any of them missing is "not right now" rather than
        // "never": the shipped job, its item snapshot, and the customer behind its order.
        return repository
            .job(reference.jobId, supplierScope = null)
            ?.takeIf { job -> job.shippedAt != null }
            ?.let { job ->
                repository
                    .items(setOf(job.id))[job.id]
                    ?.takeIf(List<StoredFulfillmentJob.Item>::isNotEmpty)
                    ?.let { items ->
                        orders.load(job.orderId)?.let { order -> notification(job, items, order) }
                    }
            }
    }

    private fun notification(
        job: StoredFulfillmentJob,
        items: List<StoredFulfillmentJob.Item>,
        order: ShippingNotificationOrder,
    ): QueuedEmail {
        val carrier = ShippingCarrier.of(job.shippingCarrier)
        val trackingNumber = job.trackingNumber?.takeIf(String::isNotBlank)
        return QueuedEmail.ShippingNotification(
            recipient = EmailRecipient(order.recipientEmail),
            orderId = job.orderId,
            customerFirstName = order.customerFirstName,
            items =
                items.sortedBy(StoredFulfillmentJob.Item::position).map { item ->
                    QueuedEmail.ShippingNotification.Item(
                        articleName = item.articleName,
                        variantName = item.variantName,
                        quantity = item.quantity,
                    )
                },
            orderUrl = order.orderUrl,
            carrierName = carrier?.displayName,
            trackingNumber = trackingNumber,
            trackingUrl = carrier?.trackingUrl(trackingNumber)?.let(EmailActionUrl::invoke),
        )
    }
}
