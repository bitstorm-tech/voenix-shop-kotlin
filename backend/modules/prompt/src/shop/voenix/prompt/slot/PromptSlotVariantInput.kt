package shop.voenix.prompt.slot

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

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
    override fun validate(): ValidationErrors = buildMap {
        if (slotId == null) {
            put("slotId", listOf("Slot id is required"))
        } else if (slotId <= 0) {
            put("slotId", listOf("Slot id must be positive"))
        }

        putAll(values().validate())
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
