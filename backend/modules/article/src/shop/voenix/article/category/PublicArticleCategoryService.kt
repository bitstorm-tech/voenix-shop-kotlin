package shop.voenix.article.category

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.persistence.PublicArticleCategoryRepository
import shop.voenix.operation.OperationResult
import shop.voenix.operation.databaseOperation

/**
 * The storefront half of the category slice: the shop menu, across every article type.
 *
 * It needs no pricing and no image storage — a navigation entry is a name, a picture's file name,
 * and a position — so this service is nothing but the repository's read with the one failure a
 * storefront read can report.
 */
internal class PublicArticleCategoryService(
    private val repository: PublicArticleCategoryRepository
) : PublicArticleCategoryOperations {
    override suspend fun list(): OperationResult<List<PublicArticleCategory>> =
        logger.databaseOperation(
            "Database error while listing public article categories",
            OperationResult.UnexpectedFailure,
        ) {
            OperationResult.Success(repository.list())
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PublicArticleCategoryService::class.java)
    }
}

/**
 * The storefront read of the category slice.
 *
 * It is a separate seam from [ArticleCategoryOperations] for the reason every storefront seam is
 * one: the admin routes read what is *stored*, this one reads what a customer may *see*. It is also
 * the only storefront seam of this module that is not tied to an article type — see
 * [PublicArticleCategory] for why the navigation is shared.
 */
internal interface PublicArticleCategoryOperations {
    /**
     * The storefront navigation: the categories that visible articles of any type sit in, each with
     * the subcategories those articles use, both in display order.
     *
     * A category nobody sells a visible article in does not appear, and neither does a subcategory
     * no visible article uses — a customer would follow it into an empty list.
     */
    suspend fun list(): OperationResult<List<PublicArticleCategory>>
}
