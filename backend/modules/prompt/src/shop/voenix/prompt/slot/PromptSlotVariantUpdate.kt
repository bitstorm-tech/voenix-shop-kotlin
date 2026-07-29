package shop.voenix.prompt.slot

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * The update input of a slot variant: every value a variant may change.
 *
 * The slot is deliberately absent. A variant belongs to one slot for its whole life, so moving it
 * is not an operation this module offers, and an input that cannot express the move is what says
 * so. [PromptSlotVariantInput] adds the slot id for the create.
 *
 * This type also carries the one implementation of the four field rules; the create input validates
 * its slot id and then defers to this one.
 */
@Serializable
internal data class PromptSlotVariantUpdate(
    val name: String? = null,
    val prompt: String? = null,
    val description: String? = null,
    val llm: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else if (name.trim().length > MAXIMUM_NAME_LENGTH) {
            put("name", listOf("Name must be at most $MAXIMUM_NAME_LENGTH characters"))
        }

        if (prompt.isNullOrBlank()) {
            put("prompt", listOf("Prompt is required"))
        } else if (prompt.trim().length > MAXIMUM_PROMPT_LENGTH) {
            put("prompt", listOf("Prompt must be at most $MAXIMUM_PROMPT_LENGTH characters"))
        }

        if (
            !description.isNullOrBlank() && description.trim().length > MAXIMUM_DESCRIPTION_LENGTH
        ) {
            put(
                "description",
                listOf("Description must be at most $MAXIMUM_DESCRIPTION_LENGTH characters"),
            )
        }

        if (!llm.isNullOrBlank() && llm.trim().length > MAXIMUM_LLM_LENGTH) {
            put("llm", listOf("LLM must be at most $MAXIMUM_LLM_LENGTH characters"))
        }
    }

    /**
     * This input with the values the repository may store. A blank description or LLM means "none",
     * so it becomes `null` instead of an empty string.
     */
    fun normalized(): PromptSlotVariantUpdate =
        copy(
            name = checkNotNull(name).trim(),
            prompt = checkNotNull(prompt).trim(),
            description = description?.trim()?.ifBlank { null },
            llm = llm?.trim()?.ifBlank { null },
        )

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        private const val MAXIMUM_NAME_LENGTH = 255
        private const val MAXIMUM_PROMPT_LENGTH = 10_000
        private const val MAXIMUM_DESCRIPTION_LENGTH = 1000
        private const val MAXIMUM_LLM_LENGTH = 255
    }
}
