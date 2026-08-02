package shop.voenix.order

/**
 * What asking for an order that is supposed to be paid can end in.
 *
 * The four refusals are the reasons an order cannot start a *second* payment journey, and each of
 * them is a different sentence to the customer: an order that is not theirs (or does not exist) is
 * [NotFound], one that has been paid is [AlreadyPaid], one that was cancelled is [Cancelled], and a
 * free order is [Free] — it was confirmed without a payment and never had one to retry.
 *
 * [NotFound] deliberately covers unknown *and* foreign ids, exactly as the customer's own order
 * reads do: the two are indistinguishable, so nobody can probe for the existence of somebody else's
 * order — and no provider call is ever made on their behalf.
 */
public sealed interface PayableOrderResult {
    /** The order exists, belongs to the caller, is still pending, and costs money. */
    public data class Payable(public val order: PayableOrder) : PayableOrderResult

    /** Unknown id, or an order that belongs to somebody else — deliberately the same answer. */
    public data object NotFound : PayableOrderResult

    /** The order is `PAID`; there is nothing left to pay. */
    public data object AlreadyPaid : PayableOrderResult

    /** The order is `CANCELLED`; it will never be paid. */
    public data object Cancelled : PayableOrderResult

    /** The order's total is zero: it is confirmed without a payment and has none to retry. */
    public data object Free : PayableOrderResult
}
