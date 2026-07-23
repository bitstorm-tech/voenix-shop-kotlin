package shop.voenix.production.delivery

import shop.voenix.email.EmailRecipient
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.QueuedEmailSource
import shop.voenix.production.ProductionSource

/**
 * Resolves a [QueuedEmailReference.ProducerPdfNotification] — keyed by the production delivery ID —
 * into the current notification values for one send attempt: recipient and optional producer name
 * from the destination's notification configuration, the destination label, the delivered file
 * name, and order date plus the supplier's physical item count from the production source.
 *
 * Everything is read freshly per attempt, so address or label changes affect the next attempt.
 * `null` means the notification cannot be sent right now (unknown delivery, destination gone, no
 * notification address anymore, unknown order) and recovers once the data reappears. A foreign
 * reference kind is a wiring bug in the application's source composition and rejected loudly.
 */
internal class ProducerNotificationResolver(
    private val repository: ProductionDeliveryRepository,
    private val source: ProductionSource,
) : QueuedEmailSource {
    override suspend fun resolve(reference: QueuedEmailReference): QueuedEmail? {
        require(reference is QueuedEmailReference.ProducerPdfNotification) {
            "Production resolves only producer PDF notifications"
        }
        return repository.notificationContext(reference.deliveryId)?.let { context ->
            notification(context)
        }
    }

    private suspend fun notification(context: ProducerNotificationContext): QueuedEmail? {
        val notificationEmail = context.notificationEmail?.takeUnless(String::isBlank)
        val order = notificationEmail?.let { source.load(context.orderId) }
        return order?.let {
            require(order.orderId == context.orderId) {
                "Production source returned a different order"
            }
            QueuedEmail.ProducerPdfNotification(
                recipient = EmailRecipient(notificationEmail),
                orderId = context.orderId,
                fileName = context.fileName,
                destinationLabel = context.destinationLabel,
                orderDate = order.orderDate,
                itemCount =
                    order.items
                        .filter { item -> item.supplierId == context.supplierId }
                        .sumOf { item -> item.quantity },
                producerName = context.notificationName,
            )
        }
    }
}

/** Notification values of one delivery, read together in [ProductionDeliveryRepository]. */
internal data class ProducerNotificationContext(
    val orderId: Long,
    val supplierId: Long,
    val fileName: String,
    val destinationLabel: String,
    val notificationEmail: String?,
    val notificationName: String?,
)
