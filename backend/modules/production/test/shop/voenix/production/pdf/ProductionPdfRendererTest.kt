package shop.voenix.production.pdf

import java.awt.Color
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.apache.pdfbox.rendering.PDFRenderer
import shop.voenix.production.ProductionPdfError

internal class ProductionPdfRendererTest {
    private val tempDir = newTempDirectory()
    private val renderer = ProductionPdfRenderer()

    @AfterTest
    fun cleanUp() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `a rendered document is a PDF with the stable name, media type, and digest`() {
        val image = writePng(tempDir, "item.png")
        val order = productionOrder(orderId = 7, items = listOf(productionItem(imagePath = image)))

        val result = renderer.render(order, supplierId = 1)

        val pdf = assertIs<ProductionPdfRenderResult.Rendered>(result).pdf
        assertEquals("ORD-7.pdf", pdf.fileName)
        assertEquals("application/pdf", pdf.mediaType)
        assertTrue(pdf.bytes.decodeToString(0, 5).startsWith("%PDF-"), "PDF magic bytes expected")
        assertEquals(sha256Hex(pdf.bytes), pdf.sha256)
    }

    @Test
    fun `every physical quantity becomes one item page after the address page`() {
        val image = writePng(tempDir, "item.png")
        val order =
            productionOrder(
                orderId = 9,
                items =
                    listOf(
                        productionItem(articleName = "Alpha", quantity = 2, imagePath = image),
                        productionItem(articleName = "Beta", quantity = 1, imagePath = image),
                    ),
            )

        val pdf = assertIs<ProductionPdfRenderResult.Rendered>(renderer.render(order, 1)).pdf

        loadPdf(pdf.bytes).use { document ->
            assertEquals(4, document.numberOfPages)
            assertEquals("ORD-9 (1/3)", document.orderLabelText(2))
            assertEquals("ORD-9 (2/3)", document.orderLabelText(3))
            assertEquals("ORD-9 (3/3)", document.orderLabelText(4))
        }
    }

    @Test
    fun `pages measure 239 by 99 millimetres unless an item overrides the format`() {
        val image = writePng(tempDir, "item.png")
        val order =
            productionOrder(
                items =
                    listOf(
                        productionItem(imagePath = image),
                        productionItem(
                            imagePath = image,
                            documentFormatWidthMm = 210.0,
                            documentFormatHeightMm = 120.0,
                        ),
                    )
            )

        val pdf = assertIs<ProductionPdfRenderResult.Rendered>(renderer.render(order, 1)).pdf

        loadPdf(pdf.bytes).use { document ->
            assertEquals(239.0, document.pageWidthMm(0), 0.05)
            assertEquals(99.0, document.pageHeightMm(0), 0.05)
            assertEquals(239.0, document.pageWidthMm(1), 0.05)
            assertEquals(99.0, document.pageHeightMm(1), 0.05)
            assertEquals(210.0, document.pageWidthMm(2), 0.05)
            assertEquals(120.0, document.pageHeightMm(2), 0.05)
        }
    }

    @Test
    fun `the address page shows the shipping address and the rotated order label`() {
        val image = writePng(tempDir, "item.png")
        val order = productionOrder(orderId = 11, items = listOf(productionItem(imagePath = image)))

        val pdf = assertIs<ProductionPdfRenderResult.Rendered>(renderer.render(order, 1)).pdf

        loadPdf(pdf.bytes).use { document ->
            val address = document.horizontalText(1)
            assertContains(address, "Erika Mustermann")
            assertContains(address, "Heidestraße 17")
            assertContains(address, "51147 Köln")
            assertContains(address, "Deutschland")
            assertEquals("ORD-11", document.orderLabelText(1))
            val directions = document.textDirections(1)
            assertContains(directions, 90f, "order label should read bottom-to-top")
            assertContains(directions, 0f, "address lines should stay horizontal")
        }
    }

    @Test
    fun `item pages rotate the order label and the article information in opposite directions`() {
        val image = writePng(tempDir, "item.png")
        val order =
            productionOrder(
                items =
                    listOf(
                        productionItem(
                            articleName = "Zaubertasse",
                            supplierArticleNumber = "SUP-77",
                            variantName = "Schwarz",
                            imagePath = image,
                        )
                    )
            )

        val pdf = assertIs<ProductionPdfRenderResult.Rendered>(renderer.render(order, 1)).pdf

        loadPdf(pdf.bytes).use { document ->
            assertEquals("Zaubertasse | SUP-77 | Schwarz", document.itemInfoText(2))
            assertEquals(setOf(90f, 270f), document.textDirections(2))
        }
    }

