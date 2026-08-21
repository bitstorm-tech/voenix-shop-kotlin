package shop.voenix.article.mug

import kotlinx.serialization.Serializable
import shop.voenix.article.PrintAspectRatio
import shop.voenix.pricing.CalculatedPrice

/**
 * The single admin representation of a mug: what create, update, and — from the read slice on —
 * list and detail answer with.
 *
 * Two fields of the legacy DTO are deliberately absent. `articleType` said `"MUG"` on every row of
 * a route that only serves mugs, and `priceId` duplicated what the embedded price already carries.
 * Dropping the separate id is not cosmetic: no article contract accepting a price id is exactly
 * what makes a price belong to one article by construction.
 *
 * [position] is response-only. Create appends behind the last mug, delete closes the gap, and the
 * reorder route of the read slice moves one mug to the place of another.
 *
 * [printAspectRatio] is never `null`: a mug that says nothing about the shape it is printed in is a
 * mug printed the way every mug is printed, and the column carries that answer instead of leaving
 * the client to guess it.
 */
@Serializable
internal data class MugArticle(
    val id: Long,
    val position: Int,
    val name: String,
    val descriptionShort: String,
    val descriptionLong: String,
    val active: Boolean,
    val categoryId: Long?,
    val subcategoryId: Long?,
    val supplierId: Long?,
    val supplierArticleName: String?,
    val supplierArticleNumber: String?,
    val printAspectRatio: PrintAspectRatio,
    val mugDetails: MugDetails?,
    val mugVariants: List<MugVariant>,
    val price: CalculatedPrice?,
)

/**
 * One stored variant of a mug.
 *
 * It is not the same type as [MugVariantInput], because the id means something different on each
 * side: here it always exists, while in a request its absence is what asks for a new variant.
 *
 * Variants come back with the default first and are otherwise ordered by name, so a client never
 * has to sort them to show the variant a customer sees first.
 */
@Serializable
internal data class MugVariant(
    val id: Long,
    val name: String,
    val insideColorCode: String,
    val outsideColorCode: String,
    val isDefault: Boolean,
    val active: Boolean,
    val exampleImageFilename: String?,
)

/**
 * One row of the admin mug list.
 *
 * This is the one place in the mug slice where a second representation earns its keep. The list is
 * an overview table: it needs what a mug *references* spelled out — the names of its category, its
 * subcategory, and its supplier — and it does not need the descriptions, the measurements, the
 * variants, or the calculated price that [MugArticle] carries. Answering a list with the full
 * representation would mean reading every variant and recalculating every price for a screen that
 * shows none of them.
 *
 * The three names come from three different places: the two category-level names from this module's
 * own tables, the supplier name from the `SupplierReader` capability of the supplier module. All
 * three are resolved for the whole page at once, never per row.
 *
 * [exampleImageFilename] is the picture the table shows for a mug: the image of its default
 * variant, or — when the default has none — the first variant that has one, by id. A mug without
 * variants, or without a single variant image, has none.
 *
 * `articleType` is absent for the same reason it is absent from [MugArticle]: the route only ever
 * serves mugs.
 */
@Serializable
internal data class MugArticleListItem(
    val id: Long,
    val position: Int,
    val name: String,
    val active: Boolean,
    val categoryId: Long?,
    val categoryName: String?,
    val subcategoryId: Long?,
    val subcategoryName: String?,
    val supplierId: Long?,
    val supplierName: String?,
    val variantCount: Int,
    val exampleImageFilename: String?,
)
