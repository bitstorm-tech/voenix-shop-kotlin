package shop.voenix.article

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.persistence.ArticleCategoryRepository
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
) {
    fun install(application: Application) {
        ArticleCategoryRoutes.install(application, categories)
        ArticleSubcategoryRoutes.install(application, subcategories)
    }
}

internal fun createArticleModule(
    database: Database,
    images: PublicImageStorage,
): ArticleModule =
    ArticleModule(
        categories = ArticleCategoryService(ArticleCategoryRepository(database)),
        subcategories = ArticleSubcategoryService(ArticleSubcategoryRepository(database), images),
    )

/** The route test seam: installs the category routes on a caller-provided implementation. */
internal fun Application.installArticleModule(categories: ArticleCategoryOperations) {
    ArticleCategoryRoutes.install(this, categories)
}

/** The route test seam: installs the subcategory routes on a caller-provided implementation. */
internal fun Application.installArticleModule(subcategories: ArticleSubcategoryOperations) {
    ArticleSubcategoryRoutes.install(this, subcategories)
}

/**
 * Installs the article admin routes. [images] is the public image storage that the example-image
 * pre-uploads write to. The module exports no capability yet; the `ArticleCatalog` that Cart,
 * Order, and Production will consume arrives with the mug slice.
 */
public fun Application.installArticleModule(
    database: Database,
    images: PublicImageStorage,
) {
    createArticleModule(database, images).install(this)
}

public fun RequestValidationConfig.validateArticleRequests() {
    validate<ArticleCategoryInput> { input -> input.toRequestValidationResult() }
    validate<ArticleSubcategoryInput> { input -> input.toRequestValidationResult() }
    validate<ReorderInput> { input -> input.toRequestValidationResult() }
}
