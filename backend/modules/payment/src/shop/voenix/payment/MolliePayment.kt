package shop.voenix.payment

import shop.voenix.order.OrderPaymentStatus

/**
 * What Mollie says about one payment, in the four facts this module uses.
 *
 * [amountCents] is the amount *Mollie* names, which is deliberately not assumed to equal the amount
 * this shop asked for: comparing the two on `PAID` is the whole point of deviation D11.
 *
 * [checkoutUrl] is nullable because Mollie answers it only while the payment can still be paid — a
 * `GET` on a settled payment carries no checkout link, and that is normal rather than a failure.
 * `MolliePayments.create` refuses an answer without one, so everything downstream of a creation has
 * a URL to send the customer to.
 */
internal data class MolliePayment(
    val id: String,
    val status: OrderPaymentStatus,
    val amountCents: Int,
    val checkoutUrl: String?,
)
