package shop.voenix.production

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.production.delivery.ProductionDestinationRepository
import shop.voenix.production.pdf.ProductionPdfRenderer
import shop.voenix.production.pdf.ProductionPdfService
import shop.voenix.validation.toRequestValidationResult

/**
 * Runtime handle of the Production module. [pdfGenerator] is the public on-demand PDF capability;
 * the application obtains a fully composed module only once a real [ProductionSource] exists (the
 * Order migration). Until then, standalone tests assemble it via [createProductionModule].
 */
public class ProductionModule
internal constructor(
    internal val destinations: ProductionDestinationOperations,
    public val pdfGenerator: ProductionPdfGenerator,
) {
    internal fun install(application: Application): Unit =
        DestinationRoutes.install(application, destinations)
}

internal fun createProductionModule(
    database: Database,
    productionSource: ProductionSource,
): ProductionModule =
    ProductionModule(
        destinations = ProductionDestinationService(ProductionDestinationRepository(database)),
        pdfGenerator = ProductionPdfService(productionSource, ProductionPdfRenderer()),
    )

internal fun Application.installProductionModule(
    destinations: ProductionDestinationOperations
): Unit = DestinationRoutes.install(this, destinations)

public fun Application.installProductionModule(database: Database): Unit =
    installProductionModule(ProductionDestinationService(ProductionDestinationRepository(database)))

public fun RequestValidationConfig.validateProductionRequests(): Unit {
    validate<ProductionDestinationInput> { input -> input.toRequestValidationResult() }
}
