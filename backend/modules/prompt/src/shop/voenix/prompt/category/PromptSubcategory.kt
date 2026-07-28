package shop.voenix.prompt.category

import kotlinx.serialization.Serializable

/**
 * The single admin representation of a prompt subcategory.
 *
 * [categoryId] names the owning category on both sides of the contract. The legacy backend accepted
 * a flat `promptCategoryId` and answered with a nested category object; the same category is
 * already available from the category routes, so nesting it here would only make request and
 * response disagree about the shape of one relationship. The legacy list and detail DTOs were
 * field-identical, which is why there is one representation and not two.
 *
 * [position] counts inside the owning category and is response-only: create appends, delete
 * compacts, reorder rewrites, and a category change appends in the new category.
 */
@Serializable
internal data class PromptSubcategory(
    val id: Long,
    val categoryId: Long,
    val name: String,
    val description: String?,
    val position: Int,
    val active: Boolean,
)
