package shop.voenix.checkout

import shop.voenix.promotion.PromotionCodeResult

/**
 * Everything a checkout — or a retried payment — can end in.
 *
 * It is a result of its own rather than the shared `OperationResult` because a checkout composes
 * four modules, and the reason it stopped is the only thing that tells a customer what to do next:
 * an empty cart is a different sentence from an exhausted coupon, and both are different from a
 * payment provider that would not create a payment.
 *
 * Two of the refusals are deliberately *not* here. An unexpected database failure is not mapped at
 * all — it surfaces as an exception and the HTTP runtime answers it — and a request that breaks its
 * own field rules never reaches an operation, because the Request Validation plugin rejects it
 * first. [Invalid] is therefore not the customer's mistake but this module's: the placement refused
 * an input the checkout itself assembled.
 */
internal sealed interface CheckoutResult {
    /** The order exists and, unless it is free, so does the payment the customer is sent to. */
    data class Started(val response: CheckoutResponse) : CheckoutResult

    /** No cart, no guest token, or a cart without a single line — all the same sentence. */
    data object EmptyCart : CheckoutResult

    /** The coupon the cart carries could not be reserved; [reason] is the promotion's own. */
    data class PromotionRejected(val reason: PromotionCodeResult) : CheckoutResult

    /** A line names an article variant the catalog no longer has. */
    data object ItemUnavailable : CheckoutResult

    /** A line names a print image that is gone. */
    data object ImageUnavailable : CheckoutResult

    /** The cart's amounts do not fit the cents the order columns hold (deviation D13). */
    data object TotalTooLarge : CheckoutResult

    /**
     * No payment was started. The checkout cannot tell whether the provider refused — in which case
     * the payment module has already cancelled the order — or whether the order's live payment slot
     * was contended away, in which case the order is untouched (deviation D7). It therefore claims
     * neither, and above all does not mark the cart checked out.
     */
    data object PaymentNotStarted : CheckoutResult

    /** The order does not exist, or belongs to somebody else — deliberately indistinguishable. */
    data object OrderNotFound : CheckoutResult

    /**
     * The order exists but no second payment journey can start for it — in one of the two ways the
     * customer can act on differently.
     *
     * The order module tells the four states apart (`PayableOrderResult`), and it keeps that
     * distinction because its own callers need it. Here only two of them survive: "you have already
     * paid this" is good news, everything else is the same dead end, and inventing a third sentence
     * for a customer who can do nothing with it would only be noise.
     */
    sealed interface OrderNotPayable : CheckoutResult {
        /** It is already `PAID`: there is nothing left to pay. */
        data object AlreadyPaid : OrderNotPayable

        /**
         * It will never be paid: it is `CANCELLED`, or its total is zero and it was confirmed
         * without a payment there could be a retry for. Both are one sentence to the customer.
         */
        data object NotPayable : OrderNotPayable
    }

    /** The placement refused the input this module built for it — a bug here, never a client's. */
    data object Invalid : CheckoutResult

    /** A step answered something that cannot be acted on; it has been logged. */
    data object UnexpectedFailure : CheckoutResult
}
