package shop.voenix.order

data class Order(
    val id: Int,
    val status: OrderStatus,
    val customerReference: String?,
    val molliePaymentId: String? = null,
)
