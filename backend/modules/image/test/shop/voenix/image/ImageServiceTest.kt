package shop.voenix.image

import io.ktor.http.ContentType
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import shop.voenix.operation.OperationResult

internal class ImageServiceTest {
    @Test
    fun `jpeg png and webp originals resize with correct media types and visibility isolation`() =
        withService { service, settings ->
            val formats =
                listOf(
                    Triple("sample.jpg", "jpeg", ContentType.Image.JPEG),
                    Triple("sample.png", "png", ContentType.Image.PNG),
                    Triple("sample.webp", "webp", ContentType("image", "webp")),
                )

            formats.forEach { (filename, format, contentType) ->
                val original = settings.publicRoot.resolve("nested/$filename")
                Files.createDirectories(original.parent)
                Files.write(original, imageBytes(format, 300, 200, alpha = format != "jpeg"))

                val result =
                    assertIs<OperationResult.Success<ImageResource>>(
                        runBlocking {
                            service.get(ImageVisibility.PUBLIC, "120x120", "nested/$filename")
                        }
                    )
                assertEquals(contentType, result.value.contentType)
                val decoded = ImageIO.read(result.value.path.toFile())
                assertEquals(120, decoded.width)
                assertEquals(80, decoded.height)
                assertIs<OperationResult.NotFound>(
                    runBlocking { service.get(ImageVisibility.PRIVATE, "120", "nested/$filename") }
                )
            }
        }

    @Test
    fun `unsafe missing unsupported and symlink escaped originals return expected outcomes`() =
        withService { service, settings ->
            assertIs<OperationResult.NotFound>(
                runBlocking { service.get(ImageVisibility.PUBLIC, "100", "missing.png") }
            )
            listOf("../secret.png", "/absolute.png", "a\\b.png", "a//b.png").forEach { filename ->
                assertIs<OperationResult.Invalid>(
                    runBlocking { service.get(ImageVisibility.PUBLIC, "100", filename) }
                )
            }
            assertIs<OperationResult.Invalid>(
                runBlocking { service.get(ImageVisibility.PUBLIC, "100", "sample.gif") }
            )

            val outside = Files.createDirectory(settings.publicRoot.parent.resolve("outside"))
            val outsideImage =
                Files.write(outside.resolve("outside.png"), imageBytes("png", 20, 20))
            val link = settings.publicRoot.resolve("link.png")
            runCatching { Files.createSymbolicLink(link, outsideImage) }
                .onSuccess {
                    assertIs<OperationResult.Invalid>(
                        runBlocking { service.get(ImageVisibility.PUBLIC, "10", "link.png") }
                    )
                }
        }

    @Test
    fun `cache hits stay stable update when original is newer and never outlive the original`() =
        withService { service, settings ->
            val original =
                Files.write(settings.publicRoot.resolve("same.png"), imageBytes("png", 300, 200))
            val first =
                assertIs<OperationResult.Success<ImageResource>>(
                    runBlocking { service.get(ImageVisibility.PUBLIC, "120", "same.png") }
                )
            val firstModified = first.value.lastModifiedMillis
            val firstBytes = Files.readAllBytes(first.value.path)

            val hit =
                assertIs<OperationResult.Success<ImageResource>>(
                    runBlocking { service.get(ImageVisibility.PUBLIC, "120", "same.png") }
                )
            assertEquals(firstModified, hit.value.lastModifiedMillis)

            Files.write(original, imageBytes("png", 300, 200, color = Color.MAGENTA))
            Files.setLastModifiedTime(original, FileTime.fromMillis(firstModified + 2_000))
            val refreshed =
                assertIs<OperationResult.Success<ImageResource>>(
                    runBlocking { service.get(ImageVisibility.PUBLIC, "120", "same.png") }
                )
            assertNotEquals(firstBytes.toList(), Files.readAllBytes(refreshed.value.path).toList())

            Files.delete(original)
            assertIs<OperationResult.NotFound>(
                runBlocking { service.get(ImageVisibility.PUBLIC, "120", "same.png") }
            )
        }

    @Test
    fun `concurrent cache misses publish one complete decodable resource`() =
        withService { service, settings ->
            Files.write(settings.publicRoot.resolve("parallel.webp"), imageBytes("webp", 600, 400))
            val results = runBlocking {
                (1..12)
                    .map {
                        async(Dispatchers.Default) {
                            service.get(ImageVisibility.PUBLIC, "240x240", "parallel.webp")
                        }
                    }
                    .awaitAll()
            }
            val paths = results.map {
                assertIs<OperationResult.Success<ImageResource>>(it).value.path
            }
            assertEquals(1, paths.toSet().size)
            val path = paths.toSet().single()
            val decoded = ImageIO.read(path.toFile())
            assertEquals(240, decoded.width)
            assertEquals(160, decoded.height)
            assertFalse(path.parent.toFile().listFiles().orEmpty().any { it.name.endsWith(".tmp") })
            assertEquals(0, service.activeKeyLockCount)
        }

