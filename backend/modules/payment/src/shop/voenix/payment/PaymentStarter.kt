package shop.voenix.payment

import shop.voenix.order.PayableOrder

/**
 * The one way anything outside this module starts a payment.
 *
 * It takes the order module's own [PayableOrder] rather than an input DTO of its own (deviation
 * D14): the amount, the customer, and the two addresses are facts the order already stored, and
 * re-declaring them here would only invite a caller to hand over something the order does not say.
 * The payment module still never reads `orders` — it is *told* what to charge for, exactly as the
 * [shop.voenix.order.OrderPaymentGateway] direction established one layer down.
 *
 * Both journeys that need a payment use this single call: the checkout that just placed an order,
 * and the retry that reads a payable one back. Neither can tell the two apart, because both hand in
 * the same stored snapshot.
 */
public fun interface PaymentStarter {
    /**
     * Starts — or re-answers — the payment of [order] and answers the URL the customer is sent to.
     *
     * `null` means no payment was started, and the caller cannot tell which of the two reasons it
     * was: the provider refused to create one, in which case this module has already cancelled the
     * order (deviation D10), or the order's live payment slot was contended away twice in a row, in
     * which case the order stays `PENDING`. Both are "there is no checkout URL", which is all a
     * caller may say about it.
     *
     * An order that already has a live payment answers that payment's stored URL without a single
     * provider call, which is what makes a double-clicked checkout harmless.
     */
    public suspend fun start(order: PayableOrder): String?
}
