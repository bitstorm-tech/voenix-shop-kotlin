package shop.voenix.production

/**
 * Durable production trigger: inserts exactly one production request per order.
 *
 * [request] must be called inside the caller's Exposed transaction (the future payment-completion
 * transaction). It only stores a cheap order reference — no source resolution, no routing. Repeated
 * and concurrent calls for the same order return the same stable request id; a rollback of the
 * caller transaction leaves no request behind. A non-positive order id fails with
 * [IllegalArgumentException] before touching the database.
 */
public fun interface ProductionOutbox {
    public suspend fun request(orderId: Long): Long
}
