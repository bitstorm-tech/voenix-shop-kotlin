package shop.voenix

import io.ktor.server.application.Application as KtorApplication
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import shop.voenix.account.AccountSettings
import shop.voenix.account.GuestDataClaims
import shop.voenix.account.installAccountModule
import shop.voenix.account.validateAccountRequests
import shop.voenix.article.installArticleModule
import shop.voenix.article.validateArticleRequests
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.installAuthModule
import shop.voenix.cart.installCartModule
import shop.voenix.cart.validateCartRequests
import shop.voenix.country.installCountryModule
import shop.voenix.country.validateCountryRequests
import shop.voenix.db.DatabaseFactory
import shop.voenix.db.DatabaseSettings
import shop.voenix.email.EmailSettings
import shop.voenix.http.installHttpRuntime
import shop.voenix.image.ImageSettings
import shop.voenix.image.installGuestImageRoute
import shop.voenix.image.installImageModule
import shop.voenix.magiccoins.installMagicCoinsModule
import shop.voenix.pricing.installPricingModule
import shop.voenix.pricing.validatePricingRequests
import shop.voenix.production.ProductionSettings
import shop.voenix.production.ProductionSource
import shop.voenix.production.validateProductionRequests
import shop.voenix.promotion.installPromotionModule
import shop.voenix.promotion.validatePromotionRequests
import shop.voenix.prompt.installPromptModule
import shop.voenix.prompt.validatePromptRequests
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
                installRequestValidation()
                installAuthModule(authSettings)
                val guestTokens = GuestTokens(authSettings)
                val images = installImageModule(imageSettings)

                val countries = installCountryModule(database)
                val vats = installVatModule(database)
                val suppliers = installSupplierModule(database, countries)
                val prices = installPricingModule(database, vats)
                val promotionCodes = installPromotionModule(database)
                val articles =
                    installArticleModule(database, images.publicStorage, prices, suppliers)
                val prompts = installPromptModule(database, images.publicStorage, prices)

                // The cart is the first consumer of the three catalog capabilities above, and the
                // owner of the print images the guest delivery route serves. The route itself
                // belongs to the image module, so it is installed here, once both sides exist.
                val cart =
                    installCartModule(
                        database,
                        articles,
                        prompts,
                        promotionCodes,
                        images.privateStorage,
                        guestTokens,
                    )
                installGuestImageRoute(images, guestTokens, cart.guestImages)

                val userEmails =
                    installEmailRuntime(
                        database,
                        emailSettings,
                        productionSettings,
                        unmigratedOrderSource,
                    )
                // The account module knows when a claim happens, the cart owns the rows it moves;
                // this lambda is the only place the two meet, so neither module depends on the
                // other. The Order migration extends the bound implementation, not the port.
                installAccountModule(
                    database,
                    accountSettings,
                    userEmails,
                    guestTokens,
                    GuestDataClaims { userId, guestToken ->
                        cart.guestData.claim(guestToken, userId)
                    },
                )

                installMagicCoinsModule(database, guestTokens)
            } catch (exception: Exception) {
                databaseFactory.close()
                throw exception
            }

            monitor.subscribe(ApplicationStopped) { databaseFactory.close() }
        }
    }

    /**
     * The one Request Validation plugin of the application. Every module that owns validated
     * request bodies registers its types here, so a body is checked once, in one place, before any
     * route handler sees it.
     */
    private fun KtorApplication.installRequestValidation() {
        install(RequestValidation) {
            validateCountryRequests()
            validateVatRequests()
            validateSupplierRequests()
            validatePricingRequests()
            validateProductionRequests()
            validatePromotionRequests()
            validateAccountRequests()
            validateArticleRequests()
            validatePromptRequests()
            validateCartRequests()
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
