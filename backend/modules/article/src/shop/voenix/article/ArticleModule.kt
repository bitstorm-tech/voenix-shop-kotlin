package shop.voenix.article

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.mug.MugArticleInput
import shop.voenix.article.mug.MugArticleOperations
import shop.voenix.article.mug.MugArticleRoutes
import shop.voenix.article.mug.MugArticleService
import shop.voenix.article.persistence.ArticleCategoryRepository
import shop.voenix.article.persistence.ArticleMugRepository
import shop.voenix.article.persistence.ArticleSubcategoryRepository
import shop.voenix.article.taxonomy.ArticleCategoryInput
import shop.voenix.article.taxonomy.ArticleCategoryOperations
import shop.voenix.article.taxonomy.ArticleCategoryRoutes
import shop.voenix.article.taxonomy.ArticleCategoryService
import shop.voenix.article.taxonomy.ArticleSubcategoryInput
import shop.voenix.article.taxonomy.ArticleSubcategoryOperations
import shop.voenix.article.taxonomy.ArticleSubcategoryRoutes
import shop.voenix.article.taxonomy.ArticleSubcategoryService
import shop.voenix.image.PublicImageStorage
import shop.voenix.pricing.PriceCatalog
import shop.voenix.supplier.SupplierReader
import shop.voenix.validation.toRequestValidationResult

/**
 * The assembled article runtime. The module is split into the sub-packages `taxonomy` (categories
 * and subcategories), `mug` (the first article type), and `persistence` (Exposed tables,
 * repositories, and the ordering lock), but it stays one compilation module: the sub-packages
 * organize the files, the module boundary is what `internal` protects.
 */
internal class ArticleModule(
    val categories: ArticleCategoryOperations,
    val subcategories: ArticleSubcategoryOperations,
    val mugs: MugArticleOperations,
) {
    fun install(application: Application) {
        ArticleCategoryRoutes.install(application, categories)
        ArticleSubcategoryRoutes.install(application, subcategories)
        MugArticleRoutes.install(application, mugs)
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
    )

/** The route test seam: installs the category routes on a caller-provided implementation. */
internal fun Application.installArticleModule(categories: ArticleCategoryOperations) {
    ArticleCategoryRoutes.install(this, categories)
}

/** The route test seam: installs the subcategory routes on a caller-provided implementation. */
internal fun Application.installArticleModule(subcategories: ArticleSubcategoryOperations) {
    ArticleSubcategoryRoutes.install(this, subcategories)
}

/** The route test seam: installs the mug routes on a caller-provided implementation. */
internal fun Application.installArticleModule(mugs: MugArticleOperations) {
    MugArticleRoutes.install(this, mugs)
}

/**
 * Installs the article admin routes. [images] is the public image storage that the example-image
 * pre-uploads write to, [prices] is the pricing capability that writes an article's price into the
 * same transaction as the article itself, and [suppliers] is the supplier capability that labels
 * the rows of the mug list with the name behind their supplier id. The module exports no capability
 * yet; the `ArticleCatalog` that Cart, Order, and Production will consume arrives with its own
 * ticket.
 */
public fun Application.installArticleModule(
    database: Database,
    images: PublicImageStorage,
    prices: PriceCatalog,
    suppliers: SupplierReader,
) {
    createArticleModule(database, images, prices, suppliers).install(this)
}

public fun RequestValidationConfig.validateArticleRequests() {
    validate<ArticleCategoryInput> { input -> input.toRequestValidationResult() }
    validate<ArticleSubcategoryInput> { input -> input.toRequestValidationResult() }
    validate<MugArticleInput> { input -> input.toRequestValidationResult() }
    validate<ReorderInput> { input -> input.toRequestValidationResult() }
}
