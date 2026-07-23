package shop.voenix.production.pdf

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionItem
import shop.voenix.production.ProductionPdfError
import shop.voenix.production.productionOrderLabel
import shop.voenix.production.productionPdfFileName

/**
 * Renders the physical production PDF for one supplier's share of an order.
 *
 * The layout recreates the legacy document: a 239 mm x 99 mm address page with a rotated order
 * label, then one item page per physical quantity with a stable 1-based index within the supplier
 * job, optional mug-layout overrides, the generated image, and rotated article/variant text. A
 * missing or unreadable production image is a typed, retryable failure — an item page is never
 * rendered blank. No PDFBox type leaves this package.
 */
internal class ProductionPdfRenderer {
    fun render(order: ProductionData, supplierId: Long): ProductionPdfRenderResult {
        val items = order.items.filter { it.supplierId == supplierId }
        val validationError = validationError(items)
        if (validationError != null) return ProductionPdfRenderResult.Failed(validationError)
        return try {
            ProductionPdfRenderResult.Rendered(renderDocument(order, items))
        } catch (exception: UnreadableImageException) {
            logger.warn("Unreadable production image for order {}", order.orderId, exception)
            ProductionPdfRenderResult.Failed(ProductionPdfError.UNREADABLE_IMAGE)
        } catch (exception: Exception) {
            logger.error("Production PDF rendering failed for order {}", order.orderId, exception)
            ProductionPdfRenderResult.Failed(ProductionPdfError.RENDER_FAILURE)
        }
    }

    private fun validationError(items: List<ProductionItem>): ProductionPdfError? =
        when {
            items.isEmpty() -> ProductionPdfError.INVALID_SOURCE
            else -> items.firstNotNullOfOrNull(::itemError)
        }

    private fun itemError(item: ProductionItem): ProductionPdfError? {
        val measurements =
            listOf(
                item.printTemplateWidthMm,
                item.printTemplateHeightMm,
                item.documentFormatWidthMm,
                item.documentFormatHeightMm,
            )
        val margin = item.documentFormatMarginBottomMm
        val pageWidthMm = item.documentFormatWidthMm ?: ProductionPdfLayout.PAGE_WIDTH_MM
        val invalidSource =
            item.quantity < 1 ||
                measurements.any { it != null && it <= 0.0 } ||
                (margin != null && margin < 0.0) ||
                pageWidthMm <= 2 * ProductionPdfLayout.SIDE_COLUMN_WIDTH_MM
        val imagePath = item.imagePath
        return when {
            invalidSource -> ProductionPdfError.INVALID_SOURCE
            imagePath == null || !Files.isRegularFile(imagePath) -> ProductionPdfError.MISSING_IMAGE
            else -> null
        }
    }

    private fun renderDocument(order: ProductionData, items: List<ProductionItem>): ProductionPdf {
        val expanded = items.flatMap { item -> List(item.quantity) { item } }
        val bytes =
            PDDocument().use { document ->
                val font = loadFont(document)
                val images = loadImages(document, expanded)
                composeAddressPage(document, font, order)
                expanded.forEachIndexed { index, item ->
                    composeItemPage(
                        document = document,
                        font = font,
                        item = item,
                        orderLabel =
                            "${productionOrderLabel(order.orderId)} " +
                                "(${index + 1}/${expanded.size})",
                        image = images.getValue(checkNotNull(item.imagePath)),
                    )
                }
                ByteArrayOutputStream().also(document::save).toByteArray()
            }
        return ProductionPdf(
            fileName = productionPdfFileName(order.orderId),
            mediaType = MEDIA_TYPE,
            bytes = bytes,
            sha256 = sha256Hex(bytes),
        )
    }

    private fun loadImages(
        document: PDDocument,
        items: List<ProductionItem>,
    ): Map<Path, PDImageXObject> =
        items.mapNotNull(ProductionItem::imagePath).distinct().associateWith { path ->
            try {
                PDImageXObject.createFromFileByContent(path.toFile(), document)
            } catch (exception: IOException) {
                throw UnreadableImageException(path, exception)
            } catch (exception: IllegalArgumentException) {
                // PDFBox reports unsupported image content as an illegal argument.
                throw UnreadableImageException(path, exception)
            }
        }

