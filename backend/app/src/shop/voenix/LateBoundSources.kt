package shop.voenix

import shop.voenix.order.OrderPaymentStatus
import shop.voenix.order.OrderPaymentStatusSource
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionSource

/**
 * App-owned [ProductionSource] that is handed to production before the module which implements it
 * exists.
 *
 * Production is installed with the email runtime, and the order module needs production's outbox
 * and PDF generator — a wiring cycle with only one honest place to break it: here, in the
 * composition root that owns both ends. The application creates this source, installs production
 * with it, installs order, and then [bind]s the real implementation.
 *
 * Before that, and only for the few milliseconds of startup between the two installs, a load fails
 * with [IllegalStateException] — deliberately the same behaviour the pre-Order stub had. Every
 * production stage and the email worker record that as the retryable `SOURCE_UNAVAILABLE`, so a job
 * that a worker picked up during startup is not lost, it is simply tried again. Silently answering
 * `null` would be the dangerous alternative: production reads that as "this order does not exist".
 */
internal class LateBoundProductionSource : ProductionSource {
    @Volatile private var source: ProductionSource? = null

    fun bind(source: ProductionSource) {
        check(this.source == null) { "Production source is already bound" }
        this.source = source
    }

    override suspend fun load(orderId: Long): ProductionData? =
        checkNotNull(source) { "Production source is not bound yet" }.load(orderId)
}

/**
 * App-owned [OrderPaymentStatusSource] that is handed to the order module before the module which
 * implements it exists.
 *
 * It is the same shape as [LateBoundProductionSource] and for the same reason: two modules need
 * each other, and the composition root is the one honest place to break the cycle. Here the cycle
 * is the shorter of the two — the payment module is installed *after* order because it needs
 * order's `OrderPaymentGateway`, while order needs payment's status source to answer a
 * `paymentStatus`. So order is installed with this, and [bind] closes the loop two lines later.
 *
 * The failure mode differs from the production source's, and deliberately so. Between the two
 * installs a status read fails with [IllegalStateException], which surfaces as a `500` — an order
 * read during those milliseconds is a request nobody has made yet, and answering `null` would be
 * the dangerous alternative: `null` is the contracted word for "this order has no payment", and a
 * customer who just paid must never be told that.
 */
internal class LateBoundPaymentStatus : OrderPaymentStatusSource {
    @Volatile private var source: OrderPaymentStatusSource? = null

    fun bind(source: OrderPaymentStatusSource) {
        check(this.source == null) { "Payment status source is already bound" }
        this.source = source
    }

    override suspend fun stored(orderIds: Set<Long>): Map<Long, OrderPaymentStatus> =
        bound().stored(orderIds)

    override suspend fun refreshed(orderId: Long): OrderPaymentStatus? = bound().refreshed(orderId)

    private fun bound(): OrderPaymentStatusSource =
        checkNotNull(source) { "Payment status source is not bound yet" }
}
