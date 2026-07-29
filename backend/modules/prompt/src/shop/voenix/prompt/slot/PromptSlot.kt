package shop.voenix.prompt.slot

import kotlinx.serialization.Serializable

/**
 * The single admin representation of a slot.
 *
 * [position] is response-only: a create appends the slot behind the last one and nothing else ever
 * writes a slot position, so a client never submits one. [variantCount] is what the admin list
 * needs to warn before a delete that a slot still has variants.
 */
@Serializable
internal data class PromptSlot(
    val id: Long,
    val name: String,
    val position: Int,
    val variantCount: Int,
)
