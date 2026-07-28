package shop.voenix.article.mug

import kotlinx.serialization.Serializable

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
