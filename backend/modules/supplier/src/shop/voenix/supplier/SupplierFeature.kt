package shop.voenix.supplier

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.country.CountryReader
import shop.voenix.validation.toRequestValidationResult

internal class SupplierFeature internal constructor(internal val operations: SupplierOperations) {
    internal fun install(application: Application): Unit =
        SupplierRoutes.install(application, operations)
}

internal fun createSupplierFeature(
    database: Database,
    countries: CountryReader,
): SupplierFeature = SupplierFeature(SupplierService(SupplierRepository(database), countries))

public fun Application.installSupplierFeature(suppliers: SupplierOperations): Unit =
    SupplierRoutes.install(this, suppliers)

public fun Application.installSupplierFeature(
    database: Database,
    countries: CountryReader,
): Unit = createSupplierFeature(database, countries).install(this)

public fun RequestValidationConfig.validateSupplierRequests(): Unit {
    validate<SupplierInput> { input -> input.toRequestValidationResult() }
}
