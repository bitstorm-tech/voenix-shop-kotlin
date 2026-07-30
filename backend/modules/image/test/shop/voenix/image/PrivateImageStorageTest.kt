package shop.voenix.image

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import shop.voenix.operation.OperationResult

/**
 * Covers the private print-image storage: what it accepts, what it answers, and that a caller never
 * needs to name a folder or a path to store, read back, and delete one file.
 */
internal class PrivateImageStorageTest {
    @Test
    fun `a stored upload round-trips through delivery and delete removes original and cache`() =
        withService { service, settings ->
            val stored =
                assertIs<OperationResult.Success<StoredPrivateImage>>(
                        runBlocking {
                            service.store(ImageUpload(imageBytes("png", 120, 80), "image/png"))
                        }
                    )
                    .value

            assertTrue(
                stored.filename.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.webp")),
                "Expected a UUID with dashes plus .webp, got ${stored.filename}",
            )
            assertEquals(
                OperationResult.Success(true),
                runBlocking { service.exists(stored.filename) },
            )

            // The delivery route is the only place that combines the image-owned folder with a
            // stored name; storage callers never see it.
            val relative = "$PRINT_IMAGE_FOLDER/${stored.filename}"
            val original = settings.privateRoot.resolve(relative)
            assertTrue(Files.isRegularFile(original))
            assertEquals(
                "webp",
                ImageIO.getImageReaders(ImageIO.createImageInputStream(original.toFile()))
                    .next()
                    .formatName
                    .lowercase(),
            )

            val derived =
                assertIs<OperationResult.Success<ImageResource>>(
                        runBlocking { service.get(ImageVisibility.PRIVATE, "40", relative) }
                    )
                    .value
                    .path
            assertTrue(Files.isRegularFile(derived))

            assertEquals(
                OperationResult.Success(Unit),
                runBlocking { service.delete(stored.filename) },
            )
            assertFalse(Files.exists(original))
            assertFalse(Files.exists(derived), "The private derivation must be removed too")
            assertEquals(
                OperationResult.Success(false),
                runBlocking { service.exists(stored.filename) },
            )
            assertEquals(
                OperationResult.Success(Unit),
                runBlocking { service.delete(stored.filename) },
                "Deleting twice is idempotent",
            )
        }

    @Test
    fun `jpeg png and webp uploads are all normalized to webp at their original size`() =
        withService { service, settings ->
            listOf("jpeg", "png", "webp").forEach { format ->
                val stored =
                    assertIs<OperationResult.Success<StoredPrivateImage>>(
                            runBlocking {
                                service.store(
                                    ImageUpload(
                                        imageBytes(format, 48, 32, alpha = format != "jpeg"),
                                        if (format == "jpeg") "image/jpeg" else "image/$format",
                                    )
                                )
                            }
                        )
                        .value
                assertTrue(stored.filename.endsWith(".webp"))
                val decoded =
                    ImageIO.read(
                        settings.privateRoot
                            .resolve(PRINT_IMAGE_FOLDER)
                            .resolve(stored.filename)
                            .toFile()
                    )
                assertEquals(48, decoded.width)
                assertEquals(32, decoded.height)
            }
        }

    @Test
    fun `gif is rejected however it is offered`() = withService { service, settings ->
        val gifBytes = imageBytes("gif", 16, 16)

        // Declared as GIF: refused on the declared content type alone.
        assertIs<OperationResult.Invalid>(
            runBlocking { service.store(ImageUpload(gifBytes, "image/gif")) }
        )
        // Declared as PNG: refused while decoding, because GIF is not a format the codec knows.
        val disguised =
            assertIs<OperationResult.Invalid>(
                runBlocking { service.store(ImageUpload(gifBytes, "image/png")) }
            )
        assertEquals(listOf("Invalid image data"), disguised.errors["image"])
        val folder = settings.privateRoot.resolve(PRINT_IMAGE_FOLDER)
        val leftovers =
            if (Files.isDirectory(folder)) {
                Files.list(folder).use { entries -> entries.toList() }
            } else {
                emptyList()
            }
        assertTrue(leftovers.isEmpty(), "A rejected upload leaves no file behind: $leftovers")
    }

    @Test
    fun `the byte and pixel limits are the same as public storage`() = withService { service, _ ->
        listOf(
                ImageUpload(byteArrayOf(), "image/png"),
                ImageUpload(byteArrayOf(1), "image/png"),
                ImageUpload(ByteArray(MAX_BYTES + 1), "image/png"),
            )
            .forEach { upload ->
                assertIs<OperationResult.Invalid>(runBlocking { service.store(upload) })
            }
        assertNull(ImageUpload(ByteArray(MAX_BYTES + 1), "image/png").bytes)

        val atByteLimit = imageBytes("png", 10, 10).copyOf(MAX_BYTES)
        assertIs<OperationResult.Success<StoredPrivateImage>>(
            runBlocking { service.store(ImageUpload(atByteLimit, "image/png")) }
        )

        val atPixelLimit = runBlocking {
            service.store(ImageUpload(pngHeader(8_000, 5_000), "image/png"))
        }
        assertEquals(
            listOf("Invalid image data"),
            assertIs<OperationResult.Invalid>(atPixelLimit).errors["image"],
            "40 megapixels is still accepted for inspection; only the truncated bytes fail",
        )
        val abovePixelLimit = runBlocking {
            service.store(ImageUpload(pngHeader(8_001, 5_000), "image/png"))
        }
        assertEquals(
            listOf("Decoded image exceeds 40 megapixels"),
            assertIs<OperationResult.Invalid>(abovePixelLimit).errors["image"],
        )
    }

    @Test
    fun `a file name that is not a plain name is rejected instead of escaping the folder`() =
        withService { service, _ ->
            listOf("../escape.webp", "nested/name.webp", "/absolute.webp", "").forEach { name ->
                assertIs<OperationResult.Invalid>(runBlocking { service.exists(name) })
                assertIs<OperationResult.Invalid>(runBlocking { service.delete(name) })
            }
        }

    private fun withService(test: (ImageService, ImageSettings) -> Unit) {
        val root = createTempDirectory("private-image-storage-test")
        try {
            val settings =
                ImageSettings.create(
                    root.resolve("public"),
                    root.resolve("private"),
                    root.resolve("cache"),
                    root,
                )
            test(ImageService(settings), settings)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun imageBytes(
        format: String,
        width: Int,
        height: Int,
        alpha: Boolean = false,
        color: Color = Color(20, 90, 180, if (alpha) 128 else 255),
    ): ByteArray {
        val type = if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(width, height, type)
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(image, format, output), "Missing writer for $format")
            output.toByteArray()
        }
    }

    /** A PNG signature plus IHDR only: enough for the dimension check, never a decodable image. */
    private fun pngHeader(
        width: Int,
        height: Int,
    ): ByteArray {
        val type = "IHDR".toByteArray(Charsets.US_ASCII)
        val data =
            ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put(8)
                .put(6)
                .put(0)
                .put(0)
                .put(0)
                .array()
        val crc =
            CRC32().apply {
                update(type)
                update(data)
            }
        return ByteBuffer.allocate(8 + 4 + 4 + 13 + 4)
            .put(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            .putInt(13)
            .put(type)
            .put(data)
            .putInt(crc.value.toInt())
            .array()
    }

    private companion object {
        private const val MAX_BYTES = 10 * 1024 * 1024
    }
}
