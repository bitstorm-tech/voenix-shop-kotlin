package shop.voenix.prompt.slot

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.buildValidationErrors

/**
 * The single admin representation of a slot variant.
 *
 * The slot is flat: [slotId] is what a client writes back, [slotName] is what it displays. A nested
 * slot object would repeat data the admin client already has from the slot list.
 *
 * [assignedPromptCount] is the number of prompts that use this variant, which is what makes the
 * "still in use" answer of the delete route predictable in the user interface.
 */
@Serializable
internal data class PromptSlotVariant(
    val id: Long,
    val slotId: Long,
    val slotName: String,
    val name: String,
    val prompt: String,
    val description: String?,
    val llm: String?,
    val assignedPromptCount: Int,
)

/**
 * The create input of a slot variant: the fields of [PromptSlotVariantUpdate] plus the slot the
 * variant is created in.
 *
 * This is the one place in the module where create and update genuinely differ, because the slot is
 * decided once and never again. The four shared field rules are not repeated here: [values] hands
 * them to the update input, which owns their single implementation.
 */
@Serializable
internal data class PromptSlotVariantInput(
    val slotId: Long? = null,
    val name: String? = null,
    val prompt: String? = null,
    val description: String? = null,
    val llm: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildValidationErrors {
        if (slotId == null) {
            add("slotId", "Slot id is required")
        } else if (slotId <= 0) {
            add("slotId", "Slot id must be positive")
        }

        addAll(values().validate())
    }

    /** The fields this input shares with an update, so that both validate and normalize once. */
    fun values(): PromptSlotVariantUpdate =
        PromptSlotVariantUpdate(
            name = name,
            prompt = prompt,
            description = description,
            llm = llm,
        )
}

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
    override fun validate(): ValidationErrors = buildValidationErrors {
        if (name.isNullOrBlank()) {
            add("name", "Name is required")
        } else if (name.trim().length > MAXIMUM_NAME_LENGTH) {
            add("name", "Name must be at most $MAXIMUM_NAME_LENGTH characters")
        }

        if (prompt.isNullOrBlank()) {
            add("prompt", "Prompt is required")
        } else if (prompt.trim().length > MAXIMUM_PROMPT_LENGTH) {
            add("prompt", "Prompt must be at most $MAXIMUM_PROMPT_LENGTH characters")
        }

        if (
            !description.isNullOrBlank() && description.trim().length > MAXIMUM_DESCRIPTION_LENGTH
        ) {
            add("description", "Description must be at most $MAXIMUM_DESCRIPTION_LENGTH characters")
        }

        if (!llm.isNullOrBlank() && llm.trim().length > MAXIMUM_LLM_LENGTH) {
            add("llm", "LLM must be at most $MAXIMUM_LLM_LENGTH characters")
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
