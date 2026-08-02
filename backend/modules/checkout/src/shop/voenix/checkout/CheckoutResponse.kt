package shop.voenix.checkout

import kotlinx.serialization.Serializable

/**
 * What a customer receives when a checkout succeeded: the order that now exists, and where to pay
 * for it.
 *
 * Both routes answer this one shape. A fresh checkout answers it with `201` and a `Location`
 * header, a retried payment with `200`, and a *free* order answers it with `checkoutUrl: null` —
 * there is no payment to send anybody to, and saying so explicitly is what lets a frontend branch
 * on one field instead of on a status code.
 */
@Serializable
internal data class CheckoutResponse(
    val orderId: Long,
    val checkoutUrl: String?,
)
