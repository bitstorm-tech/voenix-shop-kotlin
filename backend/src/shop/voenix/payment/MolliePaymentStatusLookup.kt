package shop.voenix.payment

fun interface MolliePaymentStatusLookup {
    suspend fun fetch(paymentId: String): MolliePayment?
}
