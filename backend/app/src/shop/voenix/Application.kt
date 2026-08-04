package shop.voenix

import io.ktor.server.application.Application as KtorApplication
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.account.installAccountModule
import shop.voenix.account.validateAccountRequests
import shop.voenix.article.validateArticleRequests
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.installAuthModule
import shop.voenix.cart.installCartModule
import shop.voenix.cart.validateCartRequests
import shop.voenix.checkout.installCheckoutModule
import shop.voenix.checkout.validateCheckoutRequests
import shop.voenix.country.validateCountryRequests
import shop.voenix.db.DatabaseFactory
import shop.voenix.generator.installGeneratorModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.image.installGuestImageRoute
import shop.voenix.image.installImageModule
import shop.voenix.magiccoins.installMagicCoinsModule
import shop.voenix.order.installOrderModule
import shop.voenix.payment.MollieSettings
import shop.voenix.payment.installPaymentModule
import shop.voenix.pricing.validatePricingRequests
import shop.voenix.production.validateProductionRequests
import shop.voenix.promotion.validatePromotionRequests
import shop.voenix.prompt.validatePromptRequests
import shop.voenix.ratelimit.ClientIpRateLimiter
import shop.voenix.supplier.validateSupplierRequests
import shop.voenix.vat.validateVatRequests

public fun KtorApplication.module(): Unit = Application.install(this)

/**
 * The composition test seam: the whole application, with the payment module pointed at [mollie]
 * instead of at the configured provider.
 *
 * `MollieSettings.apiUrl` is deliberately not a configuration key (deviation D24: a deployment must
 * never be able to send payments somewhere else), so a test that wants the composed application to
 * talk to a local Mollie stub has no way in through the config — and proving that the webhook, the
 * order confirm, and the late-bound status source really are wired together needs exactly that.
 * This overload is that one way in, and nothing but a test calls it.
 */
internal fun KtorApplication.module(mollie: MollieSettings): Unit =
    Application.install(this, mollie)

private object Application {
    fun install(
        application: KtorApplication,
        mollie: MollieSettings? = null,
    ) {
        with(application) {
            val settings = ApplicationSettings.from(environment.config, mollie)
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
     * the three exceptions are the ones that could not be resolved that way — the guest image
     * route, the production source, and the payment status source, each of which belongs to a
     * module installed *before* the one that can answer it and is therefore bound afterwards.
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

        // The master data every customer-facing module reads and none of them writes.
        val catalog = installCatalogRuntime(database, images.publicStorage)

        // Production and email run long before an order exists and each declared a port for it. The
        // late-bound source is what makes that installable in one pass: production is installed
        // with it, the order module is installed with production's outbox and PDF generator, and
        // both ports are bound immediately afterwards.
        val productionSource = LateBoundProductionSource()
        val paymentStatus = LateBoundPaymentStatus()
        val emails =
            installEmailRuntime(database, settings.email, settings.production, productionSource)
        val order =
            installOrderModule(
                database = database,
                articles = catalog.articles,
                promotions = catalog.promotionCodes,
                productionOutbox = emails.production.outbox,
                emailOutbox = emails.emailOutbox,
                printImages = images.privateStorage,
                payments = paymentStatus,
                productionPdfs = emails.production.pdfGenerator,
                guestTokens = guestTokens,
            )
        productionSource.bind(order.productionSource)
        emails.bindOrderConfirmations(order.orderConfirmations)

        // Payment is installed after order and given the two writes the order module exports. The
        // edge runs payment → order on purpose: the order module declares what a payment may do to
        // an order, and only this module knows Mollie. The third late-bound port closes right
        // after: an order read asks payment for its `paymentStatus`, which is why the order module
        // was installed with the late-bound source above.
        val payments = installPaymentModule(database, settings.mollie, order.payments)
        paymentStatus.bind(payments.statusSource)

        // The cart is the first consumer of the three catalog capabilities above, and the owner of
        // the print images the guest delivery route serves. The route itself belongs to the image
        // module, so it is installed here, once both sides exist. It comes after the order module
        // because reordering an ordered line is a cart route reading order data; nothing in the
        // order module needs a cart, so this direction is the only one either module has.
        val cart =
            installCartModule(
                database,
                catalog.articles,
                catalog.prompts,
                catalog.promotionCodes,
                images.privateStorage,
                order.orderItems,
                guestTokens,
            )
        installGuestImageRoute(images, guestTokens, cart.guestImages)

        // The checkout is the last consumer in the chain: it is the one place where the cart, the
        // promotion, the order, the payment, and the country list meet, and it exports nothing in
        // return. It owns no table and opens no transaction — every step it runs commits inside
        // the module it calls.
        installCheckoutModule(
            carts = cart.checkoutCarts,
            promotions = catalog.promotionCodes,
            orders = order.placement,
            orderPayments = order.payments,
            payments = payments.starter,
            shippableCountries = catalog.shippableCountries,
            guestTokens = guestTokens,
        )

        installAccountModule(
            database,
            settings.account,
            emails.userEmails,
            guestTokens,
            IndependentGuestDataClaims(cart.guestData::claim, order.guestData::claim),
        )

        // The generator is the only consumer of the Magic Coins capability, and the second consumer
        // of the prompt catalog. Whether it talks to fal.ai or hands the upload back unchanged is
        // decided inside the module, by these settings alone. Its endpoint is the one anonymous
        // request that spends provider money, so it is the only one carrying a per-IP rate limit —
        // platform's policy, built here and installed by the module (issue #78).
        val coins = installMagicCoinsModule(database, guestTokens)
        val rateLimiter = ClientIpRateLimiter(settings.rateLimit)
        installGeneratorModule(settings.generator, catalog.prompts, coins, guestTokens, rateLimiter)
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
            validateCheckoutRequests()
        }
    }
}
