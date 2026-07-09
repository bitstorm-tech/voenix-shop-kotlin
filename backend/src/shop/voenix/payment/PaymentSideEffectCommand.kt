package shop.voenix.payment

data class PaymentSideEffectCommand(
    val orderId: Int,
    val idempotencyKey: String,
)
