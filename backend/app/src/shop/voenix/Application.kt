package shop.voenix

import io.ktor.server.application.Application as KtorApplication
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.account.installAccountModule
import shop.voenix.account.validateAccountRequests
import shop.voenix.article.installArticleModule
import shop.voenix.article.validateArticleRequests
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.installAuthModule
import shop.voenix.cart.installCartModule
import shop.voenix.cart.validateCartRequests
import shop.voenix.country.installCountryModule
import shop.voenix.country.validateCountryRequests
import shop.voenix.db.DatabaseFactory
import shop.voenix.generator.installGeneratorModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.image.installGuestImageRoute
import shop.voenix.image.installImageModule
import shop.voenix.magiccoins.installMagicCoinsModule
import shop.voenix.order.installOrderModule
import shop.voenix.payment.installPaymentModule
import shop.voenix.pricing.installPricingModule
import shop.voenix.pricing.validatePricingRequests
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
            val settings = ApplicationSettings.from(environment.config)
            val databaseFactory = DatabaseFactory(settings.database)
            try {
                installModules(databaseFactory.connectAndMigrate(), settings)
            } catch (exception: Exception) {
                databaseFactory.close()
                throw exception
            }

            monitor.subscribe(ApplicationStopped) { databaseFactory.close() }
        }
    }

    /**
     * The composition itself: every module of the application, installed in the one order their
     * capabilities allow.
     *
     * The order is not a style question. A module is installed after everything it consumes, and
     * the two exceptions are the ones that could not be resolved that way — the guest image route
     * and the production source, both of which belong to a module installed *before* the one that
     * can answer them and are therefore bound afterwards.
     */
    private fun KtorApplication.installModules(
        database: Database,
        settings: ApplicationSettings,
    ) {
        installHttpRuntime()
        installRequestValidation()
        installAuthModule(settings.auth)
        val guestTokens = GuestTokens(settings.auth)
        val images = installImageModule(settings.image)

        val countries = installCountryModule(database)
        val vats = installVatModule(database)
        val suppliers = installSupplierModule(database, countries)
        val prices = installPricingModule(database, vats)
        val promotionCodes = installPromotionModule(database)
        val articles = installArticleModule(database, images.publicStorage, prices, suppliers)
        val prompts = installPromptModule(database, images.publicStorage, prices)

        // Production and email run long before an order exists and each declared a port for it. The
        // late-bound source is what makes that installable in one pass: production is installed
        // with it, the order module is installed with production's outbox and PDF generator, and
        // both ports are bound immediately afterwards.
        val productionSource = LateBoundProductionSource()
        val emails =
            installEmailRuntime(database, settings.email, settings.production, productionSource)
        val order =
            installOrderModule(
                database = database,
                articles = articles,
                promotions = promotionCodes,
                productionOutbox = emails.production.outbox,
                emailOutbox = emails.emailOutbox,
                printImages = images.privateStorage,
                productionPdfs = emails.production.pdfGenerator,
                guestTokens = guestTokens,
            )
        productionSource.bind(order.productionSource)
        emails.bindOrderConfirmations(order.orderConfirmations)

        // Payment is installed after order and given the two writes the order module exports. The
        // edge runs payment → order on purpose: the order module declares what a payment may do to
        // an order, and only this module knows Mollie.
        installPaymentModule(database, settings.mollie, order.payments)

        // The cart is the first consumer of the three catalog capabilities above, and the owner of
        // the print images the guest delivery route serves. The route itself belongs to the image
        // module, so it is installed here, once both sides exist. It comes after the order module
        // because reordering an ordered line is a cart route reading order data; nothing in the
        // order module needs a cart, so this direction is the only one either module has.
        val cart =
            installCartModule(
                database,
                articles,
                prompts,
                promotionCodes,
                images.privateStorage,
                order.orderItems,
                guestTokens,
            )
        installGuestImageRoute(images, guestTokens, cart.guestImages)

        installAccountModule(
            database,
            settings.account,
            emails.userEmails,
            guestTokens,
            IndependentGuestDataClaims(cart.guestData::claim, order.guestData::claim),
        )

        // The generator is the only consumer of the Magic Coins capability, and the second consumer
        // of the prompt catalog. Whether it talks to fal.ai or hands the upload back unchanged is
        // decided inside the module, by these settings alone.
        val coins = installMagicCoinsModule(database, guestTokens)
        installGeneratorModule(settings.generator, prompts, coins, guestTokens)
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
}
