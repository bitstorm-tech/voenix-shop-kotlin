package shop.voenix.order

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * One ordered line, in the order the customer put it together ([position]).
 *
 * [articleId] and [variantId] are plain numbers on purpose: they carry no catalog foreign key, so a
 * deleted article cannot take an order line with it. Everything production and the confirmation
 * mail need is snapshotted next to them — the names, the prices, the supplier article number, and
 * the five layout measurements in millimetres.
 */
internal object OrderItems : LongIdTable("order_items") {
    val orderId = long("order_id")
    val position = integer("position")
    val articleId = long("article_id")
    val variantId = long("variant_id")
    val articleName = varchar("article_name", 255)
    val variantName = varchar("variant_name", 255)
    val supplierArticleNumber = varchar("supplier_article_number", 255).nullable()
    val printTemplateWidthMm = integer("print_template_width_mm").nullable()
    val printTemplateHeightMm = integer("print_template_height_mm").nullable()
    val documentFormatWidthMm = integer("document_format_width_mm").nullable()
    val documentFormatHeightMm = integer("document_format_height_mm").nullable()
    val documentFormatMarginBottomMm = integer("document_format_margin_bottom_mm").nullable()
    val quantity = integer("quantity")
    val priceCents = integer("price_cents")
    val promptPriceCents = integer("prompt_price_cents")
    val promptId = long("prompt_id").nullable()
    val printImageId = long("print_image_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}
