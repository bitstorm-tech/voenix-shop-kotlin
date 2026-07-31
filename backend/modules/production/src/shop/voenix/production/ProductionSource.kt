package shop.voenix.production

/**
 * Resolves the immutable order, item, and image inputs Production needs for one order.
 *
 * The order module implements it; standalone module tests use an in-memory source. Returning `null`
 * means the order does not exist for production purposes.
 */
public fun interface ProductionSource {
    public suspend fun load(orderId: Long): ProductionData?
}
