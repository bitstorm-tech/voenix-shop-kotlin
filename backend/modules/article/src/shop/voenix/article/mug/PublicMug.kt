package shop.voenix.article.mug

import kotlinx.serialization.Serializable
import shop.voenix.article.ArticleType

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
 * [articleType] is the discriminator a client switches on. It is constant per route — this list
 * only ever answers `MUG` — and it is carried anyway: with a second article type in the shop, a
 * storefront that shows mugs and shirts in one grid merges the two arrays and then has to tell them
 * apart. The article migration had removed the field for the opposite reason, when a mug was the
 * only type there was; `article-package.md` records the reversal.
 *
 * [price] is the gross sales total in integer cents, recalculated from the current VAT entries on
 * every read. It is what a customer pays: a discount configured on the price is already subtracted.
 * [regularPrice] is the gross total before that discount and is non-`null` exactly when the price
 * carries one, so a storefront strikes it through only when there is something to strike through.
 */
@Serializable
internal data class PublicMug(
    val articleType: ArticleType,
    val id: Long,
    val position: Int,
    val name: String,
    val descriptionShort: String,
    val descriptionLong: String,
    val categoryId: Long,
    val subcategoryId: Long?,
    val price: Int,
    val regularPrice: Int?,
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
