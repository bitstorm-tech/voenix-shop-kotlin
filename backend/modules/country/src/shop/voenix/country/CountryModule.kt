package shop.voenix.country

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.validation.toRequestValidationResult

/**
 * The runtime handle of the installed country module, with the two capabilities it exports:
 * [reader] resolves the country a supplier points at, and [shippableCountries] answers whether a
 * checkout may send a parcel to a code (issue #81).
 */
public class CountryModule
internal constructor(
    internal val operations: CountryOperations,
    public val reader: CountryReader,
    public val shippableCountries: ShippableCountries,
) {
    internal fun install(application: Application): Unit =
        CountryRoutes.install(application, operations)
}

public fun createCountryModule(database: Database): CountryModule {
    val repository = CountryRepository(database)
    return CountryModule(
        operations = CountryService(repository),
        reader = repository,
        shippableCountries = repository,
    )
}

internal fun Application.installCountryModule(countries: CountryOperations): Unit =
    CountryRoutes.install(this, countries)

/**
 * Installs the country routes and answers the handle with both exported capabilities.
 *
 * It returns the whole handle rather than one capability because two unrelated modules read this
 * table now: the supplier module resolves a country by id, and the checkout module asks whether it
 * ships to a code.
 */
public fun Application.installCountryModule(database: Database): CountryModule {
    val module = createCountryModule(database)
    module.install(this)
    return module
}

public fun RequestValidationConfig.validateCountryRequests(): Unit {
    validate<CountryInput> { input -> input.toRequestValidationResult() }
}
