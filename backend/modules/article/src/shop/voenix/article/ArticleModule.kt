package shop.voenix.article

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.persistence.ArticleCategoryRepository
import shop.voenix.article.taxonomy.ArticleCategoryInput
import shop.voenix.article.taxonomy.ArticleCategoryOperations
import shop.voenix.article.taxonomy.ArticleCategoryRoutes
import shop.voenix.article.taxonomy.ArticleCategoryService
import shop.voenix.validation.toRequestValidationResult

/**
 * The assembled article runtime. The module is split into the sub-packages `taxonomy` (categories
 * and subcategories), `mug` (the first article type), and `persistence` (Exposed tables,
 * repositories, and the ordering lock), but it stays one compilation module: the sub-packages
 * organize the files, the module boundary is what `internal` protects.
 */
internal class ArticleModule(val categories: ArticleCategoryOperations) {
    fun install(application: Application) {
        ArticleCategoryRoutes.install(application, categories)
    }
}

internal fun createArticleModule(database: Database): ArticleModule =
    ArticleModule(categories = ArticleCategoryService(ArticleCategoryRepository(database)))

/** The route test seam: installs the routes on a caller-provided operation implementation. */
internal fun Application.installArticleModule(categories: ArticleCategoryOperations) {
    ArticleCategoryRoutes.install(this, categories)
}

/**
 * Installs the article admin routes. The module exports no capability yet; the `ArticleCatalog`
 * that Cart, Order, and Production will consume arrives with the mug slice.
 */
public fun Application.installArticleModule(database: Database) {
    createArticleModule(database).install(this)
}

public fun RequestValidationConfig.validateArticleRequests() {
    validate<ArticleCategoryInput> { input -> input.toRequestValidationResult() }
    validate<ReorderInput> { input -> input.toRequestValidationResult() }
}
