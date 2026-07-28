package shop.voenix.article.persistence

import shop.voenix.article.mug.MugDetails
import shop.voenix.article.mug.PublicMug
import shop.voenix.article.mug.PublicMugVariant

/**
 * A publicly visible mug with the *reference* to its price instead of the amount.
 *
 * It is the public counterpart of [StoredMug] and it exists for the same reason: the amount is
 * calculated by another module, from VAT entries this one does not read, so persistence answers
 * with the price id and the service resolves every id of the page in one batched
 * `PriceCatalog.find`.
 *
 * The difference to [StoredMug] is [priceId] being non-nullable and [PublicMug.price] being an
 * `Int` rather than an `Int?`: a mug only reaches this list while it is active, and the database
 * refuses an active mug without a price. Keeping the reference outside the representation is what
 * lets the representation state that fact.
 */
internal data class StoredPublicMug(
    val id: Long,
    val position: Int,
    val name: String,
    val descriptionShort: String,
    val descriptionLong: String,
    val categoryId: Long,
    val subcategoryId: Long?,
    val priceId: Long,
    val mugDetails: MugDetails,
    val variants: List<PublicMugVariant>,
) {
    /**
     * The storefront representation of this mug, with [price] as its gross sales total in cents.
     */
    fun withPrice(price: Int): PublicMug =
        PublicMug(
            id = id,
            position = position,
            name = name,
            descriptionShort = descriptionShort,
            descriptionLong = descriptionLong,
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            price = price,
            mugDetails = mugDetails,
            variants = variants,
        )
}
