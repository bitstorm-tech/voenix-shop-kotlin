package shop.voenix.production

import java.nio.file.Path

/**
 * One logical order line for production: quantity, the owning supplier, the generated production
 * image, and optional mug-layout overrides in millimetres.
 *
 * [quantity] physical copies of this line become individual item pages. [imagePath] points at the
 * generated production image; a missing or unreadable image is a typed, retryable generation
 * failure — an item page is never rendered blank. The five measurement overrides mirror the mug
 * detail data: [documentFormatWidthMm]/[documentFormatHeightMm] replace the default page size,
 * [printTemplateWidthMm]/[printTemplateHeightMm] confine the print area, and
 * [documentFormatMarginBottomMm] lifts the image off the bottom edge.
 */
public data class ProductionItem(
    public val supplierId: Long,
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
