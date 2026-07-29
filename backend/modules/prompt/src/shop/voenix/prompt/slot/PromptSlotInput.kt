package shop.voenix.prompt.slot

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * The shared create/update input. A slot carries nothing but its name — its position is owned by
 * the create operation — so both writes accept the same field with the same rule.
 */
@Serializable
internal data class PromptSlotInput(val name: String? = null) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else if (name.trim().length > MAXIMUM_NAME_LENGTH) {
            put("name", listOf("Name must be at most $MAXIMUM_NAME_LENGTH characters"))
        }
    }

    /** This input with the value the repository may store. */
    fun normalized(): PromptSlotInput = copy(name = checkNotNull(name).trim())

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        private const val MAXIMUM_NAME_LENGTH = 255
    }
}
