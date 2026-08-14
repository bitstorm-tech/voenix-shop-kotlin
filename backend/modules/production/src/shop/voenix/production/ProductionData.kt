package shop.voenix.production

import java.nio.file.Path
import java.time.LocalDate

/**
 * Process-only production view of one order: the shipping address printed on every address page
 * plus the order items in their explicit source order.
 *
 * [orderDate] is the customer-facing order date as `Europe/Berlin` calendar date; the source owns
 * the conversion from the stored creation instant. Production renders it unchanged in the producer
 * notification.
 */
public data class ProductionData(
    public val orderId: Long,
    public val orderDate: LocalDate,
    public val shippingFirstName: String,
    public val shippingLastName: String,
    public val shippingStreet: String,
    public val shippingHouseNumber: String,
    public val shippingPostalCode: String,
    public val shippingCity: String,
    public val shippingCountry: String,
    public val items: List<ProductionItem>,
)

/**
 * One logical order line for production: quantity, the owning supplier, the generated production
 * image, and optional mug-layout overrides in millimetres.
 *
 * [supplierId] is `null` when the article's master data assigns no supplier yet. Production never
 * guesses a route: an item without a supplier is a typed, retryable failure (the request stays
 * open, PDF generation reports an invalid source) that heals once an admin assigns the supplier.
 * [quantity] physical copies of this line become individual item pages. [imagePath] points at the
 * generated production image; a missing or unreadable image is a typed, retryable generation
 * failure — an item page is never rendered blank. The five measurement overrides mirror the mug
 * detail data: [documentFormatWidthMm]/[documentFormatHeightMm] replace the default page size,
 * [printTemplateWidthMm]/[printTemplateHeightMm] confine the print area, and
 * [documentFormatMarginBottomMm] lifts the image off the bottom edge.
 */
public data class ProductionItem(
    public val supplierId: Long?,
    public val articleName: String,
    public val supplierArticleNumber: String?,
    public val variantName: String,
    public val quantity: Int,
    public val imagePath: Path?,
    public val printTemplateWidthMm: Double? = null,
    public val printTemplateHeightMm: Double? = null,
    public val documentFormatWidthMm: Double? = null,
    public val documentFormatHeightMm: Double? = null,
    public val documentFormatMarginBottomMm: Double? = null,
)

/**
 * Resolves the immutable order, item, and image inputs Production needs for one order.
 *
 * The order module implements it; standalone module tests use an in-memory source. Returning `null`
 * means the order does not exist for production purposes.
 */
public fun interface ProductionSource {
    public suspend fun load(orderId: Long): ProductionData?
}
