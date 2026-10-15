package shop.voenix.article.spod

import java.time.Instant
import kotlinx.serialization.Serializable
import shop.voenix.json.InstantIso8601Serializer
import shop.voenix.pricing.CalculatedPrice

/**
 * The single admin representation of a SPOD article: what update, detail, and reorder answer with.
 *
 * A SPOD article is any product the print-on-demand partner prints one front motif on — a t-shirt,
 * a tote bag. Which product it is, is data: [productTypeName] is the partner's name for it.
 *
 * It is [shop.voenix.article.mug.MugArticle] read a second time, and it leaves out the same two
 * fields for the same reasons: `articleType` would say `"SPOD"` on every row of a route that only
 * serves SPOD articles, and a `priceId` would duplicate what the embedded price already carries.
 *
 * Most of this is response-only. A SPOD article has two owners, and the admin writes only the
 * shop's half (see [SpodArticleInput]): the name, the descriptions, the supplier, the product type,
 * the size chart, the colours with their pictures, the variants, and the measured print frame are
 * the sync's, and they are answered so an admin can *see* the article it is editing the shop-owned
 * half of.
 *
 * [printFrame] and [hotspot] are `null` until a sync run has measured them; such an article cannot
 * be activated. [position] is response-only for the older reason: delete closes the gap, and the
 * reorder route moves one article to the place of another.
 */
@Serializable
internal data class SpodArticle(
    val id: Long,
    val position: Int,
    val name: String,
    val descriptionShort: String,
    val descriptionLong: String,
    val active: Boolean,
    val categoryId: Long?,
    val subcategoryId: Long?,
    val supplierId: Long,
    val productTypeName: String,
    val sizeChartImageFilename: String?,
    val printFrame: PrintFrame?,
    val hotspot: String?,
    val appearances: List<SpodAppearance>,
    val spodVariants: List<SpodVariant>,
    val price: CalculatedPrice?,
    val sync: SpodArticleSync,
)

/**
 * Where a SPOD article comes from and what the last sync run saw.
 *
 * The three parts of the identity are answered separately rather than joined into one string,
 * because an admin screen shows them separately: which installation ([environment]) of which
 * destination the article belongs to, and which article it is over there ([spodArticleId]).
 *
 * [missingSince] is the visible half of the disappearance rule: an article the partner no longer
 * lists is deactivated and marked instead of deleted, because it may come back and the shop-owned
 * half of the row would otherwise be gone for good. It is `null` for every article the last run
 * found.
 */
@Serializable
internal data class SpodArticleSync(
    val spodArticleId: String,
    val environment: String,
    @Serializable(with = InstantIso8601Serializer::class) val syncedAt: Instant,
    @Serializable(with = InstantIso8601Serializer::class) val missingSince: Instant? = null,
)

/**
 * The rectangle of the mockup the generated design is placed in, in percent of the mockup.
 *
 * A sync run measures it; nobody types it. The admin and the storefront answer it with the same
 * type, because both only ever read it. The percentages are answered as `Double` although they are
 * stored as `numeric(5, 2)`: two decimals are exact enough for a CSS overlay, which is the only
 * thing this rectangle is used for.
 */
@Serializable
internal data class PrintFrame(
    val leftPct: Double,
    val topPct: Double,
    val widthPct: Double,
    val heightPct: Double,
)

/**
 * One colour of a SPOD article — an *appearance* in the partner's words — with its pictures.
 *
 * [mockupImageFilename] is the blank front image the editor lays [SpodArticle.printFrame] on;
 * [listingImageFilenames] are every picture the partner lists for the colour, in its order. Both
 * are written by the sync, and a colour without a picture sells nothing.
 */
@Serializable
internal data class SpodAppearance(
    val id: Long,
    val colorName: String,
    val colorHex: String,
    val position: Int,
    val mockupImageFilename: String?,
    val listingImageFilenames: List<String>,
)

/**
 * One stored variant of a SPOD article: a colour ([appearanceId]) in a size, and the printable
 * product those two name at the print-on-demand partner.
 *
 * The whole type is response-only. A variant is written by the sync alone, and the admin's only say
 * about the array is which of its entries is the default one ([SpodArticleInput.defaultVariantId]).
 *
 * [name] is composed rather than stored — `"Black / M"` — and it is composed in exactly one place,
 * `spodVariantName` in the persistence package, so the admin list, the storefront, the exported
 * catalog, and an order line cannot spell a variant three different ways.
 *
 * [spodVariantId] and [sku] are the partner's own names for this row. Neither is a key here — the
 * sync matches a variant by the three SPOD ids, which is what production orders by — but an
 * operator comparing the admin screen with the backoffice needs to find the same row over there.
 *
 * Variants come back in the order of their colour, then by id.
 */
@Serializable
internal data class SpodVariant(
    val id: Long,
    val name: String,
    val appearanceId: Long,
    val sizeLabel: String,
    val spodProductTypeId: Long,
    val spodAppearanceId: Long,
    val spodSizeId: Long,
    val spodVariantId: String,
    val sku: String?,
    val isDefault: Boolean,
    val active: Boolean,
)

/**
 * One row of the admin SPOD article list.
 *
 * It carries the same twelve fields the mug list row carries, and for the same reasons: the
 * overview table needs what an article *references* spelled out — the names of its category, its
 * subcategory, and its supplier — and it needs neither the descriptions, nor the frame, nor the
 * variants, nor the calculated price that [SpodArticle] carries.
 *
 * [exampleImageFilename] is the picture the table shows: the first listing image of the default
 * variant's colour.
 *
 * The two sync fields are what the overview needs from the second owner, and no more: [syncedAt]
 * says how current the partner's data is, and [missingAtSpreadconnect] is the row's warning sign.
 * The detail read answers the rest of the identity.
 */
@Serializable
internal data class SpodArticleListItem(
    val id: Long,
    val position: Int,
    val name: String,
    val active: Boolean,
    val categoryId: Long?,
    val categoryName: String?,
    val subcategoryId: Long?,
    val subcategoryName: String?,
    val supplierId: Long,
    val supplierName: String?,
    val variantCount: Int,
    val exampleImageFilename: String?,
    @Serializable(with = InstantIso8601Serializer::class) val syncedAt: Instant,
    val missingAtSpreadconnect: Boolean,
)
