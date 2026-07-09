package shop.voenix.persistence

data class Order(
    val id: Int,
    val status: OrderStatus,
    val customerReference: String?,
    val molliePaymentId: String? = null,
)
