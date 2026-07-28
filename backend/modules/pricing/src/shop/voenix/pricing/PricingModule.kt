package shop.voenix.pricing

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.validation.toRequestValidationResult
import shop.voenix.vat.VatReader

internal class PricingModule(
    val operations: PriceOperations,
    val catalog: PriceCatalog,
) {
    fun install(application: Application): Unit = PriceRoutes.install(application, operations)
}

internal fun createPricingModule(
    database: Database,
    vats: VatReader,
): PricingModule {
    val service = PriceService(PriceRepository(database), vats)
    return PricingModule(operations = service, catalog = service)
}

internal fun Application.installPricingModule(prices: PriceOperations): Unit =
    PriceRoutes.install(this, prices)

/**
 * Installs the admin price routes and returns the [PriceCatalog] capability. The composition root
 * does not bind it yet; the Article migration will, so that an article and its price are written in
 * one transaction.
 */
public fun Application.installPricingModule(
    database: Database,
    vats: VatReader,
): PriceCatalog {
    val module = createPricingModule(database, vats)
    module.install(this)
    return module.catalog
}

public fun RequestValidationConfig.validatePricingRequests(): Unit {
    validate<PriceInput> { input -> input.toRequestValidationResult() }
}
