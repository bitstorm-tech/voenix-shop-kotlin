package shop.voenix.payment

data class MolliePayment(
    val id: String,
    val status: MolliePaymentStatus,
)
