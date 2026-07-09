package shop.voenix.payment

class NotConfiguredMolliePaymentStatusLookup : MolliePaymentStatusLookup {
    override suspend fun fetch(paymentId: String): MolliePayment? = null
}
