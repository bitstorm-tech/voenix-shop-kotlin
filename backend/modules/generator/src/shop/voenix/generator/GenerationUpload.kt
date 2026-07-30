package shop.voenix.generator

import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream

/**
 * What the multipart body of a generation request carried: the image and the prompt it should be
 * generated with, or the one thing that was wrong with it.
 *
 * This type and its reader are the only place in the module that knows Ktor multipart. Everything
 * behind it works on [RawImage] and a prompt id, which is why the service can be tested without a
 * request at all.
 */
internal sealed interface GenerationUpload {
    data class Received(val image: RawImage, val promptId: Long) : GenerationUpload

    /** No `image` part at all, or one without a single byte in it. */
    data object MissingImage : GenerationUpload

    data object TooLarge : GenerationUpload

    /** No `promptId` field, or one that is not a number. */
    data object MissingPromptId : GenerationUpload
}

/** Reads the `image` and `promptId` parts of a generation request. */
internal suspend fun ApplicationCall.receiveGenerationUpload(): GenerationUpload =
    receiveMultipart().readGenerationUpload()

/**
 * Reads both parts in whatever order the client sent them — a multipart body has no required part
 * order, and the frontend's order is not a contract this module may depend on.
 *
 * The image part is read chunk by chunk and the collecting stops as soon as the bytes would exceed
 * [MAX_IMAGE_BYTES]. An oversized upload is therefore refused while it is still arriving, and the
 * server never holds a body it has already decided to reject.
 *
 * A body missing both parts is reported as [GenerationUpload.MissingImage], because that is the
 * first thing the client has to fix.
 */
private suspend fun MultiPartData.readGenerationUpload(): GenerationUpload {
    var image: RawImage? = null
    var promptId: Long? = null
    while (true) {
        val part = readPart() ?: break
        try {
            when {
                part is PartData.FileItem && part.name == IMAGE_PART_NAME ->
                    image = readImagePart(part) ?: return drainAndReportTooLarge()
                part is PartData.FormItem && part.name == PROMPT_ID_PART_NAME ->
                    promptId = part.value.trim().toLongOrNull()
            }
        } finally {
            part.release()
        }
    }
    return when {
        image == null || image.bytes.isEmpty() -> GenerationUpload.MissingImage
        promptId == null -> GenerationUpload.MissingPromptId
        else -> GenerationUpload.Received(image, promptId)
    }
}

/**
 * Reports the refusal, after letting the rest of the body arrive and throwing it away.
 *
 * Not collecting it is the point of the limit; not reading it at all is a different thing and does
 * not work: a client is still sending when the decision is made, and a server that simply stops
 * consuming leaves it writing into a body nobody reads, so the `400` never arrives. The discarded
 * bytes are never held — only the file part already refused was, and it was bounded.
 */
private suspend fun MultiPartData.drainAndReportTooLarge(): GenerationUpload {
    while (true) {
        val part = readPart() ?: break
        part.release()
    }
    return GenerationUpload.TooLarge
}

/** The image of [part], or `null` once it turns out to be larger than [MAX_IMAGE_BYTES]. */
private suspend fun readImagePart(part: PartData.FileItem): RawImage? {
    val channel: ByteReadChannel = part.provider()
    val collected = ByteArrayOutputStream()
    val chunk = ByteArray(CHUNK_BYTES)
    while (!channel.exhausted()) {
        val read = channel.readAvailable(chunk, 0, chunk.size)
        if (read <= 0) break
        if (collected.size() + read > MAX_IMAGE_BYTES) return null
        collected.write(chunk, 0, read)
    }
    return RawImage(collected.toByteArray(), part.contentType?.toString().orEmpty())
}

/**
 * The name of the image part, and therefore also the field name under which every rejected image is
 * reported. The two are deliberately the same constant, so the error cannot name a field the reader
 * does not look for (the image module's `FILE_PART_NAME` precedent).
 */
internal const val IMAGE_PART_NAME: String = "image"

/** The name of the prompt id field, and the field name of every rejection concerning it. */
internal const val PROMPT_ID_PART_NAME: String = "promptId"

internal const val MAX_IMAGE_BYTES: Int = 10 * 1024 * 1024
private const val CHUNK_BYTES = 64 * 1024
