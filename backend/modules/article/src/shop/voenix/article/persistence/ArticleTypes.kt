package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The `article_types` table created by Flyway. It is a registry of the known article types and
 * therefore two things at once: the foreign-key target of [ArticleIdentities], and the ordering
 * anchor of the per-type article positions — one row per sequence, the same idea as the single
 * category anchor of the taxonomy.
 */
internal object ArticleTypes : Table("article_types") {
    val articleType = text("article_type")

    override val primaryKey = PrimaryKey(articleType)
}

/**
 * Locks the ordering anchor of [articleType] for the current transaction.
 *
 * Article positions are dense and unique per type, so create, delete compaction, and reorder all
 * rewrite the same sequence. A preliminary `SELECT max(position)` cannot protect it: under `READ
 * COMMITTED` two creates read the same maximum and write it twice. Every position writer therefore
 * queues on this one row first and only afterwards reads what it decides from, because every
 * following statement takes a fresh snapshot.
 *
 * The lock is released when the surrounding transaction commits or rolls back.
 */
internal fun lockArticleTypeForOrderingInTransaction(articleType: String) {
    checkNotNull(
        ArticleTypes.selectAll()
            .where { ArticleTypes.articleType eq articleType }
            .forUpdate()
            .singleOrNull()
    ) {
        "The ordering anchor row of article type $articleType is missing"
    }
}
