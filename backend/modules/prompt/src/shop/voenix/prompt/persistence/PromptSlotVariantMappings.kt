package shop.voenix.prompt.persistence

import org.jetbrains.exposed.v1.core.Table

/**
 * The `prompt_slot_variant_mappings` table created by Flyway: which slot variants a prompt is
 * composed of.
 *
 * The key is only `(prompt_id, slot_variant_id)`, so one prompt may use more than one variant of
 * the same slot. Deleting a prompt takes its mappings with it; a variant a prompt still uses cannot
 * be deleted at all, which is what the "still in use" answer of the variant delete route reports
 * and what makes the count below meaningful.
 */
internal object PromptSlotVariantMappings : Table("prompt_slot_variant_mappings") {
    val promptId = reference("prompt_id", Prompts)
    val slotVariantId = reference("slot_variant_id", PromptSlotVariants)

    override val primaryKey = PrimaryKey(promptId, slotVariantId)
}
