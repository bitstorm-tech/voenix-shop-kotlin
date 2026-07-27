package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

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

/**
 * Locks the given category rows for the current transaction and reports whether every one of them
 * exists.
 *
 * Subcategory positions are dense and unique *per category*, so the category row is the anchor its
 * subcategory position writers queue on — the same idea as the single anchor row of the category
 * order, one anchor per category. Holding the row has a second effect the writes rely on: while the
 * target category is locked it cannot disappear, so the reference from a subcategory to it can no
 * longer fail, and a foreign-key violation of the write means the one remaining relationship.
 *
 * The rows are locked one statement at a time in ascending id order. A move between two categories
 * locks two rows, and two moves in opposite directions would deadlock if each took its rows in the
 * order it happens to need them.
 */
internal fun lockCategoriesForOrderingInTransaction(categoryIds: Collection<Long>): Boolean =
    categoryIds.distinct().sorted().all { categoryId ->
        ArticleCategories.selectAll()
            .where { ArticleCategories.id eq categoryId }
            .forUpdate()
            .singleOrNull() != null
    }
