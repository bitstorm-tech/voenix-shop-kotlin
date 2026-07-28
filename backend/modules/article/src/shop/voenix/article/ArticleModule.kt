package shop.voenix.article

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.category.ArticleCategoryInput
import shop.voenix.article.category.ArticleCategoryOperations
import shop.voenix.article.category.ArticleCategoryRoutes
import shop.voenix.article.category.ArticleCategoryService
import shop.voenix.article.category.ArticleSubcategoryInput
import shop.voenix.article.category.ArticleSubcategoryOperations
import shop.voenix.article.category.ArticleSubcategoryRoutes
import shop.voenix.article.category.ArticleSubcategoryService
import shop.voenix.article.mug.MugArticleInput
import shop.voenix.article.mug.MugArticleOperations
import shop.voenix.article.mug.MugArticleRoutes
import shop.voenix.article.mug.MugArticleService
import shop.voenix.article.mug.PublicMugOperations
import shop.voenix.article.mug.PublicMugRoutes
import shop.voenix.article.mug.PublicMugService
import shop.voenix.article.persistence.ArticleCatalogRepository
import shop.voenix.article.persistence.ArticleCategoryRepository
import shop.voenix.article.persistence.ArticleMugRepository
import shop.voenix.article.persistence.ArticleSubcategoryRepository
import shop.voenix.article.persistence.PublicMugRepository
import shop.voenix.image.PublicImageStorage
import shop.voenix.pricing.PriceCatalog
import shop.voenix.supplier.SupplierReader
import shop.voenix.validation.toRequestValidationResult

/**
 * The assembled article runtime. The module is split into the sub-packages `category` (categories
 * and subcategories), `mug` (the first article type), and `persistence` (Exposed tables,
 * repositories, and the ordering lock), but it stays one compilation module: the sub-packages
 * organize the files, the module boundary is what `internal` protects.
 */
internal class ArticleModule(
    val categories: ArticleCategoryOperations,
    val subcategories: ArticleSubcategoryOperations,
    val mugs: MugArticleOperations,
    val publicMugs: PublicMugOperations,
    val catalog: ArticleCatalog,
) {
    fun install(application: Application) {
        ArticleCategoryRoutes.install(application, categories)
        ArticleSubcategoryRoutes.install(application, subcategories)
        MugArticleRoutes.install(application, mugs)
        PublicMugRoutes.install(application, publicMugs)
    }
}

internal fun createArticleModule(
    database: Database,
    images: PublicImageStorage,
    prices: PriceCatalog,
    suppliers: SupplierReader,
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
        publicMugs = PublicMugService(PublicMugRepository(database), prices),
        catalog = ArticleCatalogService(ArticleCatalogRepository(database), prices),
    )

/** The route test seam: installs the category routes on a caller-provided implementation. */
internal fun Application.installArticleModule(categories: ArticleCategoryOperations) {
    ArticleCategoryRoutes.install(this, categories)
}

/** The route test seam: installs the subcategory routes on a caller-provided implementation. */
internal fun Application.installArticleModule(subcategories: ArticleSubcategoryOperations) {
    ArticleSubcategoryRoutes.install(this, subcategories)
}

/** The route test seam: installs the admin mug routes on a caller-provided implementation. */
internal fun Application.installArticleModule(mugs: MugArticleOperations) {
    MugArticleRoutes.install(this, mugs)
}

/** The route test seam: installs the storefront routes on a caller-provided implementation. */
internal fun Application.installArticleModule(mugs: PublicMugOperations) {
    PublicMugRoutes.install(this, mugs)
}

/**
 * Installs the article admin routes and the anonymous storefront routes, and returns the
 * [ArticleCatalog] capability. [images] is the public image storage that the example-image
 * pre-uploads write to, [prices] is the pricing capability that writes an article's price into the
 * same transaction as the article itself, and [suppliers] is the supplier capability that labels
 * the rows of the mug list with the name behind their supplier id.
 *
 * The composition root discards the returned capability for now, exactly as it discards Promotion's
 * `PromotionCodes`: no migrated module resolves article references yet. Cart, Order, and the
 * production adapter behind them will bind it.
 */
public fun Application.installArticleModule(
    database: Database,
    images: PublicImageStorage,
    prices: PriceCatalog,
    suppliers: SupplierReader,
): ArticleCatalog {
    val module = createArticleModule(database, images, prices, suppliers)
    module.install(this)
    return module.catalog
}

public fun RequestValidationConfig.validateArticleRequests() {
    validate<ArticleCategoryInput> { input -> input.toRequestValidationResult() }
    validate<ArticleSubcategoryInput> { input -> input.toRequestValidationResult() }
    validate<MugArticleInput> { input -> input.toRequestValidationResult() }
    validate<ReorderInput> { input -> input.toRequestValidationResult() }
}
