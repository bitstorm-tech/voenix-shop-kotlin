package shop.voenix.payment

data class PaidOrderTransition(
    val orderId: Int,
    val changed: Boolean,
)