    @Test
    fun `public storage validates byte content type size format and decoded pixel ceiling`() =
        withService { service, _ ->
            val folder = PublicImageFolder.of("uploads")
            listOf(
                    ImageUpload(byteArrayOf(), "image/png"),
                    ImageUpload(byteArrayOf(1), "image/png"),
                    ImageUpload(ByteArray(10 * 1024 * 1024 + 1), "image/png"),
                    ImageUpload(imageBytes("png", 10, 10), "image/gif"),
                    ImageUpload(imageBytes("png", 10, 10), "image/jpeg"),
                )
                .forEach { upload ->
                    assertIs<OperationResult.Invalid>(runBlocking { service.store(folder, upload) })
                }

            val validAtByteLimit = imageBytes("png", 10, 10).copyOf(10 * 1024 * 1024)
            assertIs<OperationResult.Success<StoredPublicImage>>(
                runBlocking { service.store(folder, ImageUpload(validAtByteLimit, "image/png")) }
            )
            val oversized = ImageUpload(ByteArray(10 * 1024 * 1024 + 1), "image/png")
            assertNull(oversized.bytes)

            listOf(pngHeader(7_999, 5_000), pngHeader(8_000, 5_000)).forEach { header ->
                val acceptedByPixelLimit = runBlocking {
                    service.store(folder, ImageUpload(header, "image/png"))
                }
                val decodeError = assertIs<OperationResult.Invalid>(acceptedByPixelLimit)
                assertEquals(listOf("Invalid image data"), decodeError.errors["image"])
            }
            val abovePixelLimit = runBlocking {
                service.store(folder, ImageUpload(pngHeader(8_001, 5_000), "image/png"))
            }
            val pixelLimitError = assertIs<OperationResult.Invalid>(abovePixelLimit)
            assertEquals(
                listOf("Decoded image exceeds 40 megapixels"),
                pixelLimitError.errors["image"],
            )
        }

    @Test
    fun `public storage normalizes accepted formats to generated webp and preserves alpha`() =
        withService { service, settings ->
            val folder = PublicImageFolder.of("prompt/example-images")
            listOf("jpeg", "png", "webp").forEach { format ->
                val result =
                    assertIs<OperationResult.Success<StoredPublicImage>>(
                        runBlocking {
                            service.store(
                                folder,
                                ImageUpload(
                                    imageBytes(format, 32, 24, alpha = format != "jpeg"),
                                    if (format == "jpeg") "image/jpeg" else "image/$format",
                                ),
                            )
                        }
                    )
                assertTrue(result.value.filename.matches(Regex("[0-9a-f-]+\\.webp")))
                val stored = settings.publicRoot.resolve(folder.path).resolve(result.value.filename)
                val decoded = ImageIO.read(stored.toFile())
                assertEquals(32, decoded.width)
                assertEquals(24, decoded.height)
                if (format != "jpeg") assertTrue(decoded.colorModel.hasAlpha())
            }
        }

    @Test
    fun `exists and idempotent delete stay inside folder and remove all derived cache entries`() =
        withService { service, settings ->
            val folder = PublicImageFolder.of("articles/examples")
            val stored =
                assertIs<OperationResult.Success<StoredPublicImage>>(
                        runBlocking {
                            service.store(
                                folder,
                                ImageUpload(imageBytes("png", 100, 60), "image/png"),
                            )
                        }
                    )
                    .value
            assertEquals(
                OperationResult.Success(true),
                runBlocking { service.exists(folder, stored.filename) },
            )

            val relative = "articles/examples/${stored.filename}"
            val small =
                assertIs<OperationResult.Success<ImageResource>>(
                        runBlocking { service.get(ImageVisibility.PUBLIC, "20", relative) }
                    )
                    .value
                    .path
            val large =
                assertIs<OperationResult.Success<ImageResource>>(
                        runBlocking { service.get(ImageVisibility.PUBLIC, "40", relative) }
                    )
                    .value
                    .path

            assertEquals(
                OperationResult.Success(Unit),
                runBlocking { service.delete(folder, stored.filename) },
            )
            assertFalse(Files.exists(settings.publicRoot.resolve(relative)))
            assertFalse(Files.exists(small))
            assertFalse(Files.exists(large))
            assertEquals(
                OperationResult.Success(Unit),
                runBlocking { service.delete(folder, stored.filename) },
            )
            assertEquals(
                OperationResult.Success(false),
                runBlocking { service.exists(folder, stored.filename) },
            )
            assertIs<OperationResult.Invalid>(
                runBlocking { service.delete(folder, "../escape.webp") }
            )
        }

