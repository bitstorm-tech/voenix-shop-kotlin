package shop.voenix.pricing

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.validation.toRequestValidationResult
import shop.voenix.vat.VatReader

internal class PricingFeature internal constructor(internal val operations: PriceOperations) {
    internal fun install(application: Application): Unit =
        PriceRoutes.install(application, operations)
}

internal fun createPricingFeature(
    database: Database,
    vats: VatReader,
): PricingFeature = PricingFeature(PriceService(PriceRepository(database), vats))

public fun Application.installPricingFeature(prices: PriceOperations): Unit =
    PriceRoutes.install(this, prices)

public fun Application.installPricingFeature(
    database: Database,
    vats: VatReader,
): Unit = createPricingFeature(database, vats).install(this)

public fun RequestValidationConfig.validatePricingRequests(): Unit {
    validate<PriceInput> { input -> input.toRequestValidationResult() }
}
