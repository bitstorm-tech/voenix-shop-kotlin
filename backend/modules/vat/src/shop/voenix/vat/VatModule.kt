package shop.voenix.vat

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.validation.toRequestValidationResult

public class VatModule
internal constructor(
    public val operations: VatOperations,
    public val reader: VatReader,
) {
    internal fun install(application: Application): Unit =
        VatRoutes.install(application, operations)
}

public fun createVatModule(database: Database): VatModule {
    val repository = VatRepository(database)
    return VatModule(
        operations = VatService(repository),
        reader = repository,
    )
}

internal fun Application.installVatModule(vats: VatOperations): Unit = VatRoutes.install(this, vats)

public fun Application.installVatModule(database: Database): VatReader {
    val module = createVatModule(database)
    module.install(this)
    return module.reader
}

public fun RequestValidationConfig.validateVatRequests(): Unit {
    validate<VatInput> { input -> input.toRequestValidationResult() }
}
