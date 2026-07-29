package shop.voenix.prompt.persistence

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * The `prompts` table created by Flyway. Exposed only maps the columns; every rule is owned by
 * `V14__create_prompts.sql`: `position` is positive and unique with the unique rule deferred to
 * COMMIT, the subcategory is referenced *together with* its category by a composite foreign key,
 * and `price_id` is nullable, unique, and restricted.
 *
 * Two of the four references are plain columns rather than Exposed `reference`s, and neither of
 * them could be one: the subcategory is only half of a composite key, and the price belongs to the
 * pricing module, whose table this module does not map.
 */
internal object Prompts : LongIdTable("prompts") {
    val position = integer("position")
    val title = varchar("title", length = 255)
    val promptText = text("prompt_text")
    val categoryId = reference("category_id", PromptCategories)
    val subcategoryId = long("subcategory_id").nullable()
    val exampleImageFilename = varchar("example_image_filename", length = 255).nullable()
    val priceId = long("price_id").nullable()
    val llm = varchar("llm", length = 255).nullable()
    val active = bool("active")
    val archived = bool("archived")
}
