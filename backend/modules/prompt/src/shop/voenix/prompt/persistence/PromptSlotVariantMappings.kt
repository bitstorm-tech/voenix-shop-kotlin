package shop.voenix.prompt.persistence

import org.jetbrains.exposed.v1.core.Table

/**
 * The `prompt_slot_variant_mappings` table created by Flyway: which slot variants a prompt is
 * composed of.
 *
 * The prompt slice that writes this table arrives later; this slice only counts its rows, because
 * the number of prompts a variant is assigned to is part of the variant contract from the start.
 * `prompt_id` is therefore a plain column here and not an Exposed reference — the `prompts` table
 * has no Kotlin counterpart yet, while the foreign key in the database exists from the first
 * migration on.
 */
internal object PromptSlotVariantMappings : Table("prompt_slot_variant_mappings") {
    val promptId = long("prompt_id")
    val slotVariantId = reference("slot_variant_id", PromptSlotVariants)

    override val primaryKey = PrimaryKey(promptId, slotVariantId)
}
