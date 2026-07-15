package shop.voenix

import io.ktor.server.application.Application as KtorApplication
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import shop.voenix.auth.ApplicationAuth
import shop.voenix.auth.AuthSettings
import shop.voenix.country.installCountryFeature
import shop.voenix.country.validateCountryRequests
import shop.voenix.db.DatabaseFactory
import shop.voenix.db.DatabaseSettings
import shop.voenix.http.installHttpRuntime
import shop.voenix.pricing.installPricingFeature
import shop.voenix.pricing.validatePricingRequests
import shop.voenix.supplier.installSupplierFeature
import shop.voenix.supplier.validateSupplierRequests
import shop.voenix.vat.installVatFeature
import shop.voenix.vat.validateVatRequests

public fun KtorApplication.module(): Unit = Application.install(this)

private object Application {
    fun install(application: KtorApplication) {
        with(application) {
            val databaseSettings = DatabaseSettings.from(environment.config)
            val authSettings = AuthSettings.from(environment.config)
            val databaseFactory = DatabaseFactory(databaseSettings)
            try {
                val database = databaseFactory.connectAndMigrate()

                installHttpRuntime()
                install(RequestValidation) {
                    validateCountryRequests()
                    validateVatRequests()
                    validateSupplierRequests()
                    validatePricingRequests()
                }
                ApplicationAuth.install(this, authSettings)

                val countries = installCountryFeature(database)
                val vats = installVatFeature(database)
                installSupplierFeature(database, countries)
                installPricingFeature(database, vats)
            } catch (exception: Exception) {
                databaseFactory.close()
                throw exception
            }

            monitor.subscribe(ApplicationStopped) { databaseFactory.close() }
        }
    }
}
