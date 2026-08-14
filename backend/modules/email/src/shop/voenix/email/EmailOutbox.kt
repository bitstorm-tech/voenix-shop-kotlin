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
