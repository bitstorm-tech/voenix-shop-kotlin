package shop.voenix.article.mug

import kotlinx.serialization.Serializable

/**
 * One category of the storefront navigation, with the subcategories nested inside it.
 *
 * The legacy endpoint answered a map from article type to category list, so a client had to know
 * the string `"MUG"` to find the mugs. The route path names the type instead, and the answer is the
 * bare array a menu iterates over (approved deviation).
 *
 * Only taxonomy that a *visible* mug uses appears here — an empty category is not a navigation
 * entry a customer could follow, and neither is a subcategory nobody sells anything in.
 */
@Serializable
internal data class PublicMugCategory(
    val id: Long,
    val name: String,
    val position: Int,
    val subcategories: List<PublicMugSubcategory>,
)