    private fun composeAddressPage(document: PDDocument, font: PDFont, order: ProductionData) {
        val pageWidth = ProductionPdfLayout.mmToPt(ProductionPdfLayout.PAGE_WIDTH_MM)
        val pageHeight = ProductionPdfLayout.mmToPt(ProductionPdfLayout.PAGE_HEIGHT_MM)
        val page = PDPage(PDRectangle(pageWidth, pageHeight))
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            val canvas = PdfPageCanvas(content, font, pageWidth, pageHeight)
            canvas.rotatedLeftColumnText(
                productionOrderLabel(order.orderId),
                ORDER_LABEL_FONT_SIZE,
            )
            canvas.centeredTextBlock(
                listOf(
                    PdfPageCanvas.TextLine(
                        text = "${order.shippingFirstName} ${order.shippingLastName}",
                        size = NAME_FONT_SIZE,
                        bold = true,
                    ),
                    PdfPageCanvas.TextLine(
                        text = "${order.shippingStreet} ${order.shippingHouseNumber}",
                        size = ADDRESS_FONT_SIZE,
                        bold = false,
                    ),
                    PdfPageCanvas.TextLine(
                        text = "${order.shippingPostalCode} ${order.shippingCity}",
                        size = ADDRESS_FONT_SIZE,
                        bold = false,
                    ),
                    PdfPageCanvas.TextLine(
                        text = order.shippingCountry,
                        size = ADDRESS_FONT_SIZE,
                        bold = false,
                    ),
                )
            )
        }
    }

    private fun composeItemPage(
        document: PDDocument,
        font: PDFont,
        item: ProductionItem,
        orderLabel: String,
        image: PDImageXObject,
    ) {
        val pageWidthMm = item.documentFormatWidthMm ?: ProductionPdfLayout.PAGE_WIDTH_MM
        val pageHeightMm = item.documentFormatHeightMm ?: ProductionPdfLayout.PAGE_HEIGHT_MM
        val pageWidth = ProductionPdfLayout.mmToPt(pageWidthMm)
        val pageHeight = ProductionPdfLayout.mmToPt(pageHeightMm)
        val page = PDPage(PDRectangle(pageWidth, pageHeight))
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            val canvas = PdfPageCanvas(content, font, pageWidth, pageHeight)
            canvas.rotatedLeftColumnText(orderLabel, ORDER_LABEL_FONT_SIZE)
            drawItemImage(content, image, item, pageWidthMm, pageHeightMm)
            val parts = buildList {
                add(item.articleName)
                val supplierNumber = item.supplierArticleNumber
                if (!supplierNumber.isNullOrEmpty()) add(supplierNumber)
                add(item.variantName)
            }
            canvas.rotatedRightColumnText(parts.joinToString(" | "), ITEM_INFO_FONT_SIZE)
        }
    }

    /**
     * Places the image like the legacy layout: with a print template the image is confined to the
     * template width, sits on the bottom margin, and is centered horizontally; without one it is
     * centered in the full area between the side columns. In both cases the image scales
     * aspect-preserving to fit its box.
     */
    private fun drawItemImage(
        content: PDPageContentStream,
        image: PDImageXObject,
        item: ProductionItem,
        pageWidthMm: Double,
        pageHeightMm: Double,
    ) {
        val printWidthMm = item.printTemplateWidthMm
        val printHeightMm = item.printTemplateHeightMm
        val boxLeft: Float
        val boxRight: Float
        val boxBottom: Float
        val alignBottom = printWidthMm != null && printHeightMm != null
        if (alignBottom) {
            val availableMm = pageWidthMm - 2 * ProductionPdfLayout.SIDE_COLUMN_WIDTH_MM
            val horizontalPaddingMm = max(0.0, (availableMm - checkNotNull(printWidthMm)) / 2)
            boxLeft =
                ProductionPdfLayout.mmToPt(
                    ProductionPdfLayout.SIDE_COLUMN_WIDTH_MM + horizontalPaddingMm
                )
            boxRight =
                ProductionPdfLayout.mmToPt(
                    pageWidthMm - ProductionPdfLayout.SIDE_COLUMN_WIDTH_MM - horizontalPaddingMm
                )
            boxBottom = ProductionPdfLayout.mmToPt(item.documentFormatMarginBottomMm ?: 0.0)
        } else {
            boxLeft = ProductionPdfLayout.SIDE_COLUMN_WIDTH_PT
            boxRight =
                ProductionPdfLayout.mmToPt(pageWidthMm) - ProductionPdfLayout.SIDE_COLUMN_WIDTH_PT
            boxBottom = 0f
        }
        val boxTop = ProductionPdfLayout.mmToPt(pageHeightMm)
        val boxWidth = boxRight - boxLeft
        val boxHeight = boxTop - boxBottom
        val scale = min(boxWidth / image.width, boxHeight / image.height)
        val drawWidth = image.width * scale
        val drawHeight = image.height * scale
        val x = boxLeft + (boxWidth - drawWidth) / 2
        val y = if (alignBottom) boxBottom else boxBottom + (boxHeight - drawHeight) / 2
        content.drawImage(image, x, y, drawWidth, drawHeight)
    }

    private fun loadFont(document: PDDocument): PDFont {
        val stream =
            checkNotNull(javaClass.getResourceAsStream(FONT_RESOURCE)) {
                "Bundled font $FONT_RESOURCE is missing from the classpath"
            }
        return stream.use { PDType0Font.load(document, it) }
    }

    private class UnreadableImageException(path: Path, cause: Exception) :
        RuntimeException("Production image $path cannot be decoded", cause)

    private companion object {
        const val MEDIA_TYPE = "application/pdf"
        const val ORDER_LABEL_FONT_SIZE = 9f
        const val NAME_FONT_SIZE = 12f
        const val ADDRESS_FONT_SIZE = 11f
        const val ITEM_INFO_FONT_SIZE = 8f

        /** Liberation Sans ships inside the PDFBox jar and covers extended Latin plus Cyrillic. */
        const val FONT_RESOURCE = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"

        val logger: Logger = LoggerFactory.getLogger(ProductionPdfRenderer::class.java)
    }
}