    @Test
    fun `cache cleanup rejects intermediate symlinks and keeps external files`() =
        withService { service, settings ->
            val folder = PublicImageFolder.of("articles/examples")
            val stored =
                assertIs<OperationResult.Success<StoredPublicImage>>(
                        runBlocking {
                            service.store(
                                folder,
                                ImageUpload(imageBytes("png", 100, 60), "image/png"),
                            )
                        }
                    )
                    .value
            val outside = settings.cacheRoot.parent.resolve("outside-derived")
            val outsideFile = outside.resolve("examples/${stored.filename}")
            Files.createDirectories(outsideFile.parent)
            Files.write(outsideFile, byteArrayOf(1, 2, 3))
            val sizeRoot = settings.cacheRoot.resolve("public/25")
            Files.createDirectories(sizeRoot)

            runCatching { Files.createSymbolicLink(sizeRoot.resolve("articles"), outside) }
                .onSuccess {
                    assertEquals(
                        OperationResult.Success(Unit),
                        runBlocking { service.delete(folder, stored.filename) },
                    )
                    assertTrue(Files.exists(outsideFile))
                }
        }

    @Test
    fun `delete prevents an in flight derivation from publishing after the original is gone`() =
        withBlockedProcessing { service, settings, processingGate ->
            val folder = PublicImageFolder.of("race")
            val original = settings.publicRoot.resolve("race/source.png")
            Files.createDirectories(original.parent)
            Files.write(original, imageBytes("png", 800, 600))

            runBlocking {
                val derivation =
                    async(Dispatchers.Default) {
                        service.get(ImageVisibility.PUBLIC, "400", "race/source.png")
                    }
                waitForKeyLock(service)
                assertEquals(OperationResult.Success(Unit), service.delete(folder, "source.png"))
                processingGate.release()

                assertIs<OperationResult.NotFound>(derivation.await())
            }

            assertFalse(Files.exists(original))
            assertFalse(hasTemporaryFile(settings.cacheRoot))
            assertEquals(0, service.activeKeyLockCount)
        }

    @Test
    fun `source replacement while queued derives the replacement instead of stale pixels`() =
        withBlockedProcessing { service, settings, processingGate ->
            val original = settings.publicRoot.resolve("replace.png")
            Files.write(original, imageBytes("png", 800, 600, color = Color.BLUE))

            val result = runBlocking {
                val derivation =
                    async(Dispatchers.Default) {
                        service.get(ImageVisibility.PUBLIC, "400", "replace.png")
                    }
                waitForKeyLock(service)
                Files.write(original, imageBytes("png", 800, 600, color = Color.MAGENTA))
                Files.setLastModifiedTime(
                    original,
                    FileTime.fromMillis(Files.getLastModifiedTime(original).toMillis() + 2_000),
                )
                processingGate.release()
                assertIs<OperationResult.Success<ImageResource>>(derivation.await())
            }

            val pixel = ImageIO.read(result.value.path.toFile()).getRGB(10, 10)
            assertEquals(Color.MAGENTA.rgb, pixel)
            assertEquals(0, service.activeKeyLockCount)
        }

    @Test
    fun `cancellation while queued propagates and cleans temporary files and locks`() =
        withBlockedProcessing { service, settings, processingGate ->
            Files.write(settings.publicRoot.resolve("cancel.png"), imageBytes("png", 800, 600))

            val failure = runBlocking {
                val derivation =
                    async(Dispatchers.Default) {
                        service.get(ImageVisibility.PUBLIC, "400", "cancel.png")
                    }
                waitForKeyLock(service)
                derivation.cancel()
                val thrown = runCatching { derivation.await() }.exceptionOrNull()
                processingGate.release()
                thrown
            }

            assertIs<CancellationException>(failure)
            assertFalse(hasTemporaryFile(settings.cacheRoot))
            assertEquals(0, service.activeKeyLockCount)
        }

    private fun withBlockedProcessing(test: (ImageService, ImageSettings, Semaphore) -> Unit) {
        val gate = Semaphore(1)
        runBlocking { gate.acquire() }
        try {
            withService(gate) { service, settings -> test(service, settings, gate) }
        } finally {
            if (gate.availablePermits == 0) gate.release()
        }
    }

    private fun withService(
        processingSlots: Semaphore? = null,
        test: (ImageService, ImageSettings) -> Unit,
    ) {
        val root = createTempDirectory("image-service-test")
        try {
            val settings =
                ImageSettings.create(
                    root.resolve("public"),
                    root.resolve("private"),
                    root.resolve("cache"),
                    root,
                )
            val service =
                if (processingSlots == null) {
                    ImageService(settings)
                } else {
                    ImageService(settings, processingSlots)
                }
            test(service, settings)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun waitForKeyLock(service: ImageService) {
        withTimeout(5_000) { while (service.activeKeyLockCount == 0) delay(5) }
    }

    private fun hasTemporaryFile(root: Path): Boolean {
        if (!Files.exists(root)) return false
        return Files.walk(root).use { paths ->
            paths.anyMatch { path -> path.fileName.toString().endsWith(".tmp") }
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
}
