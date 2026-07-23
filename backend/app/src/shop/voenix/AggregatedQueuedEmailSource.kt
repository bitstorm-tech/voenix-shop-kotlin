package shop.voenix

import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.email.EmailSettings
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.QueuedEmailSource
import shop.voenix.email.installEmailModule
import shop.voenix.production.ProductionSettings
import shop.voenix.production.ProductionSource
import shop.voenix.production.installProductionModule

/**
 * App-owned, late-bound composition of the [QueuedEmailSource] handed to `installEmailModule`.
 *
 * The email module needs its source at installation while Production needs the returned
 * `EmailOutbox` — a pure wiring-order concern this class absorbs: the application installs the
 * email module with this aggregate, creates the Production module with the email outbox, and then
 * binds `ProductionModule.producerNotifications` via [bindProducerNotifications]. Compile-time
 * dependencies stay acyclic (`production -> email -> platform`).
 *
 * Resolving a variant whose owner is not bound yet throws [IllegalStateException]; the email worker
 * records that as the retryable `SOURCE_UNAVAILABLE`, so a job enqueued before binding completes
 * simply recovers on a later scan. The order-confirmation branch arrives with the Order migration.
 */
internal class AggregatedQueuedEmailSource : QueuedEmailSource {
    @Volatile private var producerNotifications: QueuedEmailSource? = null

    internal fun bindProducerNotifications(source: QueuedEmailSource) {
        check(producerNotifications == null) { "Producer notification source is already bound" }
        producerNotifications = source
    }

    override suspend fun resolve(reference: QueuedEmailReference): QueuedEmail? =
        when (reference) {
            is QueuedEmailReference.OrderConfirmation ->
                error("Order confirmation resolution is not wired yet")
            is QueuedEmailReference.ProducerPdfNotification ->
                checkNotNull(producerNotifications) {
                        "Producer notification source is not bound yet"
                    }
                    .resolve(reference)
        }
}

/**
 * The application's one email-runtime wiring: install the email module exactly once with the
 * aggregated queued source, install the full production module against the returned real
 * [shop.voenix.email.EmailOutbox], and bind the producer-notification resolver. `Application` and
 * the composition integration test share this function, so the test exercises the real wiring
 * instead of mirroring it; only the settings and the [ProductionSource] are injection points.
 */
internal fun Application.installEmailRuntime(
    database: Database,
    emailSettings: EmailSettings,
    productionSettings: ProductionSettings,
    productionSource: ProductionSource,
) {
    val queuedEmails = AggregatedQueuedEmailSource()
    val email = installEmailModule(database, emailSettings, queuedEmails)
    val production =
        installProductionModule(database, productionSettings, email.outbox, productionSource)
    queuedEmails.bindProducerNotifications(production.producerNotifications)
}
