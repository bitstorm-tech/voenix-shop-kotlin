package shop.voenix.order

import shop.voenix.operation.OperationResult

/**
 * What a customer can ask about their own orders, expressed once so that the routes stay a mapping
 * from HTTP to these two calls and back.
 *
 * Both operations take the caller's identity rather than reading a cookie themselves — who the
 * caller is, is an HTTP question — and both apply the same authorization rule: an order belongs to
 * the signed-in customer whose id it carries, or to the guest token it was placed with *while it
 * has no user yet*. A claimed order therefore stops answering the old guest token, and a caller who
 * may not see an order gets the same answer as one who named an id that never existed.
 *
 * Placing an order and confirming its payment are deliberately not here. They have no HTTP surface
 * in this wave, they answer with their own result types, and their callers are future modules
 * (Checkout and Payment), not a route.
 */
internal interface OrderOperations {
    /**
     * The caller's orders, newest first (`created_at DESC, id DESC`). A caller with no identity at
     * all gets an empty list rather than somebody else's orders.
     */
    suspend fun history(
        userId: Long?,
        guestToken: String?,
    ): OperationResult<List<OrderView>>

    /** One order of the caller, or [OperationResult.NotFound] for unknown *and* foreign ids. */
    suspend fun order(
        orderId: Long,
        userId: Long?,
        guestToken: String?,
    ): OperationResult<OrderView>
}
