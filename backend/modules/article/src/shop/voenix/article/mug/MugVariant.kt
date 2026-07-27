package shop.voenix.article.mug

import kotlinx.serialization.Serializable

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
