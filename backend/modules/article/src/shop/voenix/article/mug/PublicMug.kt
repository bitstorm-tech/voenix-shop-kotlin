package shop.voenix.article.mug

import kotlinx.serialization.Serializable

/**
 * One mug as the storefront sees it.
 *
 * This is the second representation of a mug next to [MugArticle], and it exists because the
 * storefront may not see what the admin contract carries: every supplier field is absent, and so
 * are the two `active` flags — the list only contains mugs that are visible, and each of them only
 * carries variants that are.
 *
 * Three of its fields are non-nullable although the admin representation allows `null` there, and
 * that is not a convenience: the database refuses an active mug without a price, without its
 * details, and without a category. A mug that reaches this list therefore has all three, which is
 * what removes the legacy `price: 0` that the storefront showed while the cart refused the same
 * article. There is no fallback to write, because there is no case to fall back from.
 *
 * [price] is the gross sales total in integer cents, recalculated from the current VAT entries on
 * every read.
 */
@Serializable
internal data class PublicMug(
    val id: Long,
    val position: Int,
    val name: String,
    val descriptionShort: String,
    val descriptionLong: String,
    val categoryId: Long,
    val subcategoryId: Long?,
    val price: Int,
    val mugDetails: MugDetails,
    val variants: List<PublicMugVariant>,
)

/**
 * One variant a customer can order.
 *
 * It is not a smaller [MugVariant]: the storefront never sees an inactive variant at all, so
 * `active` would be `true` on every row it could ever carry. Dropping the flag is what makes the
 * filter visible in the contract instead of hiding it behind a value that never varies.
 *
 * The default variant comes first and the rest follow by name, so a client shows the variant a
 * customer sees first without sorting anything.
 */
@Serializable
internal data class PublicMugVariant(
    val id: Long,
    val name: String,
    val insideColorCode: String,
    val outsideColorCode: String,
    val isDefault: Boolean,
    val exampleImageFilename: String?,
)

/**
 * One category of the storefront navigation, with the subcategories nested inside it.
 *
 * The legacy endpoint answered a map from article type to category list, so a client had to know
 * the string `"MUG"` to find the mugs. The route path names the type instead, and the answer is the
 * bare array a menu iterates over (approved deviation).
 *
 * Only categories and subcategories that a *visible* mug uses appear here — an empty category is
 * not a navigation entry a customer could follow, and neither is a subcategory nobody sells
 * anything in.
 */
@Serializable
internal data class PublicMugCategory(
    val id: Long,
    val name: String,
    val position: Int,
    val subcategories: List<PublicMugSubcategory>,
)

/**
 * One subcategory inside a [PublicMugCategory]. It carries the example image because the storefront
 * navigation displays it, and its position because the array order is that position.
 *
 * The `description` and the `active` flag of the admin representation are absent: an invisible
 * subcategory never reaches this list, and the description is an admin note.
 */
@Serializable
internal data class PublicMugSubcategory(
    val id: Long,
    val name: String,
    val exampleImageFilename: String?,
    val position: Int,
)
