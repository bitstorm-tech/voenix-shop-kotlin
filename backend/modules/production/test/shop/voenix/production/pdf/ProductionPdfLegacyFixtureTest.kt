package shop.voenix.production.pdf

import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import shop.voenix.production.ProductionData

/**
 * Rendered-image comparison against reference PDFs from the legacy system — deliberately not a
 * byte-equality test.
 *
 * Drop legacy PDFs into `testResources/legacy-production-pdfs/` and register the matching
 * [ProductionData] under the file name (without extension) in [fixtureCases]. Until fixtures are
 * delivered, this test reports itself as skipped work, not as green coverage of nothing.
 */
internal class ProductionPdfLegacyFixtureTest {
    /** One entry per delivered fixture: the exact source data the legacy PDF was generated from. */
    private val fixtureCases: Map<String, FixtureCase> = emptyMap()

    @Test
    fun `rendered pages match the legacy reference PDFs`() {
        val fixtures = legacyFixtureFiles()
        if (fixtures.isEmpty()) {
            println(
                "SKIPPED: no legacy reference PDFs in testResources/legacy-production-pdfs yet — " +
                    "rendered-image comparison waits for the fixtures."
            )
            return
        }
        for (fixture in fixtures) {
            val case =
                fixtureCases[fixture.nameWithoutExtension]
                    ?: fail(
                        "No source data registered for legacy fixture ${fixture.name} — " +
                            "add a fixtureCases entry so the comparison can render the same order."
                    )
            val rendered =
                assertIs<ProductionPdfRenderResult.Rendered>(
                        ProductionPdfRenderer().render(case.data, case.supplierId),
                        "fixture ${fixture.name}",
                    )
                    .pdf
            Loader.loadPDF(fixture).use { legacy ->
                loadPdf(rendered.bytes).use { ours -> assertSimilar(fixture.name, legacy, ours) }
            }
        }
    }

    private fun assertSimilar(name: String, legacy: PDDocument, ours: PDDocument) {
        assertEquals(legacy.numberOfPages, ours.numberOfPages, "$name: page count")
        for (pageIndex in 0 until legacy.numberOfPages) {
            assertEquals(
                legacy.getPage(pageIndex).mediaBox.width * MM_PER_POINT,
                ours.getPage(pageIndex).mediaBox.width * MM_PER_POINT,
                0.5,
                "$name page ${pageIndex + 1}: width",
            )
            assertEquals(
                legacy.getPage(pageIndex).mediaBox.height * MM_PER_POINT,
                ours.getPage(pageIndex).mediaBox.height * MM_PER_POINT,
                0.5,
                "$name page ${pageIndex + 1}: height",
            )
            val difference =
                meanChannelDifference(
                    PDFRenderer(legacy).renderImageWithDPI(pageIndex, COMPARISON_DPI),
                    PDFRenderer(ours).renderImageWithDPI(pageIndex, COMPARISON_DPI),
                )
            assertTrue(
                difference <= MAX_MEAN_CHANNEL_DIFFERENCE,
                "$name page ${pageIndex + 1}: rendered images differ too much " +
                    "(mean channel difference $difference)",
            )
        }
    }

    /** Mean absolute RGB difference per channel, normalized to 0..1, over the shared area. */
    private fun meanChannelDifference(left: BufferedImage, right: BufferedImage): Double {
        val width = minOf(left.width, right.width)
        val height = minOf(left.height, right.height)
        var total = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val a = left.getRGB(x, y)
                val b = right.getRGB(x, y)
                total += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)).toLong()
                total += abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)).toLong()
                total += abs((a and 0xFF) - (b and 0xFF)).toLong()
            }
        }
        return total.toDouble() / (width.toLong() * height * 3) / 255.0
    }

    private fun legacyFixtureFiles(): List<File> {
        val resource = javaClass.getResource(FIXTURE_RESOURCE_DIRECTORY) ?: return emptyList()
        val directory = File(resource.toURI())
        return directory
            .listFiles { file -> file.extension.equals("pdf", ignoreCase = true) }
            .orEmpty()
            .sortedBy(File::getName)
    }

    private data class FixtureCase(val data: ProductionData, val supplierId: Long)

    private companion object {
        const val FIXTURE_RESOURCE_DIRECTORY = "/legacy-production-pdfs"
        const val COMPARISON_DPI = 96f
        const val MAX_MEAN_CHANNEL_DIFFERENCE = 0.06
    }
}
