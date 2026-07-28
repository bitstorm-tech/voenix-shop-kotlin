package shop.voenix.prompt.category

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

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
    override fun validate(): ValidationErrors = buildMap {
        if (name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else if (name.trim().length > MAXIMUM_NAME_LENGTH) {
            put("name", listOf("Name must be at most $MAXIMUM_NAME_LENGTH characters"))
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
