package shop.voenix.payment

class NoExternalWritePaymentSideEffectSink : PaymentSideEffectSink {
    override fun sendOrderPaidEmail(command: PaymentSideEffectCommand) = Unit

    override fun uploadOrderPaidSftp(command: PaymentSideEffectCommand) = Unit
}
