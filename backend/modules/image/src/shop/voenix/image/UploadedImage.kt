package shop.voenix.image

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import java.io.ByteArrayOutputStream
import shop.voenix.http.ApiError
import shop.voenix.http.readChunks

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

public class ImageUpload(
    bytes: ByteArray,
    public val contentType: String,
) {
    internal val byteCount: Int = bytes.size
    internal val bytes: ByteArray? = bytes.takeIf { it.size <= MAX_BYTES }?.copyOf()

    public companion object {
        public const val MAX_BYTES: Int = 10 * 1024 * 1024
    }
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
 * `OperationResult.Invalid`. `413` is deliberately not used: it belongs to the application-wide
 * body limit, which refuses an announced oversized body before any handler runs and cuts an
 * unannounced one off while it arrives, while these are rules of the image pipeline, and a client
 * cannot act differently on the two anyway (Joe's decision of 2026-07-30).
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

/**
 * The image of [part], or [UploadedImage.TooLarge] as soon as one more chunk would pass
 * [ImageUpload.MAX_BYTES] — reading stops at that moment.
 *
 * The read goes through [readChunks] rather than a hand-written loop, so a part that was cut off
 * mid-transfer — the application-wide body limit refusing an oversized upload while it arrives, or
 * a failing connection — fails the request instead of being stored as a complete, merely shorter
 * image. See `docs/dev/backend/request-size-limits.md`.
 */
private suspend fun readFilePart(part: PartData.FileItem): UploadedImage {
    val collected = ByteArrayOutputStream()
    val complete =
        part.provider().readChunks { chunk, count ->
            if (collected.size() + count > ImageUpload.MAX_BYTES) {
                false
            } else {
                collected.write(chunk, 0, count)
                true
            }
        }
    if (!complete) return UploadedImage.TooLarge
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
