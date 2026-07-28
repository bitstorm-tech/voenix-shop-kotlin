package shop.voenix.article

import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import shop.voenix.image.ImageUpload

/**
 * What a pre-upload request carried: the image of its `file` part, no such part at all, or more
 * bytes than the image storage accepts.
 */
internal sealed interface ExampleImageUpload {
    data class Received(val upload: ImageUpload) : ExampleImageUpload

    data object Missing : ExampleImageUpload

    data object TooLarge : ExampleImageUpload
}

/** Reads the `file` part of a multipart pre-upload request. */
internal suspend fun ApplicationCall.receiveExampleImageUpload(): ExampleImageUpload =
    receiveMultipart().readExampleImageUpload()

/**
 * Reads the `file` part of [this] multipart body.
 *
 * The part is read chunk by chunk and the read stops as soon as the collected bytes would exceed
 * [ImageUpload.MAX_BYTES]. That is the point of reading it here instead of handing the whole body
 * to the image storage: an oversized upload is refused while it is still arriving, so the server
 * never holds a body it has already decided to reject.
 */
internal suspend fun MultiPartData.readExampleImageUpload(): ExampleImageUpload {
    while (true) {
        val part = readPart() ?: return ExampleImageUpload.Missing
        if (part is PartData.FileItem && part.name == FILE_PART_NAME) {
            return try {
                readExampleImage(part)
            } finally {
                part.release()
            }
        }
        part.release()
    }
}

private suspend fun readExampleImage(part: PartData.FileItem): ExampleImageUpload {
    val channel: ByteReadChannel = part.provider()
    val collected = ByteArrayOutputStream()
    val chunk = ByteArray(CHUNK_BYTES)
    while (!channel.exhausted()) {
        val read = channel.readAvailable(chunk, 0, chunk.size)
        if (read <= 0) break
        if (collected.size() + read > ImageUpload.MAX_BYTES) return ExampleImageUpload.TooLarge
        collected.write(chunk, 0, read)
    }
    return ExampleImageUpload.Received(
        ImageUpload(collected.toByteArray(), part.contentType?.toString().orEmpty())
    )
}

private const val FILE_PART_NAME = "file"
private const val CHUNK_BYTES = 64 * 1024
