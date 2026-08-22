package shop.voenix.production.delivery.spod

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the conversion promises the submission stage: a WebP original goes in, PNG within both
 * budgets comes out, and everything that cannot be converted is a bounded code instead of an
 * exception.
 */
internal class PrintImagePngTest {
    private val directory: Path = Files.createTempDirectory("print-image-png-test")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `the webp ImageIO reader is registered on this classpath`() {
        assertTrue(
            ImageIO.getImageReadersByMIMEType("image/webp").hasNext(),
            "a WebP reader must be registered, otherwise the rest of this test proves nothing " +
                "about the format every production image actually arrives in",
        )
    }

    @Test
    fun `a webp original becomes png of the same size`() {
        val source = writeWebp("design.webp", width = 640, height = 480)

        val converted = assertIs<PrintImagePngResult.Converted>(PrintImagePng.convert(source))

        val decoded = assertNotNull(decode(converted.bytes), "the result decodes as an image")
        assertEquals(640, decoded.width)
        assertEquals(480, decoded.height)
        assertEquals("png", formatOf(converted.bytes), "the partner's upload takes PNG only")
    }

    @Test
    fun `an image beyond the pixel cap is scaled down and keeps its aspect ratio`() {
        val source = writeWebp("huge.webp", width = 6000, height = 3000)

        val converted = assertIs<PrintImagePngResult.Converted>(PrintImagePng.convert(source))

        val decoded = assertNotNull(decode(converted.bytes))
        assertEquals(PrintImagePng.MAX_EDGE_PIXELS, decoded.width, "the longest edge is capped")
        assertEquals(PrintImagePng.MAX_EDGE_PIXELS / 2, decoded.height, "the ratio survives")
    }

    /**
     * The budget is a test parameter here on purpose: an image that overruns 9.5 MB after three
     * halvings would have to be gigantic, and this proves the same loop with a laptop-sized one.
     */
    @Test
    fun `an image over the budget is halved until it fits`() {
        val source = writeNoisePng("noise.png", width = 800, height = 800)
        val full = assertIs<PrintImagePngResult.Converted>(PrintImagePng.convert(source))

        val shrunk =
            assertIs<PrintImagePngResult.Converted>(
                PrintImagePng.convert(source, maxBytes = full.bytes.size / 2)
            )

        val decoded = assertNotNull(decode(shrunk.bytes))
        assertEquals(400, decoded.width, "one halving was enough for half the budget")
        assertTrue(shrunk.bytes.size <= full.bytes.size / 2, "the result is within the budget")
    }

    @Test
    fun `an image that never fits gives up after the bounded number of shrinks`() {
        val source = writeNoisePng("stubborn.png", width = 800, height = 800)

        val result = PrintImagePng.convert(source, maxBytes = 1)

        assertEquals(
            PrintImagePngResult.Failed(PrintImageError.PRINT_IMAGE_TOO_LARGE),
            result,
            "the shrink loop is bounded, so a pathological image fails instead of looping",
        )
    }

    @Test
    fun `a line without an image and a file that is not there are both missing`() {
        assertEquals(
            PrintImagePngResult.Failed(PrintImageError.PRINT_IMAGE_MISSING),
            PrintImagePng.convert(path = null),
        )
        assertEquals(
            PrintImagePngResult.Failed(PrintImageError.PRINT_IMAGE_MISSING),
            PrintImagePng.convert(directory.resolve("never-generated.webp")),
        )
    }

    @Test
    fun `a file no reader claims is unreadable rather than an exception`() {
        val source = directory.resolve("not-an-image.webp")
        Files.write(source, "this is not an image".toByteArray())

        assertEquals(
            PrintImagePngResult.Failed(PrintImageError.PRINT_IMAGE_UNREADABLE),
            PrintImagePng.convert(source),
        )
    }

    private fun writeWebp(name: String, width: Int, height: Int): Path {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0x20, 0x60, 0xA0)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return writeWebp(name, image)
    }

    /**
     * Random pixels, stored losslessly: PNG cannot compress noise, which is how a byte budget is
     * overrun on demand. The source format is PNG rather than WebP here because a lossy WebP round
     * trip would smooth the noise away and with it the point of the test.
     */
    private fun writeNoisePng(name: String, width: Int, height: Int): Path {
        val random = Random(seed = 4711)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        repeat(height) { y -> repeat(width) { x -> image.setRGB(x, y, random.nextInt()) } }
        val path = directory.resolve(name)
        ImageIO.write(image, "png", path.toFile())
        return path
    }

    private fun writeWebp(name: String, image: BufferedImage): Path {
        val path = directory.resolve(name)
        val writer = ImageIO.getImageWritersByMIMEType("image/webp").next()
        ImageIO.createImageOutputStream(path.toFile()).use { output ->
            writer.output = output
            writer.write(image)
        }
        writer.dispose()
        return path
    }

    private fun decode(bytes: ByteArray): BufferedImage? =
        ByteArrayInputStream(bytes).use(ImageIO::read)

    private fun formatOf(bytes: ByteArray): String =
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            ImageIO.getImageReaders(stream).next().formatName.lowercase()
        }
}
