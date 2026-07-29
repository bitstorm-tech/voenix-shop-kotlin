package shop.voenix.prompt.persistence

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * The `prompt_slot_variants` table created by Flyway. Every constraint is owned by
 * `V14__create_prompts.sql`: `LOWER(name)` is unique across *all* slots, and the slot reference
 * restricts, so a slot that still has variants cannot be deleted.
 */
internal object PromptSlotVariants : LongIdTable("prompt_slot_variants") {
    val slotId = reference("slot_id", PromptSlots)
    val name = varchar("name", length = 255)
    val prompt = text("prompt")
    val description = varchar("description", length = 1000).nullable()
    val llm = varchar("llm", length = 255).nullable()
}
