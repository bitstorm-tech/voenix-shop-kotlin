package shop.voenix.proofs

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.metadata.ImageMetadata
import com.sksamuel.scrimage.webp.WebpWriter
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.Color
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfImageFixtureProofTest {
    @Test
    fun `pdf fixture compares html renderer and pdfbox drawing`() {
        val outputDir = proofOutputDir("pdf")
        val html = Files.readString(fixturePath("invoice-source.xhtml"))
        val htmlSource = outputDir.resolve("source-invoice.html")
        val openHtmlPdf = outputDir.resolve("openhtmltopdf-invoice.pdf").toFile()
        val pdfBoxPdf = outputDir.resolve("pdfbox-invoice.pdf").toFile()

        Files.writeString(htmlSource, html)

        val openHtmlMillis =
            timedMillis {
                openHtmlPdf.outputStream().use { output ->
                    PdfRendererBuilder()
                        .withHtmlContent(html, null)
                        .toStream(output)
                        .run()
                }
            }
        val pdfBoxMillis = timedMillis { writePdfBoxFixture(pdfBoxPdf) }

        val openHtmlProbe = probePdf(openHtmlPdf, outputDir.resolve("openhtmltopdf-invoice.png").toFile())
        val pdfBoxProbe = probePdf(pdfBoxPdf, outputDir.resolve("pdfbox-invoice.png").toFile())

        assertInvoicePdf(openHtmlProbe)
        assertInvoicePdf(pdfBoxProbe)

        Files.writeString(
            outputDir.resolve("pdf-metrics.txt"),
            """
            option,file,bytes,millis,page_png_bytes
            OpenHTMLToPDF,${openHtmlPdf.name},${openHtmlPdf.length()},$openHtmlMillis,${openHtmlProbe.firstPagePngBytes}
            PDFBox,${pdfBoxPdf.name},${pdfBoxPdf.length()},$pdfBoxMillis,${pdfBoxProbe.firstPagePngBytes}
            """.trimIndent(),
        )
    }

    @Test
    fun `image fixture validates webp resize quality and metadata behavior`() {
        val outputDir = proofOutputDir("image")
        val source = outputDir.resolve("source-with-jpeg-comment.jpg").toFile()
        val highWebp = outputDir.resolve("resized-q90.webp").toFile()
        val lowWebp = outputDir.resolve("resized-q40.webp").toFile()

        Files.copy(
            fixturePath("source-with-jpeg-comment.jpg"),
            source.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        val sourceMetadata = metadataText(source)
        assertTrue(sourceMetadata.contains(MetadataMarker), sourceMetadata)

        val sourceImage = ImmutableImage.loader().fromFile(source)
        assertEquals(640, sourceImage.width)
        assertEquals(400, sourceImage.height)

        val resized = sourceImage.scaleToWidth(320)
        assertEquals(320, resized.width)
        assertEquals(200, resized.height)

        val highMillis =
            timedMillis {
                resized.output(WebpWriter.DEFAULT.withQ(90).withM(6), highWebp)
            }
        val lowMillis =
            timedMillis {
                resized.output(WebpWriter.DEFAULT.withQ(40).withM(6), lowWebp)
            }

        assertTrue(isWebp(highWebp), "high-quality output is not a WebP file")
        assertTrue(isWebp(lowWebp), "low-quality output is not a WebP file")
        assertTrue(highWebp.length() > lowWebp.length(), "q90 should be larger than q40")

        val decodedWebp = ImmutableImage.loader().fromFile(highWebp)
        assertEquals(320, decodedWebp.width)
        assertEquals(200, decodedWebp.height)

        val webpMetadata = metadataText(highWebp)
        assertTrue(!webpMetadata.contains(MetadataMarker), "WebP output unexpectedly retained source comment")

        Files.writeString(
            outputDir.resolve("image-metrics.txt"),
            """
            file,bytes,millis,width,height,metadata_marker
            ${source.name},${source.length()},0,640,400,present
            ${highWebp.name},${highWebp.length()},$highMillis,320,200,not-retained
            ${lowWebp.name},${lowWebp.length()},$lowMillis,320,200,not-retained
            """.trimIndent(),
        )
    }

    private fun assertInvoicePdf(probe: PdfProbe) {
        assertEquals(1, probe.pages)
        assertTrue(probe.text.contains("Fixture invoice"), probe.text)
        assertTrue(probe.text.contains("Ceramic mug run"), probe.text)
        assertTrue(probe.text.contains("Total EUR 96.20"), probe.text)
        assertTrue(probe.mediaWidth in 590f..600f, "expected A4 width, got ${probe.mediaWidth}")
        assertTrue(probe.mediaHeight in 835f..850f, "expected A4 height, got ${probe.mediaHeight}")
        assertTrue(probe.firstPagePngBytes > 10_000, "rendered page PNG is unexpectedly tiny")
    }

    private fun writePdfBoxFixture(target: File) {
        Files.deleteIfExists(target.toPath())
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)

            PDPageContentStream(document, page).use { content ->
                content.setNonStrokingColor(Color(247, 249, 252))
                content.addRect(0f, 0f, page.mediaBox.width, page.mediaBox.height)
                content.fill()

                content.setNonStrokingColor(Color(35, 94, 173))
                content.addRect(48f, 690f, 498f, 86f)
                content.fill()

                content.beginText()
                content.setNonStrokingColor(Color.WHITE)
                content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 22f)
                content.newLineAtOffset(68f, 742f)
                content.showText("Fixture invoice")
                content.endText()

                content.beginText()
                content.setNonStrokingColor(Color(40, 47, 63))
                content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                content.setLeading(24f)
                content.newLineAtOffset(68f, 638f)
                content.showText("Ceramic mug run")
                content.newLine()
                content.showText("2 x EUR 18.40")
                content.newLine()
                content.showText("Poster set")
                content.newLine()
                content.showText("4 x EUR 14.85")
                content.newLine()
                content.showText("Total EUR 96.20")
                content.endText()
            }

            document.save(target)
        }
    }

    private fun probePdf(
        pdf: File,
        png: File,
    ): PdfProbe =
        Loader.loadPDF(pdf).use { document ->
            val image = PDFRenderer(document).renderImageWithDPI(0, 96f, ImageType.RGB)
            ImageIO.write(image, "png", png)
            val mediaBox = document.getPage(0).mediaBox
            PdfProbe(
                pages = document.numberOfPages,
                text = PDFTextStripper().getText(document),
                mediaWidth = mediaBox.width,
                mediaHeight = mediaBox.height,
                firstPagePngBytes = png.length(),
            )
        }

    private fun metadataText(file: File): String =
        try {
            FileInputStream(file).use { stream ->
                ImageMetadata.fromStream(stream).tags().joinToString("\n") { it.toString() }
            }
        } catch (exception: Exception) {
            "metadata-read-error:${exception.javaClass.simpleName}:${exception.message}"
        }

    private fun isWebp(file: File): Boolean {
        val bytes = Files.readAllBytes(file.toPath())
        return bytes.size > 12 &&
            String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP"
    }

    private fun proofOutputDir(name: String): Path {
        val directory = Paths.get("build", "proofs", "pdf-image-fixtures", name)
        Files.createDirectories(directory)
        return directory
    }

    private fun fixturePath(name: String): Path =
        Paths.get("testResources", "proofs", "pdf-image-fixtures", name)

    private fun timedMillis(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }

    private data class PdfProbe(
        val pages: Int,
        val text: String,
        val mediaWidth: Float,
        val mediaHeight: Float,
        val firstPagePngBytes: Long,
    )

    private companion object {
        const val MetadataMarker = "VOENIX_SOURCE_METADATA_COMMENT"
    }
}
