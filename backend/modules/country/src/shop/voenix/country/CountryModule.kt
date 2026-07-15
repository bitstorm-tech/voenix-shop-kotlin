package shop.voenix.country

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.validation.toRequestValidationResult

public class CountryModule
internal constructor(
    internal val operations: CountryOperations,
    public val reader: CountryReader,
) {
    internal fun install(application: Application): Unit =
        CountryRoutes.install(application, operations)
}

public fun createCountryModule(database: Database): CountryModule {
    val repository = CountryRepository(database)
    return CountryModule(
        operations = CountryService(repository),
        reader = repository,
    )
}

internal fun Application.installCountryModule(countries: CountryOperations): Unit =
    CountryRoutes.install(this, countries)

public fun Application.installCountryModule(database: Database): CountryReader {
    val module = createCountryModule(database)
    module.install(this)
    return module.reader
}

public fun RequestValidationConfig.validateCountryRequests(): Unit {
    validate<CountryInput> { input -> input.toRequestValidationResult() }
}
