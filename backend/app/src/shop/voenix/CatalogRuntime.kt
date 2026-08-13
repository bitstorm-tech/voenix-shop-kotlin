package shop.voenix

import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleCatalog
import shop.voenix.article.installArticleModule
import shop.voenix.country.ShippableCountries
import shop.voenix.country.installCountryModule
import shop.voenix.image.PublicImageStorage
import shop.voenix.pricing.installPricingModule
import shop.voenix.promotion.PromotionCodes
import shop.voenix.promotion.installPromotionModule
import shop.voenix.prompt.PromptCatalog
import shop.voenix.prompt.installPromptModule
import shop.voenix.supplier.SupplierReader
import shop.voenix.supplier.installSupplierModule
import shop.voenix.vat.installVatModule

/**
 * What the application's master data hands to the modules installed after it.
 *
 * The seven modules behind it are the ones an admin maintains and every customer-facing module only
 * ever *reads*: countries, VAT rates, suppliers, prices, promotions, articles, and prompts. Two of
 * them are consumed inside the group alone — a VAT rate reaches nothing but a price — so the five
 * capabilities here are the whole of what the rest of the application sees of it: [articles] and
 * [prompts] are what a cart line and an order line are priced from, [promotionCodes] is the coupon
 * lifecycle the cart and the checkout share, [shippableCountries] is the list of destinations a
 * checkout's shipping address must name (issue #81), and [suppliers] labels the rows of the admin
 * fulfillment list with the supplier they belong to.
 */
internal class CatalogRuntime(
    val articles: ArticleCatalog,
    val prompts: PromptCatalog,
    val promotionCodes: PromotionCodes,
    val shippableCountries: ShippableCountries,
    val suppliers: SupplierReader,
)

/**
 * Installs the master data of the shop, in the one order its capabilities allow: the two lookups
 * first, then the two catalogs built on them, then the promotion, and finally the two modules that
 * price something — each installed after everything it consumes.
 *
 * [images] is the public image storage an article and a prompt store their pictures in; it is the
 * only capability this group needs from outside itself.
 */
internal fun Application.installCatalogRuntime(
    database: Database,
    images: PublicImageStorage,
): CatalogRuntime {
    val countries = installCountryModule(database)
    val vats = installVatModule(database)
    val suppliers = installSupplierModule(database, countries.reader)
    val prices = installPricingModule(database, vats)
    val promotionCodes = installPromotionModule(database)
    val articles = installArticleModule(database, images, prices, suppliers)
    val prompts = installPromptModule(database, images, prices)
    return CatalogRuntime(
        articles = articles,
        prompts = prompts,
        promotionCodes = promotionCodes,
        shippableCountries = countries.shippableCountries,
        suppliers = suppliers,
    )
}
