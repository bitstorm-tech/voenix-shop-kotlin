package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.mug.MugDetails

/**
 * The `article_mugs` table created by Flyway: the legacy `articles` row and its
 * `article_mug_details` row merged into the table of one article type.
 *
 * The id is not generated here — [ArticleIdentities] mints it and this row adopts it, which is what
 * makes the identity the parent of the whole article. Exposed only maps the columns; every rule
 * belongs to `V13__create_articles.sql`: details are all-or-none, measurements are positive, a
 * subcategory requires its category, an active mug has a price, its details, and a category, and
 * `position` is positive and unique with the unique rule deferred to COMMIT.
 *
 * The four references are plain columns rather than Exposed `reference`s. Three of them cannot be
 * one: the subcategory is referenced *together with* its category, and supplier and price belong to
 * other modules whose tables this module does not map.
 */
internal object ArticleMugs : Table("article_mugs") {
    val id = long("id")
    val position = integer("position")
    val name = varchar("name", length = 255)
    val descriptionShort = varchar("description_short", length = 1000)
    val descriptionLong = varchar("description_long", length = 5000)
    val active = bool("active")
    val categoryId = long("category_id").nullable()
    val subcategoryId = long("subcategory_id").nullable()
    val supplierId = long("supplier_id").nullable()
    val supplierArticleName = varchar("supplier_article_name", length = 255).nullable()
    val supplierArticleNumber = varchar("supplier_article_number", length = 255).nullable()
    val priceId = long("price_id").nullable()
    val heightMm = integer("height_mm").nullable()
    val diameterMm = integer("diameter_mm").nullable()
    val printTemplateWidthMm = integer("print_template_width_mm").nullable()
    val printTemplateHeightMm = integer("print_template_height_mm").nullable()
    val fillingQuantity = varchar("filling_quantity", length = 255).nullable()
    val dishwasherSafe = bool("dishwasher_safe").nullable()
    val documentFormatWidthMm = integer("document_format_width_mm").nullable()
    val documentFormatHeightMm = integer("document_format_height_mm").nullable()
    val documentFormatMarginBottomMm = integer("document_format_margin_bottom_mm").nullable()

    override val primaryKey = PrimaryKey(id)
}

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

/**
 * The details of a stored mug, or `null` when it has none. `height_mm` represents the whole block:
 * the all-or-none CHECK keeps the required measurements together, so one of them is enough to know
 * whether details exist.
 *
 * It sits next to the table whose columns it reads, because both mug repositories build the same
 * value out of the same nine columns.
 */
internal fun ResultRow.toMugDetails(): MugDetails? {
    val heightMm = this[ArticleMugs.heightMm] ?: return null
    return MugDetails(
        heightMm = heightMm,
        diameterMm = checkNotNull(this[ArticleMugs.diameterMm]),
        printTemplateWidthMm = checkNotNull(this[ArticleMugs.printTemplateWidthMm]),
        printTemplateHeightMm = checkNotNull(this[ArticleMugs.printTemplateHeightMm]),
        fillingQuantity = this[ArticleMugs.fillingQuantity],
        dishwasherSafe = checkNotNull(this[ArticleMugs.dishwasherSafe]),
        documentFormatWidthMm = this[ArticleMugs.documentFormatWidthMm],
        documentFormatHeightMm = this[ArticleMugs.documentFormatHeightMm],
        documentFormatMarginBottomMm = this[ArticleMugs.documentFormatMarginBottomMm],
    )
}

/** Moves every mug behind [position] one place forward, so the sequence stays dense. */
internal fun closeMugPositionGapInTransaction(position: Int) {
    ArticleMugs.select(ArticleMugs.id, ArticleMugs.position)
        .where { ArticleMugs.position greater position }
        .orderBy(ArticleMugs.position to SortOrder.ASC)
        .map { row -> row[ArticleMugs.id] to row[ArticleMugs.position] }
        .forEach { (id, taken) ->
            ArticleMugs.update({ ArticleMugs.id eq id }) { statement ->
                statement[ArticleMugs.position] = taken - 1
            }
        }
}
