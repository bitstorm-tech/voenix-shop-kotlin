package shop.voenix.persistence

data class NewOrder(
    val status: OrderStatus,
    val customerReference: String?,
    val molliePaymentId: String? = null,
)
