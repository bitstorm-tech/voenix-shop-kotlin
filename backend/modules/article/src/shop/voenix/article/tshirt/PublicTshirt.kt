package shop.voenix.article.tshirt

import kotlinx.serialization.Serializable
import shop.voenix.article.ArticleType
import shop.voenix.article.PrintAspectRatio

/**
 * One t-shirt as the storefront sees it.
 *
 * It is the shirt counterpart of [shop.voenix.article.mug.PublicMug] and it leaves out the same
 * things for the same reasons: every supplier field is absent, and so are the two `active` flags —
 * the list only contains shirts that are visible, and each of them only carries variants that are.
 *
 * One omission is this type's own, and it is the important one: **the three SPOD ids are not
 * here.** They are the identifiers of the printable product at the print-on-demand partner, they
 * are the only thing a customer could use to buy the same shirt somewhere else, and a customer must
 * never learn that the partner exists at all. The colour, the size, and the picture are what a
 * shirt is to the person wearing it; the printer's vocabulary stays inside the backend.
 *
 * [articleType] is the discriminator a client switches on. It is constant per route — this list
 * only ever answers `TSHIRT` — and it is carried anyway, because a storefront that shows mugs and
 * shirts in one grid merges the two arrays and then has to tell a colour-and-size shirt from a mug
 * with measurements. See `article-package.md` for why the field returned after the article
 * migration had removed it.
 *
 * [price] is the gross sales total in integer cents, recalculated from the current VAT entries on
 * every read. Like a mug's it is non-nullable: the database refuses an active shirt without a
 * price, without a category, and with an empty frame, so a shirt that reaches this list has all
 * three. It is what a customer pays: a discount configured on the price is already subtracted.
 * [regularPrice] is the gross total before that discount and is non-`null` exactly when the price
 * carries one, so a storefront strikes it through only when there is something to strike through.
 */
@Serializable
internal data class PublicTshirt(
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
    val printAspectRatio: PrintAspectRatio,
    val sizeChartImageFilename: String?,
    val printFrame: PublicPrintFrame,
    val variants: List<PublicTshirtVariant>,
)

/**
 * The rectangle of the product mockup the generated design is placed in, in percent of the mockup.
 *
 * It is not the admin [PrintFrame]: that one serves a request as well as a response, so its four
 * percentages are nullable and an omitted one is a field error. A *stored* frame always has all
 * four — the columns are `NOT NULL` — and the storefront only ever reads, so the type it reads into
 * says so.
 *
 * The percentages are answered as `Double` although they are stored as `numeric(5, 2)`: two
 * decimals are exactly representable enough for a CSS overlay, which is the only thing this
 * rectangle is used for.
 */
@Serializable
internal data class PublicPrintFrame(
    val leftPct: Double,
    val topPct: Double,
    val widthPct: Double,
    val heightPct: Double,
)

/**
 * One shirt a customer can order: a colour in a size.
 *
 * It is not a smaller [TshirtVariant]. Besides the `active` flag that would be `true` on every row
 * the storefront could ever carry, the three SPOD ids are gone — see [PublicTshirt] for why that is
 * a rule and not an omission.
 *
 * [name] is the composed `"Black / M"`, spelled by the same `tshirtVariantName` the admin list, the
 * exported catalog, and an order line use, so a customer sees the variant under the name the order
 * will snapshot. [colorName], [colorHex], and [size] are the same name taken apart, because a
 * picker shows a colour swatch and a size button rather than one string.
 *
 * The default variant comes first and the rest follow by colour and size, so a client shows the
 * variant a customer sees first without sorting anything.
 */
@Serializable
internal data class PublicTshirtVariant(
    val id: Long,
    val name: String,
    val colorName: String,
    val colorHex: String,
    val size: String,
    val isDefault: Boolean,
    val exampleImageFilename: String?,
)
