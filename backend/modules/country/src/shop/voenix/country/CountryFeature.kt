package shop.voenix.country

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.validation.toRequestValidationResult

public class CountryFeature
internal constructor(
    internal val operations: CountryOperations,
    public val reader: CountryReader,
) {
    internal fun install(application: Application): Unit =
        CountryRoutes.install(application, operations)
}

public fun createCountryFeature(database: Database): CountryFeature {
    val repository = CountryRepository(database)
    return CountryFeature(
        operations = CountryService(repository),
        reader = repository,
    )
}

public fun Application.installCountryFeature(countries: CountryOperations): Unit =
    CountryRoutes.install(this, countries)

public fun Application.installCountryFeature(database: Database): CountryReader {
    val feature = createCountryFeature(database)
    feature.install(this)
    return feature.reader
}

public fun RequestValidationConfig.validateCountryRequests(): Unit {
    validate<CountryInput> { input -> input.toRequestValidationResult() }
}
