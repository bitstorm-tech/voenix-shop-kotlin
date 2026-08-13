package shop.voenix.production.fulfillment

import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.SupplierAccounts
import shop.voenix.production.ProductionSettings
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.supplier.SupplierReader

/**
 * Installs the fulfillment read side: the supplier's own job list with its PDF downloads, and the
 * admin view of every supplier's jobs.
 *
 * It is a second install function of the production module rather than part of
 * `installProductionModule`, because it consumes what that module cannot wait for. The background
 * worker runs from startup, long before an order exists; these routes need the order module's
 * [FulfillmentOrderSource] and the account module's [SupplierAccounts], both of which exist only
 * after those modules are installed. Splitting the install is what keeps the composition a single
 * pass without a third late-bound port.
 *
 * [settings] must be the same the production module was installed with: the download reads the
 * artifacts the worker wrote, out of the same private root.
 */
public fun Application.installProductionFulfillment(
    database: Database,
    settings: ProductionSettings,
    orders: FulfillmentOrderSource,
    suppliers: SupplierReader,
    accounts: SupplierAccounts,
) {
    val fulfillment =
        FulfillmentService(
            repository = FulfillmentRepository(database),
            orders = orders,
            suppliers = suppliers,
            artifacts = ProductionArtifactStore(settings.artifactRoot),
        )
    FulfillmentRoutes.install(this, fulfillment, accounts)
}

/** The route test seam: installs the fulfillment routes on caller-provided implementations. */
internal fun Application.installProductionFulfillment(
    fulfillment: FulfillmentOperations,
    accounts: SupplierAccounts,
): Unit = FulfillmentRoutes.install(this, fulfillment, accounts)