    @Test
    fun `a blank supplier article number is left out of the item text`() {
        val image = writePng(tempDir, "item.png")
        for (supplierNumber in listOf(null, "")) {
            val order =
                productionOrder(
                    items =
                        listOf(
                            productionItem(
                                articleName = "Tasse",
                                supplierArticleNumber = supplierNumber,
                                variantName = "Blau",
                                imagePath = image,
                            )
                        )
                )

            val pdf = assertIs<ProductionPdfRenderResult.Rendered>(renderer.render(order, 1)).pdf

            loadPdf(pdf.bytes).use { document ->
                assertEquals("Tasse | Blau", document.itemInfoText(2))
            }
        }
    }

    @Test
    fun `with a print template the image sits on the bottom margin inside the template width`() {
        val image = writePng(tempDir, "item.png", color = Color.RED, width = 200, height = 100)
        val order =
            productionOrder(
                items =
                    listOf(
                        productionItem(
                            imagePath = image,
                            printTemplateWidthMm = 100.0,
                            printTemplateHeightMm = 50.0,
                            documentFormatMarginBottomMm = 10.0,
                        )
                    )
            )

        val pdf = assertIs<ProductionPdfRenderResult.Rendered>(renderer.render(order, 1)).pdf

        loadPdf(pdf.bytes).use { document ->
            val rendered = PDFRenderer(document).renderImageWithDPI(1, RENDER_DPI)
            // Page 239 x 99 mm; the 200x100 px image fits the 100 mm template width, so it
            // covers 100 x 50 mm, horizontally centered, bottom edge 10 mm above the page edge.
            val centerX = rendered.width / 2
            assertTrue(isRed(rendered.getRGB(centerX, yFromBottomMm(rendered.height, 12.0))))
            assertTrue(isRed(rendered.getRGB(centerX, yFromBottomMm(rendered.height, 58.0))))
            assertFalse(isRed(rendered.getRGB(centerX, yFromBottomMm(rendered.height, 5.0))))
            assertFalse(isRed(rendered.getRGB(centerX, yFromBottomMm(rendered.height, 65.0))))
            val insideLeftEdge = centerX - pxOfMm(48.0)
            val outsideLeftEdge = centerX - pxOfMm(55.0)
            assertTrue(isRed(rendered.getRGB(insideLeftEdge, yFromBottomMm(rendered.height, 30.0))))
            assertFalse(
                isRed(rendered.getRGB(outsideLeftEdge, yFromBottomMm(rendered.height, 30.0)))
            )
        }
    }

    @Test
    fun `without a print template the image is centered between the side columns`() {
        val image = writePng(tempDir, "item.png", color = Color.RED, width = 100, height = 100)
        val order = productionOrder(items = listOf(productionItem(imagePath = image)))

        val pdf = assertIs<ProductionPdfRenderResult.Rendered>(renderer.render(order, 1)).pdf

        loadPdf(pdf.bytes).use { document ->
            val rendered = PDFRenderer(document).renderImageWithDPI(1, RENDER_DPI)
            // The square image fits the 99 mm page height, so it covers 99 x 99 mm around the
            // vertical page center.
            val centerX = rendered.width / 2
            val centerY = rendered.height / 2
            assertTrue(isRed(rendered.getRGB(centerX, centerY)))
            assertTrue(isRed(rendered.getRGB(centerX - pxOfMm(45.0), centerY)))
            assertTrue(isRed(rendered.getRGB(centerX + pxOfMm(45.0), centerY)))
            assertFalse(isRed(rendered.getRGB(centerX - pxOfMm(60.0), centerY)))
            assertFalse(isRed(rendered.getRGB(centerX + pxOfMm(60.0), centerY)))
        }
    }

    @Test
    fun `very long item text still renders instead of failing`() {
        val image = writePng(tempDir, "item.png")
        val order =
            productionOrder(
                items =
                    listOf(
                        productionItem(
                            articleName = "Extralange Tasse ".repeat(40),
                            imagePath = image,
                        )
                    )
            )

        assertIs<ProductionPdfRenderResult.Rendered>(renderer.render(order, 1))
    }

    @Test
    fun `a supplier without items in the order is an invalid render request`() {
        val image = writePng(tempDir, "item.png")
        val order =
            productionOrder(items = listOf(productionItem(supplierId = 1, imagePath = image)))

        val result = renderer.render(order, supplierId = 99)

        assertEquals(
            ProductionPdfError.INVALID_SOURCE,
            assertIs<ProductionPdfRenderResult.Failed>(result).error,
        )
    }

    private fun isRed(rgb: Int): Boolean {
        val color = Color(rgb)
        return color.red > 200 && color.green < 80 && color.blue < 80
    }

    private fun pxOfMm(mm: Double): Int = (mm / 25.4 * RENDER_DPI).toInt()

    private fun yFromBottomMm(imageHeightPx: Int, mm: Double): Int = imageHeightPx - 1 - pxOfMm(mm)

    private companion object {
        const val RENDER_DPI = 96f
    }
}
