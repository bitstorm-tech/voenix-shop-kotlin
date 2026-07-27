package shop.voenix.article.mug

import kotlinx.serialization.Serializable

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
 * The three names come from three different places: the two taxonomy names from this module's own
 * tables, the supplier name from the `SupplierReader` capability of the supplier module. All three
 * are resolved for the whole page at once, never per row.
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
