package shop.voenix.prompt.persistence

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * The `prompt_slots` table created by Flyway. Exposed only maps the columns; every constraint is
 * owned by `V14__create_prompts.sql`: `LOWER(name)` is unique, `position` is positive and unique
 * with the unique rule deferred to COMMIT.
 *
 * Positions are unique but not dense: nothing compacts them after a delete, and there is no reorder
 * route. The gaps are intentional.
 */
internal object PromptSlots : LongIdTable("prompt_slots") {
    val name = varchar("name", length = 255)
    val position = integer("position")
}
