package shop.voenix.article.persistence

import org.jetbrains.exposed.v1.core.Table

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
