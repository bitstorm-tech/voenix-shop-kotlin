package shop.voenix.order

import shop.voenix.operation.OperationResult

/**
 * What a customer can ask about their own orders, expressed once so that the routes stay a mapping
 * from HTTP to these three calls and back.
 *
 * [history] and [order] take the caller's identity rather than reading a cookie themselves — who
 * the caller is, is an HTTP question — and both apply the same authorization rule: an order belongs
 * to the signed-in customer whose id it carries, or to the guest token it was placed with while it
 * has no user. A caller who may not see an order gets the same answer as one who named an id that
 * never existed.
 *
 * [orderByToken] is the third read and the one with a different security model altogether: it
 * carries no identity, because the access token from the confirmation mail is the identity. Its
 * route lives on a node of its own for exactly that reason — see `OrderRoutes`.
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

    /**
     * The one order [token] opens, or [OperationResult.NotFound] for a token that is malformed
     * *and* for one that names no order — deliberately the same answer, because the difference is
     * exactly what a probe is looking for.
     *
     * This is the only read that takes no identity at all: the token *is* the identity
     * (issue #110). It is handed in as the raw string from the URL, so the route stays free of the
     * token's format and this operation is the single place that decides what a token even looks
     * like.
     */
    suspend fun orderByToken(token: String): OperationResult<OrderView>
}
