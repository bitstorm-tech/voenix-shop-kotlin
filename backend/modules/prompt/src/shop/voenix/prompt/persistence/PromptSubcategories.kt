package shop.voenix.prompt.persistence

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * The `prompt_subcategories` table created by Flyway. Exposed only maps the columns; every
 * constraint is owned by `V14__create_prompts.sql`: `LOWER(name)` is unique per category,
 * `position` is positive and unique per category with the unique rule deferred to COMMIT, and `(id,
 * category_id)` is the alternate key that `prompts` references, which is what makes "a prompt's
 * subcategory belongs to the prompt's category" a database fact instead of a preliminary read.
 */
internal object PromptSubcategories : LongIdTable("prompt_subcategories") {
    val categoryId = reference("category_id", PromptCategories)
    val name = varchar("name", length = 200)
    val description = varchar("description", length = 1000).nullable()
    val position = integer("position")
    val active = bool("active")
}
