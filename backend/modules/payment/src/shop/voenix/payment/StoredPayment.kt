package shop.voenix.payment

/**
 * A payment row as the service reads it: everything a decision here is made from, and nothing else.
 *
 * [amountCents] is the amount this shop asked for, which is what the webhook compares Mollie's paid
 * amount against; [checkoutUrl] is what a repeated `start` answers.
 */
internal data class StoredPayment(
    val paymentId: Long,
    val orderId: Long,
    val molliePaymentId: String,
    val status: PaymentStatus,
    val amountCents: Int,
    val checkoutUrl: String,
)
