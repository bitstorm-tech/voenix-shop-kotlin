package shop.voenix.production.pdf

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionItem

internal const val MM_PER_POINT = 25.4 / 72.0

internal fun productionOrder(
    orderId: Long = 42,
    items: List<ProductionItem>,
    firstName: String = "Erika",
    lastName: String = "Mustermann",
    street: String = "Heidestraße",
    houseNumber: String = "17",
    postalCode: String = "51147",
    city: String = "Köln",
    country: String = "Deutschland",
): ProductionData =
    ProductionData(
        orderId = orderId,
        shippingFirstName = firstName,
        shippingLastName = lastName,
        shippingStreet = street,
        shippingHouseNumber = houseNumber,
        shippingPostalCode = postalCode,
        shippingCity = city,
        shippingCountry = country,
        items = items,
    )

internal fun productionItem(
    supplierId: Long? = 1,
    articleName: String = "Zaubertasse",
    supplierArticleNumber: String? = "SUP-001",
    variantName: String = "Weiß",
    quantity: Int = 1,
    imagePath: Path?,
    printTemplateWidthMm: Double? = null,
    printTemplateHeightMm: Double? = null,
    documentFormatWidthMm: Double? = null,
    documentFormatHeightMm: Double? = null,
    documentFormatMarginBottomMm: Double? = null,
): ProductionItem =
    ProductionItem(
        supplierId = supplierId,
        articleName = articleName,
        supplierArticleNumber = supplierArticleNumber,
        variantName = variantName,
        quantity = quantity,
        imagePath = imagePath,
        printTemplateWidthMm = printTemplateWidthMm,
        printTemplateHeightMm = printTemplateHeightMm,
        documentFormatWidthMm = documentFormatWidthMm,
        documentFormatHeightMm = documentFormatHeightMm,
        documentFormatMarginBottomMm = documentFormatMarginBottomMm,
    )

/** Writes a single-color PNG so image bytes never need to be checked into the repository. */
internal fun writePng(
    directory: Path,
    name: String,
    color: Color = Color.RED,
    width: Int = 200,
    height: Int = 100,
): Path {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = color
    graphics.fillRect(0, 0, width, height)
    graphics.dispose()
    val path = directory.resolve(name)
    ImageIO.write(image, "png", path.toFile())
    return path
}

internal fun loadPdf(bytes: ByteArray): PDDocument = Loader.loadPDF(bytes)

/**
 * Collects the page text separated by glyph direction (degrees), in drawing order. Rotated text
 * would otherwise be shredded across lines by the plain text stripper.
 */
internal fun PDDocument.textByDirection(pageNumber: Int): Map<Float, String> {
    val collected = linkedMapOf<Float, StringBuilder>()
    val stripper =
        object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                collected.getOrPut(text.dir) { StringBuilder() }.append(text.unicode)
                super.processTextPosition(text)
            }
        }
    stripper.startPage = pageNumber
    stripper.endPage = pageNumber
    stripper.getText(this)
    return collected.mapValues { it.value.toString() }
}

/** The horizontal text of one page — on the address page, the address block. */
internal fun PDDocument.horizontalText(pageNumber: Int): String =
    textByDirection(pageNumber)[0f].orEmpty()

/** The bottom-to-top order label in the left side column. */
internal fun PDDocument.orderLabelText(pageNumber: Int): String =
    textByDirection(pageNumber)[90f].orEmpty()

/** The top-to-bottom article/variant text in the right side column. */
internal fun PDDocument.itemInfoText(pageNumber: Int): String =
    textByDirection(pageNumber)[270f].orEmpty()

internal fun PDDocument.pageWidthMm(pageIndex: Int): Double =
    getPage(pageIndex).mediaBox.width * MM_PER_POINT

internal fun PDDocument.pageHeightMm(pageIndex: Int): Double =
    getPage(pageIndex).mediaBox.height * MM_PER_POINT

/** Collects the text direction (degrees) of every glyph on one page. */
internal fun PDDocument.textDirections(pageNumber: Int): Set<Float> {
    val directions = mutableSetOf<Float>()
    val stripper =
        object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                directions += text.dir
                super.processTextPosition(text)
            }
        }
    stripper.startPage = pageNumber
    stripper.endPage = pageNumber
    stripper.getText(this)
    return directions
}

internal fun newTempDirectory(): Path = Files.createTempDirectory("production-pdf-test")
