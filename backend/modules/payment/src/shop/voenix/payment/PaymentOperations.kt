package shop.voenix.payment

/**
 * What the module's HTTP surface may ask of it: one call, because the webhook is the only route.
 *
 * The seam exists so the route tests can state what the routes decide *before* any payment work
 * runs — the secret check above all — against a stub instead of against Mollie and a database.
 */
internal fun interface PaymentOperations {
    /**
     * Applies whatever Mollie currently says about [molliePaymentId].
     *
     * The webhook body is never trusted: the id is the only thing read from it, and the status is
     * fetched from Mollie's API. A forged `status=PAID` therefore changes nothing.
     */
    suspend fun confirm(molliePaymentId: String): PaymentConfirmation
}
