package shop.voenix.prompt.category

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.buildValidationErrors

/**
 * The single admin representation of a prompt category. [position] is response-only: it is decided
 * by the create, delete, and reorder operations, never submitted by a client.
 *
 * A category carries no description. The legacy schema had none either, and the storefront only
 * ever shows the name, so the field would be a column nobody reads.
 */
@Serializable
internal data class PromptCategory(
    val id: Long,
    val name: String,
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
internal data class PromptCategoryInput(
    val name: String? = null,
    val active: Boolean = true,
) : Validatable {
    override fun validate(): ValidationErrors = buildValidationErrors {
        if (name.isNullOrBlank()) {
            add("name", "Name is required")
        } else if (name.trim().length > MAXIMUM_NAME_LENGTH) {
            add("name", "Name must be at most $MAXIMUM_NAME_LENGTH characters")
        }
    }

    /** This input with the value the repository may store. */
    fun normalized(): PromptCategoryInput = copy(name = checkNotNull(name).trim())

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        private const val MAXIMUM_NAME_LENGTH = 200
    }
}
