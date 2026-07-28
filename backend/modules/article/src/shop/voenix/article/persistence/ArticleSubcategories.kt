package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * The `article_subcategories` table created by Flyway. Exposed only maps the columns; every
 * constraint below is owned by `V13__create_articles.sql`: `LOWER(name)` is unique per category,
 * `position` is positive and unique per category with the unique rule deferred to COMMIT, and `(id,
 * category_id)` is the alternate key that `article_mugs` references, which is what makes "an
 * article's subcategory belongs to the article's category" a database fact.
 */
internal object ArticleSubcategories : LongIdTable("article_subcategories") {
    val categoryId = reference("category_id", ArticleCategories)
    val name = varchar("name", length = 200)
    val description = varchar("description", length = 1000).nullable()
    val exampleImageFilename = varchar("example_image_filename", length = 255).nullable()
    val position = integer("position")
    val active = bool("active")
}
