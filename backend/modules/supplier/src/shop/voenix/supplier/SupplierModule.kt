package shop.voenix.supplier

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.country.CountryReader
import shop.voenix.validation.toRequestValidationResult

internal class SupplierModule
internal constructor(
    internal val operations: SupplierOperations,
    internal val reader: SupplierReader,
) {
    internal fun install(application: Application): Unit =
        application.installSupplierRoutes(operations)
}

internal fun createSupplierModule(
    database: Database,
    countries: CountryReader,
): SupplierModule {
    val repository = SupplierRepository(database)
    return SupplierModule(
        operations = SupplierService(repository, countries),
        reader = repository,
    )
}

/**
 * Installs the admin supplier routes and returns the [SupplierReader] capability. The composition
 * root does not bind it yet; the Article migration will, so that an article list can label its rows
 * with supplier names without importing this module's table or repository.
 */
public fun Application.installSupplierModule(
    database: Database,
    countries: CountryReader,
): SupplierReader {
    val module = createSupplierModule(database, countries)
    module.install(this)
    return module.reader
}

public fun RequestValidationConfig.validateSupplierRequests(): Unit {
    validate<SupplierInput> { input -> input.toRequestValidationResult() }
}
