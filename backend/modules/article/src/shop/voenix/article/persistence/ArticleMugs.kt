package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.mug.MugArticleListItem
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
    const val ARTICLE_TYPE: String = "MUG"

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

/**
 * The last taken position of this article type, or `0` when no mug exists yet. Only meaningful
 * under the ordering anchor of the type, which every caller holds.
 */
internal fun maxMugPositionInTransaction(): Int {
    val maximum = ArticleMugs.position.max()
    return ArticleMugs.select(maximum).single()[maximum] ?: 0
}

/**
 * Whether the stored positions of this order are `1..n` without a gap.
 *
 * Only a writer that ignored the type anchor — a manual database fix, for instance — can leave a
 * gap, and the reorder is the one write that would spread it: it rewrites positions from a list, so
 * a broken sequence would come back repaired and every row a client sees would have moved. The
 * check is what the legacy backend did before its rewrite, and it keeps that answer.
 */
internal fun List<MugArticleListItem>.isDense(): Boolean =
    withIndex().all { (index, mug) -> mug.position == index + 1 }

/**
 * Numbers [ordered] from 1 without gaps and returns the result. Only rows whose position really
 * changes are written, so moving two neighbours costs two statements instead of one per mug.
 */
internal fun rewriteDenseMugPositionsInTransaction(
    ordered: List<MugArticleListItem>
): List<MugArticleListItem> = ordered.mapIndexed { index, mug ->
    val position = index + 1
    if (mug.position != position) {
        ArticleMugs.update({ ArticleMugs.id eq mug.id }) { statement ->
            statement[ArticleMugs.position] = position
        }
    }
    mug.copy(position = position)
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
