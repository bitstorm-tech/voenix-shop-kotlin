package shop.voenix.production

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.production.delivery.ProductionDestinationRepository
import shop.voenix.validation.toRequestValidationResult

public class ProductionModule
internal constructor(internal val destinations: ProductionDestinationOperations) {
    internal fun install(application: Application): Unit =
        DestinationRoutes.install(application, destinations)
}

internal fun createProductionModule(database: Database): ProductionModule =
    ProductionModule(ProductionDestinationService(ProductionDestinationRepository(database)))

internal fun Application.installProductionModule(
    destinations: ProductionDestinationOperations
): Unit = DestinationRoutes.install(this, destinations)

public fun Application.installProductionModule(database: Database): Unit =
    createProductionModule(database).install(this)

public fun RequestValidationConfig.validateProductionRequests(): Unit {
    validate<ProductionDestinationInput> { input -> input.toRequestValidationResult() }
}
