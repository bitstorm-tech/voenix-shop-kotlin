package shop.voenix.image

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import shop.voenix.http.ApiError

/**
 * What a pre-upload request carried: the image of its `file` part, no such part at all, or more
 * bytes than the image storage accepts.
 *
 * The type lives here rather than in a module that uploads images because more than one of them
 * does. Articles and prompts read the same `file` part under the same limit, and cart is the third
 * consumer — so the reading is the image module's business, and what each of them still decides for
 * itself is only the answer it sends. The name says nothing about example images: cart uploads
 * print images, not examples.
 */
public sealed interface UploadedImage {
    public data class Received(val upload: ImageUpload) : UploadedImage

    public data object Missing : UploadedImage

    public data object TooLarge : UploadedImage
}

/** Reads the `file` part of a multipart pre-upload request. */
public suspend fun ApplicationCall.receiveUploadedImage(): UploadedImage =
    receiveMultipart().readUploadedImage()

/**
 * Answers a request whose `file` part the reader refused: `400` with [message] scoped to the
 * [FILE_PART_NAME] field.
 *
 * Every rejection of an uploaded image uses this one shape — the missing part and the oversized
 * part here, the format, emptiness, and decoding rules through the storage's
 * `OperationResult.Invalid`. `413` is deliberately not used: it belongs to a body limit enforced
 * before any handler runs, while these are rules of the image pipeline, and a client cannot act
 * differently on the two anyway (Joe's decision of 2026-07-30).
 */
public suspend fun ApplicationCall.respondUploadRejection(message: String): Unit =
    respond(
        HttpStatusCode.BadRequest,
        ApiError("Validation failed", mapOf(FILE_PART_NAME to listOf(message))),
    )

/**
 * Reads the `file` part of [this] multipart body.
 *
 * The part is read chunk by chunk and the read stops as soon as the collected bytes would exceed
 * [ImageUpload.MAX_BYTES]. That is the point of reading it here instead of handing the whole body
 * to the image storage: an oversized upload is refused while it is still arriving, so the server
 * never holds a body it has already decided to reject.
 */
internal suspend fun MultiPartData.readUploadedImage(): UploadedImage {
    while (true) {
        val part = readPart() ?: return UploadedImage.Missing
        if (part is PartData.FileItem && part.name == FILE_PART_NAME) {
            return try {
                readFilePart(part)
            } finally {
                part.release()
            }
        }
        part.release()
    }
}

private suspend fun readFilePart(part: PartData.FileItem): UploadedImage {
    val channel: ByteReadChannel = part.provider()
    val collected = ByteArrayOutputStream()
    val chunk = ByteArray(CHUNK_BYTES)
    while (!channel.exhausted()) {
        val read = channel.readAvailable(chunk, 0, chunk.size)
        if (read <= 0) break
        if (collected.size() + read > ImageUpload.MAX_BYTES) return UploadedImage.TooLarge
        collected.write(chunk, 0, read)
    }
    return UploadedImage.Received(
        ImageUpload(collected.toByteArray(), part.contentType?.toString().orEmpty())
    )
}

/**
 * The name of the multipart part every pre-upload reads, and therefore also the field name under
 * which all four upload endpoints report a rejected image.
 *
 * The two are deliberately the same constant. A client that sent the wrong thing is told which part
 * of its request was wrong, in the part's own name, and the field name cannot drift away from what
 * the reader actually looks for (Joe's decision of 2026-07-30; see `image-package.md`).
 */
public const val FILE_PART_NAME: String = "file"
private const val CHUNK_BYTES = 64 * 1024
