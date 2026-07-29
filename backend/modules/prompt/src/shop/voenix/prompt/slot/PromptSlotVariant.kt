package shop.voenix.prompt.slot

import kotlinx.serialization.Serializable

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
