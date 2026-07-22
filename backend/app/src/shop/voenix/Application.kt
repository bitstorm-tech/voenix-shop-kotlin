package shop.voenix

import io.ktor.server.application.Application as KtorApplication
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.installAuthModule
import shop.voenix.country.installCountryModule
import shop.voenix.country.validateCountryRequests
import shop.voenix.db.DatabaseFactory
import shop.voenix.db.DatabaseSettings
import shop.voenix.http.installHttpRuntime
import shop.voenix.image.ImageSettings
import shop.voenix.image.installImageModule
import shop.voenix.pricing.installPricingModule
import shop.voenix.pricing.validatePricingRequests
import shop.voenix.production.installProductionModule
import shop.voenix.production.validateProductionRequests
import shop.voenix.supplier.installSupplierModule
import shop.voenix.supplier.validateSupplierRequests
import shop.voenix.vat.installVatModule
import shop.voenix.vat.validateVatRequests

public fun KtorApplication.module(): Unit = Application.install(this)

private object Application {
    fun install(application: KtorApplication) {
        with(application) {
            val databaseSettings = DatabaseSettings.from(environment.config)
            val authSettings = AuthSettings.from(environment.config)
            val imageSettings = ImageSettings.from(environment.config)
            val databaseFactory = DatabaseFactory(databaseSettings)
            try {
                val database = databaseFactory.connectAndMigrate()

                installHttpRuntime()
                install(RequestValidation) {
                    validateCountryRequests()
                    validateVatRequests()
                    validateSupplierRequests()
                    validatePricingRequests()
                    validateProductionRequests()
                }
                installAuthModule(authSettings)
                installImageModule(imageSettings)

                val countries = installCountryModule(database)
                val vats = installVatModule(database)
                installSupplierModule(database, countries)
                installPricingModule(database, vats)
                installProductionModule(database)
            } catch (exception: Exception) {
                databaseFactory.close()
                throw exception
            }

            monitor.subscribe(ApplicationStopped) { databaseFactory.close() }
        }
    }
}
