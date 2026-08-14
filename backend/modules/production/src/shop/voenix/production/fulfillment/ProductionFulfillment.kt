package shop.voenix.production.fulfillment

import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.SupplierAccounts
import shop.voenix.email.EmailOutbox
import shop.voenix.production.ProductionModule
import shop.voenix.production.ProductionSettings
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.supplier.SupplierReader

/**
 * Installs the fulfillment surface: the supplier's own job list with its PDF downloads and its ship
 * button, and the admin view of every supplier's jobs with ship-on-behalf.
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
 * artifacts the worker wrote, out of the same private root. [emailOutbox] must be the same one too,
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
        )
    production.bindShippingNotifications(ShippingNotificationResolver(repository, shippingOrders))
    installFulfillmentRoutes(fulfillment, accounts)
}
