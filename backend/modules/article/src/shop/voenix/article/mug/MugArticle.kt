package shop.voenix.article.mug

import kotlinx.serialization.Serializable
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
    val mugDetails: MugDetails?,
    val mugVariants: List<MugVariant>,
    val price: CalculatedPrice?,
)
