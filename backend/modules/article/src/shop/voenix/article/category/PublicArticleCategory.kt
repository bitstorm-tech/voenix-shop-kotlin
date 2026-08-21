package shop.voenix.article.category

import kotlinx.serialization.Serializable

/**
 * One category of the storefront navigation, with the subcategories nested inside it.
 *
 * The navigation is **type-agnostic**: a category appears when a visible article of *any* type sits
 * in it, and the answer says nothing about which types those are. That is what a shop menu is —
 * `Apparel` is one entry whether it holds shirts, mugs, or both — and it is why the route is
 * `/api/articles/categories` rather than one route per type.
 *
 * The legacy endpoint answered a map from article type to category list, so a client had to know
 * the string `"MUG"` to find the mugs. The answer here is the bare array a menu iterates over. The
 * mug-only `/api/articles/mugs/categories` that the article migration introduced is gone with the
 * second article type: it could only ever answer half a menu.
 *
 * Only categories and subcategories that a *visible* article uses appear here — an empty category
 * is not a navigation entry a customer could follow, and neither is a subcategory nobody sells
 * anything in.
 */
@Serializable
internal data class PublicArticleCategory(
    val id: Long,
    val name: String,
    val position: Int,
    val subcategories: List<PublicArticleSubcategory>,
)

/**
 * One subcategory inside a [PublicArticleCategory]. It carries the example image because the
 * storefront navigation displays it, and its position because the array order is that position.
 *
 * The `description` and the `active` flag of the admin representation are absent: an invisible
 * subcategory never reaches this list, and the description is an admin note.
 */
@Serializable
internal data class PublicArticleSubcategory(
    val id: Long,
    val name: String,
    val exampleImageFilename: String?,
    val position: Int,
)
