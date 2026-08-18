package shop.voenix.article.category

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.buildValidationErrors

/**
 * The single admin representation of a category. [position] is response-only: it is decided by the
 * create and reorder operations, never submitted by a client.
 */
@Serializable
internal data class ArticleCategory(
    val id: Long,
    val name: String,
    val description: String?,
    val position: Int,
    val active: Boolean,
)

/**
 * The shared create/update input. Both operations accept the same fields with the same rules and
 * replace every stored value, so one input type carries the one `validate()` implementation.
 *
 * The position is deliberately absent: it is owned by the create and reorder operations.
 */
@Serializable
internal data class ArticleCategoryInput(
    val name: String? = null,
    val description: String? = null,
    val active: Boolean = true,
) : Validatable {
    override fun validate(): ValidationErrors = buildValidationErrors {
        if (name.isNullOrBlank()) {
            add("name", "Name is required")
        } else if (name.trim().length > MAXIMUM_NAME_LENGTH) {
            add("name", "Name must be at most $MAXIMUM_NAME_LENGTH characters")
        }

        if (
            !description.isNullOrBlank() && description.trim().length > MAXIMUM_DESCRIPTION_LENGTH
        ) {
            add("description", "Description must be at most $MAXIMUM_DESCRIPTION_LENGTH characters")
        }
    }

    /**
     * This input with the values the repository may store. A blank description means "no
     * description", so it becomes `null` instead of an empty string.
     */
    fun normalized(): ArticleCategoryInput =
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
