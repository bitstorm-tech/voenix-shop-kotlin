package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Table

/**
 * The `article_mug_variants` table created by Flyway. Like the mug itself, a variant adopts the id
 * that [ArticleVariantIdentities] minted for it.
 *
 * The one rule PostgreSQL can express about a set of variants is declared there: a partial unique
 * index on `(article_id) WHERE is_default` allows at most one default. The other half — an article
 * with variants has exactly one — is a cross-row rule of the write path.
 */
internal object ArticleMugVariants : Table("article_mug_variants") {
    val id = long("id")
    val articleId = long("article_id")
    val insideColorCode = varchar("inside_color_code", length = 255)
    val outsideColorCode = varchar("outside_color_code", length = 255)
    val name = varchar("name", length = 255)
    val isDefault = bool("is_default")
    val active = bool("active")
    val exampleImageFilename = varchar("example_image_filename", length = 255).nullable()

    override val primaryKey = PrimaryKey(id)
}
