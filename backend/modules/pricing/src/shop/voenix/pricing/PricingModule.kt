package shop.voenix.pricing

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.validation.toRequestValidationResult
import shop.voenix.vat.VatReader

internal class PricingModule internal constructor(internal val operations: PriceOperations) {
    internal fun install(application: Application): Unit =
        PriceRoutes.install(application, operations)
}

internal fun createPricingModule(
    database: Database,
    vats: VatReader,
): PricingModule = PricingModule(PriceService(PriceRepository(database), vats))

internal fun Application.installPricingModule(prices: PriceOperations): Unit =
    PriceRoutes.install(this, prices)

public fun Application.installPricingModule(
    database: Database,
    vats: VatReader,
): Unit = createPricingModule(database, vats).install(this)

public fun RequestValidationConfig.validatePricingRequests(): Unit {
    validate<PriceInput> { input -> input.toRequestValidationResult() }
}
