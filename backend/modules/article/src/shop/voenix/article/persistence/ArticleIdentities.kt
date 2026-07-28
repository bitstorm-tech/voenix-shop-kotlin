package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * The `article_identities` table created by Flyway: the registry that mints the id of every
 * article, whatever its type. It carries an id and the type and nothing else, so that Cart, Order,
 * and every later consumer have one foreign-key target across the per-type tables.
 *
 * Review rule from the migration plan: this table never gains another column.
 */
internal object ArticleIdentities : LongIdTable("article_identities") {
    val articleType = text("article_type")
}
