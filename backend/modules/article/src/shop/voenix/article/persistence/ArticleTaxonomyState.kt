package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The single-row `article_taxonomy_state` table. It stores nothing: its one row exists to be
 * locked, so that every transaction which writes a category position queues behind the others.
 */
internal object ArticleTaxonomyState : Table("article_taxonomy_state") {
    private val id = integer("id")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Locks the taxonomy ordering anchor for the current transaction.
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
    checkNotNull(ArticleTaxonomyState.selectAll().forUpdate().singleOrNull()) {
        "The article taxonomy ordering anchor row is missing"
    }
}
