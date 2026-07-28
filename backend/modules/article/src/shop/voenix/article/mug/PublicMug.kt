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
