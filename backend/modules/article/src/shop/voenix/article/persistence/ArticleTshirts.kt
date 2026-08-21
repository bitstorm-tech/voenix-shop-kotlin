package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.PrintAspectRatio

/**
 * The `article_tshirts` table created by Flyway: the second article type, and the first use of the
 * one-table-per-type idea the article schema was designed around.
 *
 * It mirrors [ArticleMugs] wherever the two types agree — the id is minted by [ArticleIdentities]
 * and adopted here, `position` is dense and unique per type with the unique rule deferred to
 * COMMIT, an active article needs a price and a category — and differs only where a shirt really is
 * different: it carries no measurements and no supplier article data, and instead the four print
 * frame percentages the preview places the generated design in. Every rule lives in
 * `V20__create_article_tshirts.sql`; Exposed only maps the columns.
 *
 * The frame is mapped as [java.math.BigDecimal] because the column is `numeric(5, 2)`: percentages
 * are entered with two decimals and a binary floating-point type cannot carry them back unchanged.
 */
internal object ArticleTshirts : Table("article_tshirts") {
    val id = long("id")
    val position = integer("position")
    val name = varchar("name", length = 255)
    val descriptionShort = varchar("description_short", length = 1000)
    val descriptionLong = varchar("description_long", length = 5000)
    val active = bool("active")
    val categoryId = long("category_id").nullable()
    val subcategoryId = long("subcategory_id").nullable()
    val supplierId = long("supplier_id").nullable()
    val priceId = long("price_id").nullable()
    val printAspectRatio = text("print_aspect_ratio")
    val sizeChartImageFilename = varchar("size_chart_image_filename", length = 255).nullable()
    val printFrameLeftPct = decimal("print_frame_left_pct", precision = 5, scale = 2)
    val printFrameTopPct = decimal("print_frame_top_pct", precision = 5, scale = 2)
    val printFrameWidthPct = decimal("print_frame_width_pct", precision = 5, scale = 2)
    val printFrameHeightPct = decimal("print_frame_height_pct", precision = 5, scale = 2)

    override val primaryKey = PrimaryKey(id)
}

/**
 * The `article_tshirt_variants` table created by Flyway. Like the shirt itself, a variant adopts
 * the id [ArticleVariantIdentities] minted for it.
 *
 * There is deliberately no `name` column. A shirt variant *is* its colour and its size, so the name
 * is composed by [tshirtVariantName] instead of stored — one spelling for the admin list, the
 * storefront, the catalog capability, and the order line that snapshots it.
 *
 * The three `spod*` ids are the printable product at the print-on-demand partner. They are `NOT
 * NULL` and unique per article, and so is the `(colour, size)` pair: the two unique rules are the
 * same statement seen from the customer's and from the printer's side.
 */
internal object ArticleTshirtVariants : Table("article_tshirt_variants") {
    val id = long("id")
    val articleId = long("article_id")
    val colorName = varchar("color_name", length = 64)
    val colorHex = varchar("color_hex", length = 7)
    val sizeLabel = varchar("size_label", length = 64)
    val spodProductTypeId = long("spod_product_type_id")
    val spodAppearanceId = long("spod_appearance_id")
    val spodSizeId = long("spod_size_id")
    val isDefault = bool("is_default")
    val active = bool("active")
    val exampleImageFilename = varchar("example_image_filename", length = 255).nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * The display name of a t-shirt variant: its colour and its size, in that order.
 *
 * This is the only place the two parts are joined. The admin representation, the storefront read,
 * the exported catalog, and the order line that snapshots the name all call it, so a variant is
 * named the same everywhere and a change to the format is a change to one function.
 */
internal fun tshirtVariantName(
    colorName: String,
    sizeLabel: String,
): String = "$colorName / $sizeLabel"

/** Moves every shirt behind [position] one place forward, so the sequence stays dense. */
internal fun closeTshirtPositionGapInTransaction(position: Int) {
    ArticleTshirts.select(ArticleTshirts.id, ArticleTshirts.position)
        .where { ArticleTshirts.position greater position }
        .orderBy(ArticleTshirts.position to SortOrder.ASC)
        .map { row -> row[ArticleTshirts.id] to row[ArticleTshirts.position] }
        .forEach { (id, taken) ->
            ArticleTshirts.update({ ArticleTshirts.id eq id }) { statement ->
                statement[ArticleTshirts.position] = taken - 1
            }
        }
}

/**
 * The print aspect ratio of a stored t-shirt, read the same way [toPrintAspectRatio] reads a mug's:
 * the column holds the wire value of [PrintAspectRatio] and the CHECK of `article_tshirts` allows
 * exactly the pair the enum has, so a row that does not map is a schema that drifted from the code.
 */
internal fun ResultRow.toTshirtPrintAspectRatio(): PrintAspectRatio {
    val stored = this[ArticleTshirts.printAspectRatio]
    return checkNotNull(PrintAspectRatio.ofWireValue(stored)) {
        "The stored print aspect ratio '$stored' is not one this backend knows"
    }
}
