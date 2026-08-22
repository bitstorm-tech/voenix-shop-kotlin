package shop.voenix.vat

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.validation.toRequestValidationResult

internal class VatModule(
    val operations: VatOperations,
    val reader: VatReader,
)

internal fun createVatModule(database: Database): VatModule {
    val repository = VatRepository(database)
    return VatModule(
        operations = VatService(repository),
        reader = repository,
    )
}

/**
 * Builds only the read capability of the VAT module, without installing its admin routes.
 *
 * Production code never needs this: [installVatModule] installs the routes and returns the same
 * [VatReader]. The factory exists so an integration test in a consuming compilation module, such as
 * Pricing, can obtain a real-database reader without also mounting the VAT admin API. It returns
 * nothing but the public production capability, which keeps the assembled handle internal as
 * described in `docs/dev/backend/conventions/module-architecture.md`.
 */
public fun createVatReader(database: Database): VatReader = VatRepository(database)

public fun Application.installVatModule(database: Database): VatReader {
    val module = createVatModule(database)
    installVatRoutes(module.operations)
    return module.reader
}

public fun RequestValidationConfig.validateVatRequests() {
    validate<VatInput> { input -> input.toRequestValidationResult() }
}
