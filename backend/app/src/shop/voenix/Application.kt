package shop.voenix

import io.ktor.server.application.Application as KtorApplication
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import shop.voenix.account.AccountSettings
import shop.voenix.account.installAccountModule
import shop.voenix.account.validateAccountRequests
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.installAuthModule
import shop.voenix.country.installCountryModule
import shop.voenix.country.validateCountryRequests
import shop.voenix.db.DatabaseFactory
import shop.voenix.db.DatabaseSettings
import shop.voenix.email.EmailSettings
import shop.voenix.http.installHttpRuntime
import shop.voenix.image.ImageSettings
import shop.voenix.image.installImageModule
import shop.voenix.magiccoins.installMagicCoinsModule
import shop.voenix.pricing.installPricingModule
import shop.voenix.pricing.validatePricingRequests
import shop.voenix.production.ProductionSettings
import shop.voenix.production.ProductionSource
import shop.voenix.production.validateProductionRequests
import shop.voenix.promotion.installPromotionModule
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
            val emailSettings = EmailSettings.from(environment.config)
            val productionSettings = ProductionSettings.from(environment.config)
            val accountSettings = AccountSettings.from(environment.config)
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
                    validateAccountRequests()
                }
                installAuthModule(authSettings)
                installImageModule(imageSettings)

                val countries = installCountryModule(database)
                val vats = installVatModule(database)
                installSupplierModule(database, countries)
                installPricingModule(database, vats)
                installPromotionModule(database)

                val userEmails =
                    installEmailRuntime(
                        database,
                        emailSettings,
                        productionSettings,
                        unmigratedOrderSource,
                    )
                installAccountModule(database, accountSettings, userEmails)

                installMagicCoinsModule(database, GuestTokens(authSettings))
            } catch (exception: Exception) {
                databaseFactory.close()
                throw exception
            }

            monitor.subscribe(ApplicationStopped) { databaseFactory.close() }
        }
    }

    /**
     * The Order migration replaces this with the real order-backed source. Until then every load
     * fails with an [IllegalStateException], which the production and email workers record as the
     * retryable `SOURCE_UNAVAILABLE` — never as a silent "order does not exist".
     */
    private val unmigratedOrderSource = ProductionSource {
        error("Order production source is not migrated yet")
    }
}
