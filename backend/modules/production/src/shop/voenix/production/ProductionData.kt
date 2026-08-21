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
 *
 * [customerEmail] and [customerPhone] are the customer's contact data as the order stored it, and
 * they are here for one channel only: the print-on-demand partner a t-shirt is ordered from
 * requires both on the order it receives. Nothing that goes to a *supplier* carries them — the
 * fulfillment view a supplier reads is a separate, deliberately minimal type (`FulfillmentOrder`)
 * and stays that way. [customerPhone] is nullable because a mug-only order needs no number; the
 * channel that does need one refuses the submission when it is missing rather than inventing one.
 */
public data class ProductionData(
    public val orderId: Long,
    public val orderDate: LocalDate,
    public val customerEmail: String,
    public val customerPhone: String?,
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
 *
 * [spodProduct] is the other channel's answer to the same question the five measurements answer for
 * the PDF: *what exactly is to be produced?* It carries the three ids the print-on-demand partner
 * identifies one printable product by, and it is `null` for every item that is not produced that
 * way — a mug answers `null` here and a t-shirt answers `null` for the five measurements. Unlike
 * everything else on this item it is **not** a snapshot: the source resolves it from current master
 * data on every load, exactly like [supplierId], so a corrected id reaches an order that is still
 * waiting to be submitted. That is why the submitting adapter compares [variantName] — which *is*
 * the snapshot — against what it reads today and refuses when the two disagree.
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
    public val spodProduct: SpodProductRef? = null,
)

/**
 * The three ids the print-on-demand partner identifies one printable product by: which product type
 * it is, which appearance (the colour, in that partner's vocabulary) it has, and which size.
 *
 * It is one value rather than three fields of [ProductionItem], because the three ids are only ever
 * meaningful together: an appearance id without its product type names nothing.
 *
 * The article module declares a structurally identical value of its own, and that repetition is
 * deliberate. Production owns the port its source fills in, and importing the article module's type
 * here would make every consumer of this port depend on the catalog — a dependency production does
 * not have and must not gain for three numbers. The order module, which depends on both, is the one
 * place that translates between them.
 */
public data class SpodProductRef(
    public val productTypeId: Long,
    public val appearanceId: Long,
    public val sizeId: Long,
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
