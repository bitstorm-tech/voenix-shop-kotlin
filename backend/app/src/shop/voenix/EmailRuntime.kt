package shop.voenix

import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.tshirt.TshirtCatalogSync
import shop.voenix.email.EmailOutbox
import shop.voenix.email.EmailSettings
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.QueuedEmailSource
import shop.voenix.email.UserEmailSender
import shop.voenix.email.installEmailModule
import shop.voenix.production.ProductionModule
import shop.voenix.production.ProductionSettings
import shop.voenix.production.ProductionSource
import shop.voenix.production.installProductionModule
import shop.voenix.spod.SpodClient

/**
 * What the application's email-and-production wiring hands to the modules installed after it.
 *
 * [userEmails] is the direct-delivery capability the account module sends with, [emailOutbox] and
 * [production] are what the order module needs to make a paid order durable, and
 * [bindOrderConfirmations] closes the last open port of the aggregate once the order module exists.
 */
internal class EmailRuntime(
    val userEmails: UserEmailSender,
    val emailOutbox: EmailOutbox,
    val production: ProductionModule,
    private val queuedEmails: AggregatedQueuedEmailSource,
) {
    fun bindOrderConfirmations(source: QueuedEmailSource) {
        queuedEmails.bindOrderConfirmations(source)
    }
}

/**
 * The application's one email-runtime wiring: install the email module exactly once with the
 * aggregated queued source, install the full production module against the returned real
 * [EmailOutbox], and bind production's own mail branch. `Application` and the composition
 * integration test share this function, so the test exercises the real wiring instead of mirroring
 * it; only the settings, the [ProductionSource], and the two capabilities of the t-shirt sync — the
 * application's single [SpodClient] and the article module's [TshirtCatalogSync] — are injection
 * points.
 *
 * Production's branch covers both of its kinds, and its shipping half is closed later, inside the
 * module, by `installProductionFulfillment` — until then a shipping notification fails retryably.
 *
 * The parameter list is long because the injection points *are* the list, one per capability.
 */
@Suppress("LongParameterList")
internal fun Application.installEmailRuntime(
    database: Database,
    emailSettings: EmailSettings,
    productionSettings: ProductionSettings,
    productionSource: ProductionSource,
    spod: SpodClient,
    tshirtCatalogSync: TshirtCatalogSync,
): EmailRuntime {
    val queuedEmails = AggregatedQueuedEmailSource()
    val email = installEmailModule(database, emailSettings, queuedEmails)
    val production =
        installProductionModule(
            database,
            productionSettings,
            email.outbox,
            productionSource,
            spod,
            tshirtCatalogSync,
        )
    queuedEmails.bindProductionEmails(production.queuedEmails)
    return EmailRuntime(
        userEmails = email.userEmails,
        emailOutbox = email.outbox,
        production = production,
        queuedEmails = queuedEmails,
    )
}

/**
 * App-owned, late-bound composition of the [QueuedEmailSource] handed to `installEmailModule`.
 *
 * The email module needs its source at installation while its two suppliers need what the email
 * module returns — production needs the `EmailOutbox`, order needs it too — a pure wiring-order
 * concern this class absorbs: the application installs the email module with this aggregate,
 * creates production and order against the email outbox, and then binds each branch to the module
 * that owns it. Compile-time dependencies stay acyclic (`order -> production -> email ->
 * platform`).
 *
 * There are two branches, not one per mail kind: a kind belongs to the module that owns it, and
 * production owns three of the four — the producer PDF notification, the customer's shipping
 * notification, and the print-on-demand operations alert. Which of its own resolvers a reference
 * goes to is production's business, not this aggregate's.
 *
 * Resolving a variant whose owner is not bound yet throws [IllegalStateException]; the email worker
 * records that as the retryable `SOURCE_UNAVAILABLE`, so a job enqueued before binding completes
 * simply recovers on a later scan.
 */
internal class AggregatedQueuedEmailSource : QueuedEmailSource {
    @Volatile private var productionEmails: QueuedEmailSource? = null

    @Volatile private var orderConfirmations: QueuedEmailSource? = null

    internal fun bindProductionEmails(source: QueuedEmailSource) {
        check(productionEmails == null) { "Production email source is already bound" }
        productionEmails = source
    }

    internal fun bindOrderConfirmations(source: QueuedEmailSource) {
        check(orderConfirmations == null) { "Order confirmation source is already bound" }
        orderConfirmations = source
    }

    override suspend fun resolve(reference: QueuedEmailReference): QueuedEmail? =
        when (reference) {
            is QueuedEmailReference.OrderConfirmation ->
                checkNotNull(orderConfirmations) { "Order confirmation source is not bound yet" }
                    .resolve(reference)
            is QueuedEmailReference.ProducerPdfNotification,
            is QueuedEmailReference.ShippingNotification,
            is QueuedEmailReference.SpodOpsAlert ->
                checkNotNull(productionEmails) { "Production email source is not bound yet" }
                    .resolve(reference)
        }
}
