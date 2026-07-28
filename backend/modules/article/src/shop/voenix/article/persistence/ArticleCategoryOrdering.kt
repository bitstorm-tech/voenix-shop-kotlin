package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The single-row `article_category_ordering` table. It stores nothing: its one row exists to be
 * locked, so that every transaction which writes a category position queues behind the others.
 */
internal object ArticleCategoryOrdering : Table("article_category_ordering") {
    private val id = integer("id")

    override val primaryKey = PrimaryKey(id)
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
