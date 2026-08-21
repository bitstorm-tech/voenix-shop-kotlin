package shop.voenix.production.fulfillment

import io.ktor.server.application.Application
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import shop.voenix.auth.SupplierAccounts
import shop.voenix.db.read
import shop.voenix.email.EmailOutbox
import shop.voenix.production.ProductionModule
import shop.voenix.production.ProductionSettings
import shop.voenix.production.ProductionSpodSettings
import shop.voenix.production.delivery.ProductionChannels
import shop.voenix.production.delivery.ProductionDestinations
import shop.voenix.production.delivery.spod.SpodOrderRepository
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.supplier.SupplierReader

/**
 * Installs the fulfillment surface: the supplier's own job list with its PDF downloads and its ship
 * button, the admin view of every supplier's jobs with ship-on-behalf, and the print-on-demand
 * partner's inbound webhook.
 *
 * It is a second install function of the production module rather than part of
 * `installProductionModule`, because it consumes what that module cannot wait for. The background
 * worker runs from startup, long before an order exists; these routes need the order module's
 * [FulfillmentOrderSource] and [ShippingNotificationOrderSource] plus the account module's
 * [SupplierAccounts], all of which exist only after those modules are installed. Splitting the
 * install is what keeps the composition a single pass without further late-bound ports.
 *
 * This is also where production's own late mail branch closes: the shipping-notification resolver
 * is built here — it needs the same repository the routes read through — and bound into
 * [production]'s combined queued-email source, which the application already handed to the email
 * module.
 *
 * [settings] must be the same the production module was installed with: the download reads the
 * artifacts the worker wrote, out of the same private root, and the webhook answers on the secret
 * from the same block the ops alerts are addressed with. [emailOutbox] must be the same one too,
 * because the shipment and the customer's mail are one commit.
 *
 * The parameter list is long because the dependencies *are* the list: one per thing this surface
 * cannot build itself.
 */
@Suppress("LongParameterList")
public fun Application.installProductionFulfillment(
    production: ProductionModule,
    database: Database,
    settings: ProductionSettings,
    orders: FulfillmentOrderSource,
    shippingOrders: ShippingNotificationOrderSource,
    suppliers: SupplierReader,
    accounts: SupplierAccounts,
    emailOutbox: EmailOutbox,
) {
    val repository = FulfillmentRepository(database, emailOutbox)
    val fulfillment =
        FulfillmentService(
            repository = repository,
            orders = orders,
            suppliers = suppliers,
            artifacts = ProductionArtifactStore(settings.artifactRoot),
            spodOrders = SpodOrderRepository(database, emailOutbox),
        )
    production.bindShippingNotifications(ShippingNotificationResolver(repository, shippingOrders))
    installFulfillmentRoutes(fulfillment, accounts)
    requireSpodSettings(database, settings.spod)?.let { spod ->
        installSpodWebhookRoute(fulfillment, spod.webhookSecret)
    }
}

/**
 * The print-on-demand configuration, checked against the destinations this deployment actually has.
 *
 * A shop with a print-on-demand destination and no webhook secret is a shop whose t-shirt orders
 * are produced and shipped and whose customers are never told — the partner reports every shipment
 * to a callback that does not exist. That is worth refusing to start over, so the check runs here,
 * once, at installation, and blocks the startup thread for one `EXISTS`-shaped query the way Flyway
 * blocks it for the migrations a moment earlier.
 *
 * The other direction is deliberately allowed: a deployment may carry the configuration before it
 * carries a destination, which is exactly how one is set up.
 */
private fun requireSpodSettings(
    database: Database,
    spod: ProductionSpodSettings?,
): ProductionSpodSettings? {
    if (spod != null) return spod
    val hasSpodDestination = runBlocking {
        database.read {
            ProductionDestinations.select(ProductionDestinations.id)
                .where { ProductionDestinations.channel eq ProductionChannels.SPOD }
                .limit(1)
                .any()
        }
    }
    check(!hasSpodDestination) {
        "A print-on-demand destination exists, so production.spod.webhookSecret and " +
            "production.spod.alertEmail are required"
    }
    return null
}
