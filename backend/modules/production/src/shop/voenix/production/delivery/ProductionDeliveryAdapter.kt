package shop.voenix.production.delivery

/**
 * The channel-neutral seam to a true-external delivery system. The worker owns durable intent,
 * retry, and truthful state; an adapter only pushes one immutable artifact to one destination and
 * reports the outcome as a typed [ProductionDeliveryResult].
 *
 * Contract: return [ProductionDeliveryResult.Accepted] only after the remote system confirmed
 * acceptance of the complete file under its final name — `delivered_at` is set on nothing less.
 * Expected external problems become [ProductionDeliveryResult.Failed] with a bounded
 * [ProductionDeliveryError]; a [java.util.concurrent.CancellationException] must propagate so
 * shutdown never records a failure. Adapters never log or throw credentials.
 *
 * Adding a channel later means a new adapter plus destination configuration and a Flyway
 * check-constraint change — the worker algorithm stays untouched.
 */
internal interface ProductionDeliveryAdapter {
    /** The destination channel this adapter serves, e.g. `SFTP`. Unique per registry. */
    val channel: String

    suspend fun deliver(
        destination: ProductionDeliveryDestination,
        fileName: String,
        bytes: ByteArray,
    ): ProductionDeliveryResult
}
