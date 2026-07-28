package shop.voenix.article.mug

import shop.voenix.operation.OperationResult

/**
 * The two storefront reads of the mug slice.
 *
 * They are a separate seam from [MugArticleOperations] because they answer a different client with
 * a different rule: the admin routes read what is *stored*, these read what a customer may *see*.
 * Nothing here takes an id, an input, or a token.
 */
internal interface PublicMugOperations {
    /**
     * The mugs a customer may buy, in display order, each with its gross sales price in cents and
     * only its active variants.
     *
     * Visible means active, in an active category, and either without a subcategory or in an active
     * one. A mug that fails any of those is not in the list — including one that is active while
     * its category is not.
     */
    suspend fun list(): OperationResult<List<PublicMug>>

    /**
     * The storefront navigation: the categories that visible mugs sit in, each with the
     * subcategories those mugs use, both in display order.
     *
     * A category nobody sells a visible mug in does not appear, and neither does a subcategory no
     * visible mug uses — a customer would follow it into an empty list.
     */
    suspend fun listCategories(): OperationResult<List<PublicMugCategory>>
}
