package shop.voenix.article.mug

import kotlinx.serialization.Serializable

/**
 * One subcategory inside a [PublicMugCategory]. It carries the example image because the storefront
 * navigation displays it, and its position because the array order is that position.
 *
 * The `description` and the `active` flag of the admin representation are absent: an invisible
 * subcategory never reaches this list, and the description is an admin note.
 */
@Serializable
internal data class PublicMugSubcategory(
    val id: Long,
    val name: String,
    val exampleImageFilename: String?,
    val position: Int,
)
