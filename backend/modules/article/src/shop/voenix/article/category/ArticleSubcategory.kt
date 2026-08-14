package shop.voenix.article.category

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * The single admin representation of a subcategory.
 *
 * [categoryId] names the owning category on both sides of the contract. The legacy backend accepted
 * a flat `articleCategoryId` and answered with a nested category object; the same category is
 * already available from the category routes, so nesting it here would only make request and
 * response disagree about the shape of one relationship.
 *
 * [position] counts inside the owning category and is response-only: create appends, delete
 * compacts, reorder rewrites, and a category change appends in the new category.
 */
@Serializable
internal data class ArticleSubcategory(
    val id: Long,
    val categoryId: Long,
    val name: String,
    val description: String?,
    val exampleImageFilename: String?,
    val position: Int,
    val active: Boolean,
)

/**
 * The shared create/update input. Both operations accept the same fields with the same rules and
 * replace every stored value.
 *
 * [exampleImageFilename] is the file name a previous pre-upload returned. An absent or `null` value
 * therefore means "this subcategory has no example image", which is how an existing image is
 * removed. Whether the named file really exists is not a field rule: only the image storage can
 * answer that, so the service checks it while saving.
 *
 * The position is deliberately absent: it is owned by the create, reorder, and category-change
 * operations.
 */
@Serializable
internal data class ArticleSubcategoryInput(
    val categoryId: Long? = null,
    val name: String? = null,
    val description: String? = null,
    val exampleImageFilename: String? = null,
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
     * This input with the values the repository may store. A blank text means "nothing was
     * submitted", so it becomes `null` instead of an empty string.
     */
    fun normalized(): ArticleSubcategoryInput =
        copy(
            name = checkNotNull(name).trim(),
            description = description?.trim()?.ifBlank { null },
            exampleImageFilename = exampleImageFilename?.trim()?.ifBlank { null },
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
