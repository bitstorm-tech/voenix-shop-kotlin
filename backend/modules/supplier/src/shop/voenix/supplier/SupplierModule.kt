package shop.voenix.supplier

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.country.CountryReader
import shop.voenix.validation.toRequestValidationResult

internal class SupplierModule internal constructor(internal val operations: SupplierOperations) {
    internal fun install(application: Application): Unit =
        SupplierRoutes.install(application, operations)
}

internal fun createSupplierModule(
    database: Database,
    countries: CountryReader,
): SupplierModule = SupplierModule(SupplierService(SupplierRepository(database), countries))

public fun Application.installSupplierModule(suppliers: SupplierOperations): Unit =
    SupplierRoutes.install(this, suppliers)

public fun Application.installSupplierModule(
    database: Database,
    countries: CountryReader,
): Unit = createSupplierModule(database, countries).install(this)

public fun RequestValidationConfig.validateSupplierRequests(): Unit {
    validate<SupplierInput> { input -> input.toRequestValidationResult() }
}
