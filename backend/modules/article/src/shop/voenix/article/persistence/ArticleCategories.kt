package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * The `article_categories` table created by Flyway. Exposed only maps the columns; it never creates
 * or alters the schema, so every constraint below is owned by `V13__create_articles.sql`:
 * `LOWER(name)` is unique, `position` is positive and unique with the unique rule deferred to
 * COMMIT.
 */
internal object ArticleCategories : LongIdTable("article_categories") {
    val name = varchar("name", length = 200)
    val description = varchar("description", length = 1000).nullable()
    val position = integer("position")
    val active = bool("active")
}
