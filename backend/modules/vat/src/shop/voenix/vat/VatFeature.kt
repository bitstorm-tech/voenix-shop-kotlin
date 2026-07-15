package shop.voenix.vat

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.validation.toRequestValidationResult

public class VatFeature
internal constructor(
    public val operations: VatOperations,
    public val reader: VatReader,
) {
    internal fun install(application: Application): Unit =
        VatRoutes.install(application, operations)
}

public fun createVatFeature(database: Database): VatFeature {
    val repository = VatRepository(database)
    return VatFeature(
        operations = VatService(repository),
        reader = repository,
    )
}

public fun Application.installVatFeature(vats: VatOperations): Unit = VatRoutes.install(this, vats)

public fun Application.installVatFeature(database: Database): VatReader {
    val feature = createVatFeature(database)
    feature.install(this)
    return feature.reader
}

public fun RequestValidationConfig.validateVatRequests(): Unit {
    validate<VatInput> { input -> input.toRequestValidationResult() }
}
