package shop.voenix

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.routing.IgnoreTrailingSlash
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.country.AuthSettings
import shop.voenix.country.CountryAuth
import shop.voenix.country.CountryOperations
import shop.voenix.country.CountryRepository
import shop.voenix.country.CountryRoutes
import shop.voenix.country.CountryService
import shop.voenix.db.DatabaseFactory
import shop.voenix.db.DatabaseSettings

fun Application.module() {
    val databaseSettings = DatabaseSettings.from(environment.config)
    val authSettings = AuthSettings.from(environment.config)
    val databaseFactory = DatabaseFactory(databaseSettings)
    try {
        val database = databaseFactory.connectAndMigrate()
        countryModule(database, authSettings)
    } catch (exception: Exception) {
        databaseFactory.close()
        throw exception
    }

    monitor.subscribe(ApplicationStopped) {
        databaseFactory.close()
    }
}

fun Application.countryModule(
    database: Database,
    authSettings: AuthSettings,
) {
    val countries = CountryService(CountryRepository(database))
    countryModule(countries, authSettings)
}

fun Application.countryModule(
    countries: CountryOperations,
    authSettings: AuthSettings,
) {
    install(IgnoreTrailingSlash)
    CountryAuth.install(this, authSettings)
    CountryRoutes.install(this, countries)
}
