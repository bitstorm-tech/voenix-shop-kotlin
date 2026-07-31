package shop.voenix

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
