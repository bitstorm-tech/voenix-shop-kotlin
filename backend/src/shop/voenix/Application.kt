package shop.voenix

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.ApplicationAuth
import shop.voenix.auth.AuthSettings
import shop.voenix.country.CountryInput
import shop.voenix.country.CountryOperations
import shop.voenix.country.CountryRepository
import shop.voenix.country.CountryRoutes
import shop.voenix.country.CountryService
import shop.voenix.db.DatabaseFactory
import shop.voenix.db.DatabaseSettings
import shop.voenix.http.HttpRuntime
import shop.voenix.pricing.PriceInput
import shop.voenix.pricing.PriceOperations
import shop.voenix.pricing.PriceRepository
import shop.voenix.pricing.PriceRoutes
import shop.voenix.pricing.PriceService
import shop.voenix.supplier.SupplierInput
import shop.voenix.supplier.SupplierOperations
import shop.voenix.supplier.SupplierRepository
import shop.voenix.supplier.SupplierRoutes
import shop.voenix.supplier.SupplierService
import shop.voenix.validation.Validatable
import shop.voenix.vat.VatInput
import shop.voenix.vat.VatOperations
import shop.voenix.vat.VatRepository
import shop.voenix.vat.VatRoutes
import shop.voenix.vat.VatService

fun Application.module() {
    val databaseSettings = DatabaseSettings.from(environment.config)
    val authSettings = AuthSettings.from(environment.config)
    val databaseFactory = DatabaseFactory(databaseSettings)
    try {
        val database = databaseFactory.connectAndMigrate()
        installHttpRuntime()
        ApplicationAuth.install(this, authSettings)
        countryModule(database)
        vatModule(database)
        priceModule(database)
        supplierModule(database)
    } catch (exception: Exception) {
        databaseFactory.close()
        throw exception
    }

    monitor.subscribe(ApplicationStopped) { databaseFactory.close() }
}

internal fun Application.installHttpRuntime() {
    HttpRuntime.install(this)
    install(RequestValidation) {
        validate<CountryInput>(Validatable::toValidationResult)
        validate<VatInput>(Validatable::toValidationResult)
        validate<PriceInput>(Validatable::toValidationResult)
        validate<SupplierInput>(Validatable::toValidationResult)
    }
}

fun Application.countryModule(database: Database) {
    val countries = CountryService(CountryRepository(database))
    countryModule(countries)
}

fun Application.countryModule(countries: CountryOperations) {
    CountryRoutes.install(this, countries)
}

fun Application.vatModule(database: Database) {
    val vats = VatService(VatRepository(database))
    vatModule(vats)
}

fun Application.vatModule(vats: VatOperations) {
    VatRoutes.install(this, vats)
}

fun Application.priceModule(database: Database) {
    val prices = PriceService(PriceRepository(database))
    priceModule(prices)
}

fun Application.priceModule(prices: PriceOperations) {
    PriceRoutes.install(this, prices)
}

fun Application.supplierModule(database: Database) {
    val suppliers = SupplierService(SupplierRepository(database))
    supplierModule(suppliers)
}

fun Application.supplierModule(suppliers: SupplierOperations) {
    SupplierRoutes.install(this, suppliers)
}

private fun Validatable.toValidationResult(): ValidationResult =
    validate().let { errors ->
        if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors.values.flatten())
        }
    }
