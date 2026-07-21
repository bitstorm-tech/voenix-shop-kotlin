package shop.voenix.image

import com.sksamuel.scrimage.ImmutableImage
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class ImageCodecRuntimeSmokeTest {
    @Test
    fun `jpeg png and webp codecs plus fit within resizing work`() {
        val source =
            BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB).also { image ->
                val graphics = image.createGraphics()
                try {
                    graphics.color = Color(20, 90, 180, 160)
                    graphics.fillRect(0, 0, image.width, image.height)
                } finally {
                    graphics.dispose()
                }
            }

        val png = encode(source, "png")
        val jpeg = encode(toRgb(source), "jpeg")
        val webp = encode(source, "webp")

        listOf(png, jpeg, webp).forEach { bytes ->
            val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
            assertEquals(300, decoded.width)
            assertEquals(200, decoded.height)
        }

        val resized = ImmutableImage.loader().fromBytes(webp).max(120, 120)
        assertEquals(120, resized.width)
        assertEquals(80, resized.height)
    }

    private fun encode(
        image: BufferedImage,
        format: String,
    ): ByteArray =
        ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(image, format, output), "Missing ImageIO writer for $format")
            output.toByteArray()
        }

    private fun toRgb(source: BufferedImage): BufferedImage =
        BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB).also { target ->
            val graphics = target.createGraphics()
            try {
                graphics.drawImage(source, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }
}
