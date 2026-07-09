package shop.voenix.persistence.order

data class NewOrder(
    val status: OrderStatus,
    val customerReference: String?,
    val molliePaymentId: String? = null,
)
