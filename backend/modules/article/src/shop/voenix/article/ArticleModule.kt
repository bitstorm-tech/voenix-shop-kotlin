package shop.voenix.article

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.category.ArticleCategoryInput
import shop.voenix.article.category.ArticleCategoryOperations
import shop.voenix.article.category.ArticleCategoryService
import shop.voenix.article.category.ArticleSubcategoryInput
import shop.voenix.article.category.ArticleSubcategoryOperations
import shop.voenix.article.category.ArticleSubcategoryService
import shop.voenix.article.category.PublicArticleCategoryOperations
import shop.voenix.article.category.PublicArticleCategoryService
import shop.voenix.article.category.installArticleCategoryRoutes
import shop.voenix.article.category.installArticleSubcategoryRoutes
import shop.voenix.article.category.installPublicArticleCategoryRoutes
import shop.voenix.article.mug.MugArticleInput
import shop.voenix.article.mug.MugArticleOperations
import shop.voenix.article.mug.MugArticleService
import shop.voenix.article.mug.PublicMugOperations
import shop.voenix.article.mug.PublicMugService
import shop.voenix.article.mug.installMugArticleRoutes
import shop.voenix.article.mug.installPublicMugRoutes
import shop.voenix.article.persistence.ArticleCatalogRepository
import shop.voenix.article.persistence.ArticleCategoryRepository
import shop.voenix.article.persistence.ArticleMugRepository
import shop.voenix.article.persistence.ArticleSpodRepository
import shop.voenix.article.persistence.ArticleSpodSyncRepository
import shop.voenix.article.persistence.ArticleSubcategoryRepository
import shop.voenix.article.persistence.PublicArticleCategoryRepository
import shop.voenix.article.persistence.PublicMugRepository
import shop.voenix.article.persistence.PublicSpodArticleRepository
import shop.voenix.article.spod.PublicSpodArticleOperations
import shop.voenix.article.spod.PublicSpodArticleService
import shop.voenix.article.spod.SpodArticleInput
import shop.voenix.article.spod.SpodArticleOperations
import shop.voenix.article.spod.SpodArticleService
import shop.voenix.article.spod.SpodCatalogSync
import shop.voenix.article.spod.SpodCatalogSyncService
import shop.voenix.article.spod.installPublicSpodArticleRoutes
import shop.voenix.article.spod.installSpodArticleRoutes
import shop.voenix.image.PublicImageStorage
import shop.voenix.pricing.PriceCatalog
import shop.voenix.spod.SpodClient
import shop.voenix.supplier.SupplierReader
import shop.voenix.validation.toRequestValidationResult

/**
 * The assembled article runtime. The module is split into the sub-packages `category` (categories
 * and subcategories), `mug` (the first article type), `spod` (the second one), and `persistence`
 * (Exposed tables, repositories, and the ordering lock), but it stays one compilation module: the
 * sub-packages organize the files, the module boundary is what `internal` protects.
 *
 * The handle itself is public for the same reason `ImageModule` is: the composition root has to
 * hand two different capabilities to two different modules — the catalog to everything that stores
 * an article reference, the sync to the production module that triggers it. Everything else on it
 * stays internal.
 */
@Suppress("LongParameterList")
public class ArticleModule
internal constructor(
    internal val categories: ArticleCategoryOperations,
    internal val subcategories: ArticleSubcategoryOperations,
    internal val mugs: MugArticleOperations,
    internal val spodArticles: SpodArticleOperations,
    internal val storefront: ArticleStorefront,
    /** Resolves the article-variant references Cart, Order, Checkout, and the Generator store. */
    public val catalog: ArticleCatalog,
    /** Reconciles one destination's Spreadconnect catalog; the production module triggers it. */
    public val spodSync: SpodCatalogSync,
)

/**
 * The three anonymous reads, held together because they are one client: the shop.
 *
 * They are grouped rather than listed next to the admin seams for the same reason the routes are
 * installed outside the `authenticate` block — what a customer may see is one rule with three
 * answers, and a reader looking for it should find all three in one place. One of them, the
 * navigation, belongs to no article type at all.
 */
internal class ArticleStorefront(
    val mugs: PublicMugOperations,
    val spodArticles: PublicSpodArticleOperations,
    val categories: PublicArticleCategoryOperations,
)

internal fun createArticleModule(
    database: Database,
    images: PublicImageStorage,
    prices: PriceCatalog,
    suppliers: SupplierReader,
    spod: SpodClient,
): ArticleModule =
    ArticleModule(
        categories = ArticleCategoryService(ArticleCategoryRepository(database)),
        subcategories = ArticleSubcategoryService(ArticleSubcategoryRepository(database), images),
        mugs =
            MugArticleService(
                ArticleMugRepository(database, prices),
                images,
                prices,
                suppliers,
            ),
        spodArticles =
            SpodArticleService(
                ArticleSpodRepository(database, prices),
                images,
                prices,
                suppliers,
            ),
        storefront =
            ArticleStorefront(
                mugs = PublicMugService(PublicMugRepository(database), prices),
                spodArticles =
                    PublicSpodArticleService(PublicSpodArticleRepository(database), prices),
                categories =
                    PublicArticleCategoryService(PublicArticleCategoryRepository(database)),
            ),
        catalog = ArticleCatalogService(ArticleCatalogRepository(database), prices),
        spodSync = SpodCatalogSyncService(ArticleSpodSyncRepository(database), spod, images),
    )

/**
 * Installs the article admin routes and the anonymous storefront routes, and returns the handle the
 * composition root reads the module's two capabilities off. [images] is the public image storage
 * that the example-image pre-uploads and the SPOD article sync write to, [prices] is the pricing
 * capability that writes an article's price into the same transaction as the article itself,
 * [suppliers] is the supplier capability that labels the rows of every admin article list with the
 * name behind their supplier id, and [spod] is the application's single Spreadconnect client, whose
 * pacer keeps every call of every module inside the partner's request budget.
 *
 * The composition root binds [ArticleModule.catalog] to the cart module, which resolves the
 * `(articleId, variantId)` pair of every line it renders through it, and [ArticleModule.spodSync]
 * to the production module, which triggers a sync on one of its destinations.
 */
public fun Application.installArticleModule(
    database: Database,
    images: PublicImageStorage,
    prices: PriceCatalog,
    suppliers: SupplierReader,
    spod: SpodClient,
): ArticleModule {
    val module = createArticleModule(database, images, prices, suppliers, spod)
    installArticleCategoryRoutes(module.categories)
    installArticleSubcategoryRoutes(module.subcategories)
    installMugArticleRoutes(module.mugs)
    installSpodArticleRoutes(module.spodArticles)
    installPublicMugRoutes(module.storefront.mugs)
    installPublicSpodArticleRoutes(module.storefront.spodArticles)
    installPublicArticleCategoryRoutes(module.storefront.categories)
    return module
}

public fun RequestValidationConfig.validateArticleRequests() {
    validate<ArticleCategoryInput> { input -> input.toRequestValidationResult() }
    validate<ArticleSubcategoryInput> { input -> input.toRequestValidationResult() }
    validate<MugArticleInput> { input -> input.toRequestValidationResult() }
    validate<SpodArticleInput> { input -> input.toRequestValidationResult() }
    validate<ReorderInput> { input -> input.toRequestValidationResult() }
}
