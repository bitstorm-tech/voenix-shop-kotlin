package shop.voenix.image

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal class UploadedImageTest {
    @Test
    fun `the file part is read with its content type`() = runBlocking {
        val upload = multipartOf(filePart(ByteArray(16), contentType = "image/png"))

        val result = assertIs<UploadedImage.Received>(upload.readUploadedImage())
        assertEquals("image/png", result.upload.contentType)
    }

    @Test
    fun `a part without a content type is left to the image storage to reject`() = runBlocking {
        val upload = multipartOf(filePart(ByteArray(16), contentType = null))

        val result = assertIs<UploadedImage.Received>(upload.readUploadedImage())
        assertEquals("", result.upload.contentType)
    }

    @Test
    fun `other parts are skipped and a body without a file part is missing one`() = runBlocking {
        assertEquals(
            UploadedImage.Missing,
            multipartOf(PartData.FormItem("value", {}, Headers.Empty)).readUploadedImage(),
        )

        val withOtherParts =
            multipartOf(
                PartData.FormItem("value", {}, Headers.Empty),
                filePart(ByteArray(16), contentType = "image/webp"),
            )
        val result = assertIs<UploadedImage.Received>(withOtherParts.readUploadedImage())
        assertEquals("image/webp", result.upload.contentType)
    }

    @Test
    fun `exactly the maximum is accepted`() = runBlocking {
        val upload = multipartOf(filePart(ByteArray(ImageUpload.MAX_BYTES), "image/png"))

        val result = assertIs<UploadedImage.Received>(upload.readUploadedImage())
        assertEquals("image/png", result.upload.contentType)
    }

    /**
     * The point of the limit: the reader stops taking bytes as soon as they would exceed the
     * maximum, so a body far larger than that is refused without ever being received completely.
     */
    @Test
    fun `an oversized part is refused before it has been read`() = runBlocking {
        val offered = AtomicLong()
        val source =
            CoroutineScope(Dispatchers.IO).writer {
                val block = ByteArray(BLOCK_BYTES)
                repeat(OFFERED_BLOCKS) {
                    channel.writeFully(block)
                    channel.flush()
                    offered.addAndGet(BLOCK_BYTES.toLong())
                }
            }

        try {
            val upload = multipartOf(filePart(source.channel, contentType = "image/png"))

            assertEquals(UploadedImage.TooLarge, upload.readUploadedImage())
            assertTrue(
                offered.get() < OFFERED_BLOCKS.toLong() * BLOCK_BYTES,
                "The reader took all ${offered.get()} offered bytes before refusing them",
            )
        } finally {
            source.job.cancel()
        }
    }

    private fun filePart(
        bytes: ByteArray,
        contentType: String?,
    ): PartData.FileItem = filePart(ByteReadChannel(bytes), contentType)

    private fun filePart(
        channel: ByteReadChannel,
        contentType: String?,
    ): PartData.FileItem =
        PartData.FileItem(
            { channel },
            {},
            Headers.build {
                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"")
                if (contentType != null) {
                    append(HttpHeaders.ContentType, contentType)
                }
            },
            {},
        )

    private fun multipartOf(vararg parts: PartData): MultiPartData {
        val remaining = parts.toMutableList()
        return object : MultiPartData {
            override suspend fun readPart(): PartData? = remaining.removeFirstOrNull()
        }
    }

    private companion object {
        const val BLOCK_BYTES = 64 * 1024
        const val OFFERED_BLOCKS = 24 * 16
    }
}
