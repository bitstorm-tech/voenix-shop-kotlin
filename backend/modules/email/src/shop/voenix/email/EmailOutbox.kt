package shop.voenix.email

public fun interface EmailOutbox {
    public suspend fun enqueue(reference: QueuedEmailReference): Long
}

public sealed interface QueuedEmailReference {
    public val sourceId: Long

    public data class OrderConfirmation(public val orderId: Long) : QueuedEmailReference {
        init {
            require(orderId > 0) { "Order ID must be positive" }
        }

        override val sourceId: Long = orderId
    }

    public data class ProducerPdfNotification(public val deliveryId: Long) : QueuedEmailReference {
        init {
            require(deliveryId > 0) { "Delivery ID must be positive" }
        }

        override val sourceId: Long = deliveryId
    }

    /**
     * The mail that tells a customer one package of their order is on its way. Its business
     * identity is the production job that was shipped — not the order — because an order can ship
     * in several packages, one per supplier job, and each of them is its own mail.
     */
    public data class ShippingNotification(public val jobId: Long) : QueuedEmailReference {
        init {
            require(jobId > 0) { "Production job ID must be positive" }
        }

        override val sourceId: Long = jobId
    }
}

public fun interface QueuedEmailSource {
    public suspend fun resolve(reference: QueuedEmailReference): QueuedEmail?
}

private const val ORDER_CONFIRMATION_KIND = "ORDER_CONFIRMATION"
private const val PRODUCER_PDF_NOTIFICATION_KIND = "PRODUCER_PDF_NOTIFICATION"
private const val SHIPPING_NOTIFICATION_KIND = "SHIPPING_NOTIFICATION"

/**
 * The name a reference is stored and logged under. It lives beside the reference type so that the
 * persisted vocabulary has exactly one owner: forward and reverse mapping share the constants
 * above. Only this forward `when` is exhaustive over the sealed type — the reverse one reads an
 * arbitrary stored string and needs its `else`. A new reference variant therefore fails to compile
 * here; extend the reverse mapping below and the kind round-trip test in the same change.
 */
internal val QueuedEmailReference.kind: String
    get() =
        when (this) {
            is QueuedEmailReference.OrderConfirmation -> ORDER_CONFIRMATION_KIND
            is QueuedEmailReference.ProducerPdfNotification -> PRODUCER_PDF_NOTIFICATION_KIND
            is QueuedEmailReference.ShippingNotification -> SHIPPING_NOTIFICATION_KIND
        }

/**
 * Rebuilds the reference a stored row describes. An unknown kind fails loudly instead of being
 * skipped: the database CHECK constraint only allows the names above, so an unknown one means the
 * schema and this code have drifted apart.
 */
internal fun String.toQueuedEmailReference(sourceId: Long): QueuedEmailReference =
    when (this) {
        ORDER_CONFIRMATION_KIND -> QueuedEmailReference.OrderConfirmation(sourceId)
        PRODUCER_PDF_NOTIFICATION_KIND -> QueuedEmailReference.ProducerPdfNotification(sourceId)
        SHIPPING_NOTIFICATION_KIND -> QueuedEmailReference.ShippingNotification(sourceId)
        else -> error("Unsupported persisted email kind")
    }
