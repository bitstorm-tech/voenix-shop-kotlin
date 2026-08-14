package shop.voenix.prompt.category

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

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

/**
 * The shared create/update input. Both operations accept the same fields with the same rules and
 * replace every stored value, including the owning category.
 *
 * The position is deliberately absent: it is owned by the create, reorder, and category-change
 * operations. Whether the submitted category exists is not a field rule either — only the database
 * can answer that, so it becomes a field error while the write runs.
 */
@Serializable
internal data class PromptSubcategoryInput(
    val categoryId: Long? = null,
    val name: String? = null,
    val description: String? = null,
    val active: Boolean = true,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        when {
            categoryId == null -> put("categoryId", listOf("CategoryId is required"))
            categoryId <= 0 -> put("categoryId", listOf("CategoryId must be positive"))
        }

        if (name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else if (name.trim().length > MAXIMUM_NAME_LENGTH) {
            put("name", listOf("Name must be at most $MAXIMUM_NAME_LENGTH characters"))
        }

        if (
            !description.isNullOrBlank() && description.trim().length > MAXIMUM_DESCRIPTION_LENGTH
        ) {
            put(
                "description",
                listOf("Description must be at most $MAXIMUM_DESCRIPTION_LENGTH characters"),
            )
        }
    }

    /**
     * This input with the values the repository may store. A blank description means "no
     * description", so it becomes `null` instead of an empty string.
     */
    fun normalized(): PromptSubcategoryInput =
        copy(
            name = checkNotNull(name).trim(),
            description = description?.trim()?.ifBlank { null },
        )

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        private const val MAXIMUM_NAME_LENGTH = 200
        private const val MAXIMUM_DESCRIPTION_LENGTH = 1000
    }
}
