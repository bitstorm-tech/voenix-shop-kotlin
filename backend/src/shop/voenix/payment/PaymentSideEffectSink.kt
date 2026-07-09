package shop.voenix.payment

interface PaymentSideEffectSink {
    fun sendOrderPaidEmail(command: PaymentSideEffectCommand)

    fun uploadOrderPaidSftp(command: PaymentSideEffectCommand)
}
