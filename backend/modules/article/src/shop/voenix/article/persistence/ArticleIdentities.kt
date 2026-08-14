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

/**
 * The `article_variant_identities` table created by Flyway: the same registry one level down. Its
 * composite foreign key makes "this variant belongs to that article" a database fact, and deleting
 * a row cascades into the per-type variant table.
 *
 * Review rule from the migration plan: this table never gains another column.
 */
internal object ArticleVariantIdentities : LongIdTable("article_variant_identities") {
    val articleId = long("article_id")
    val articleType = text("article_type")
}
