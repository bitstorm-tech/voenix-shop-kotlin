package shop.voenix.article.spod

import kotlinx.serialization.Serializable
import shop.voenix.article.ArticleType

/**
 * One SPOD article as the storefront sees it.
 *
 * It is the SPOD counterpart of [shop.voenix.article.mug.PublicMug] and it leaves out the same
 * things for the same reasons: every supplier field is absent, and so are the `active` flags — the
 * list only contains articles that are visible, and each of them only carries variants that are.
 *
 * One omission is this type's own, and it is the important one: **the three SPOD ids and the
 * hotspot are not here.** They are the identifiers of the printable product at the print-on-demand
 * partner, they are the only thing a customer could use to buy the same product somewhere else, and
 * a customer must never learn that the partner exists at all. The colour, the size, and the picture
 * are what an article is to the person buying it; the printer's vocabulary stays inside the
 * backend.
 *
 * [articleType] is the discriminator a client switches on. It is constant per route — this list
 * only ever answers `SPOD` — and it is carried anyway, because a storefront that shows mugs and
 * SPOD articles in one grid merges the two arrays and then has to tell one from the other. See
 * `article-package.md` for why the field returned after the article migration had removed it.
 *
 * [productTypeName] is the partner's name of the product ("Men's T-Shirt", "Tote Bag"), which the
 * storefront shows as the subtitle; it may be empty. [printFrame] is never `null` here: the
 * database refuses an active article without a measured frame. The print is always square, so there
 * is no aspect ratio to answer.
 *
 * [price] is the gross sales total in integer cents, recalculated from the current VAT entries on
 * every read. Like a mug's it is non-nullable: the database refuses an active article without a
 * price. It is what a customer pays: a discount configured on the price is already subtracted.
 * [regularPrice] is the gross total before that discount and is non-`null` exactly when the price
 * carries one, so a storefront strikes it through only when there is something to strike through.
 *
 * [appearances] are the colours that still have something for sale, in the partner's order, and
 * [variants] are flat: each names the colour it belongs to by [PublicSpodVariant.appearanceId], so
 * a cart line, the default variant, and the wizard keep pointing at one variant id.
 */
@Serializable
internal data class PublicSpodArticle(
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
    val productTypeName: String,
    val sizeChartImageFilename: String?,
    val printFrame: PrintFrame,
    val appearances: List<PublicSpodAppearance>,
    val variants: List<PublicSpodVariant>,
)

/**
 * One colour a customer can pick, with its pictures: the blank [mockupImageFilename] the editor
 * places the motif on, and the [listingImageFilenames] the shop shows the colour with.
 */
@Serializable
internal data class PublicSpodAppearance(
    val id: Long,
    val colorName: String,
    val colorHex: String,
    val mockupImageFilename: String?,
    val listingImageFilenames: List<String>,
)

/**
 * One product a customer can order: a colour ([appearanceId]) in a size.
 *
 * It is not a smaller [SpodVariant]. Besides the `active` flag that would be `true` on every row
 * the storefront could ever carry, the three SPOD ids are gone — see [PublicSpodArticle] for why
 * that is a rule and not an omission.
 *
 * [name] is the composed `"Black / M"`, spelled by the same `spodVariantName` the admin list, the
 * exported catalog, and an order line use, so a customer sees the variant under the name the order
 * will snapshot.
 */
@Serializable
internal data class PublicSpodVariant(
    val id: Long,
    val name: String,
    val appearanceId: Long,
    val size: String,
    val isDefault: Boolean,
)
