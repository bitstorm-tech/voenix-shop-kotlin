package shop.voenix.email

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
}
