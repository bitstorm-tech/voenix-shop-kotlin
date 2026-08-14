package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Table
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

/**
 * The single-row `article_category_ordering` table. It stores nothing: its one row exists to be
 * locked, so that every transaction which writes a category position queues behind the others.
 */
internal object ArticleCategoryOrdering : Table("article_category_ordering") {
    private val id = integer("id")

    override val primaryKey = PrimaryKey(id)
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
 * The rows are locked one statement at a time in ascending id order, and that order is only worth
 * anything while *every* writer of more than one category row uses this function: the subcategory
 * and mug writes as well as the category reorder and the delete compaction, which decide their rows
 * from a display order that has nothing to do with the ids. Two writers that each took the rows in
 * the order they happen to need them would deadlock, and the category anchor does not prevent it —
 * the writers of the other slices never take that anchor.
 */
internal fun lockCategoriesForOrderingInTransaction(categoryIds: Collection<Long>): Boolean =
    categoryIds.distinct().sorted().all { categoryId ->
        ArticleCategories.selectAll()
            .where { ArticleCategories.id eq categoryId }
            .forUpdate()
            .singleOrNull() != null
    }

/**
 * Locks the category ordering anchor for the current transaction.
 *
 * Category positions are dense and unique, so creating, deleting, and reordering a category all
 * rewrite the same sequence. A preliminary read cannot protect that sequence: under `READ
 * COMMITTED` two transactions would read the same maximum position and then write it twice. Both
 * writers therefore queue on this one row first and only afterwards read the positions they decide
 * from, because every following statement takes a fresh snapshot.
 *
 * The lock is released when the surrounding transaction commits or rolls back.
 */
internal fun lockCategoryOrderingInTransaction() {
    checkNotNull(ArticleCategoryOrdering.selectAll().forUpdate().singleOrNull()) {
        "The article category ordering anchor row is missing"
    }
}
