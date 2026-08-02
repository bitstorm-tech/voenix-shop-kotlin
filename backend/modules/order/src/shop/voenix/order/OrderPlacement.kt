package shop.voenix.order

/**
 * The two things a checkout needs from the order module: place the order it has just priced, and
 * read back an order whose payment has to be started again.
 *
 * Like [OrderPaymentGateway], it is declared *and* implemented here and handed to the checkout
 * module at composition time. What an order is, what it snapshots, and who may see it are this
 * module's decisions; the caller only supplies the numbers it has already decided
 * ([PlaceOrderInput]) and receives the snapshot a payment is built from ([PayableOrder]).
 * Everything behind that — the tables, the repository, the internal `OrderView` the customer routes
 * serialize — stays inside.
 *
 * Neither call maps an unexpected database failure to a result. It surfaces as an exception
 * together with the rollback that caused it, so the checkout answers it with its own error policy.
 */
public interface OrderPlacement {
    /**
     * Places one order from [input]: the field rules first, then the catalog snapshot, then the
     * write.
     *
     * A cart that already has a live order is answered with that order rather than a second one —
     * see [OrderPlacementResult.AlreadyPlaced], which is what makes a double-submitted checkout
     * harmless.
     */
    public suspend fun place(input: PlaceOrderInput): OrderPlacementResult

    /**
     * The order [orderId] as a payment would be built from it, for the caller identified by
     * [userId] and [guestToken] — the read behind the retry-payment journey.
     *
     * The ownership rule is the same one the customer's own order reads apply: an order belongs to
     * the signed-in customer whose id it carries, or to the guest token it was placed with *while
     * it has no user yet*. Anything else is [PayableOrderResult.NotFound], whether the id is
     * unknown or simply somebody else's.
     */
    public suspend fun payable(
        orderId: Long,
        userId: Long?,
        guestToken: String?,
    ): PayableOrderResult
}
