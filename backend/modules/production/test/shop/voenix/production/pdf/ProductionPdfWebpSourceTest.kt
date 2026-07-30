package shop.voenix.production.pdf

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves that a production PDF renders from a WebP original.
 *
 * The print-image registry stores WebP only, so every production image arrives as WebP. The
 * renderer therefore decodes through ImageIO — where the registered `webp-imageio` reader handles
 * the RIFF container — and embeds the raster with `LosslessFactory`. Going through
 * `PDImageXObject.createFromFileByContent` instead would reject WebP before ImageIO is ever asked
 * (see `docs/migration/cart-migration.md`, § "WebP production PDFs"), so this test guards the
 * decision, not just the happy path.
 */
internal class ProductionPdfWebpSourceTest {
    private val tempDir = newTempDirectory()
    private val renderer = ProductionPdfRenderer()

    @AfterTest
    fun cleanUp() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `the webp ImageIO reader is registered on this classpath`() {
        assertTrue(
            ImageIO.getImageReadersByMIMEType("image/webp").hasNext(),
            "A WebP ImageIO reader must be registered, otherwise this test proves nothing " +
                "about the renderer",
        )
    }

    @Test
    fun `a webp original renders into the production PDF`() {
        val image = writeWebp(tempDir, "item.webp")
        val order = productionOrder(orderId = 5, items = listOf(productionItem(imagePath = image)))

        val result = renderer.render(order, supplierId = 1)

        val pdf =
            assertIs<ProductionPdfRenderResult.Rendered>(result, "a WebP original renders").pdf
        assertNotNull(ImageIO.read(image.toFile()), "ImageIO itself reads the WebP file")
        loadPdf(pdf.bytes).use { document ->
            assertEquals(2, document.numberOfPages, "address page plus one item page")
            assertTrue(
                document.getPage(1).resources.xObjectNames.any(),
                "the item page embeds the decoded WebP image",
            )
        }
    }

    private fun writeWebp(
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
        val writer = ImageIO.getImageWritersByMIMEType("image/webp").next()
        try {
            FileImageOutputStream(path.toFile()).use { output ->
                writer.output = output
                writer.write(null, IIOImage(image, null, null), writer.defaultWriteParam)
            }
        } finally {
            writer.dispose()
        }
        return path
    }
}
