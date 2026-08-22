package shop.voenix.generator

import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import java.io.ByteArrayOutputStream
import shop.voenix.http.readChunks

/**
 * What the multipart body of a generation request carried: the image, the prompt it should be
 * generated with, and the article it is generated for — or the one thing that was wrong with it.
 *
 * This type and its reader are the only place in the module that knows Ktor multipart. Everything
 * behind it works on [RawImage] and two ids, which is why the service can be tested without a
 * request at all.
 */
internal sealed interface GenerationUpload {
    data class Received(val image: RawImage, val promptId: Long, val articleId: Long) :
        GenerationUpload

    /** No `image` part at all, or one without a single byte in it. */
    data object MissingImage : GenerationUpload

    data object TooLarge : GenerationUpload

    /** No `promptId` field, or one that is not a number. */
    data object MissingPromptId : GenerationUpload

    /** No `articleId` field, or one that is not a number. */
    data object MissingArticleId : GenerationUpload
}

/** Reads the `image`, `promptId`, and `articleId` parts of a generation request. */
internal suspend fun ApplicationCall.receiveGenerationUpload(): GenerationUpload =
    receiveMultipart().readGenerationUpload()

/**
 * Reads both parts in whatever order the client sent them — a multipart body has no required part
 * order, and the frontend's order is not a contract this module may depend on.
 *
 * Two limits bound this read, and they answer two different questions. [MAX_IMAGE_BYTES] is how
 * large one picture may be. [MAX_REQUEST_BYTES] is how many bytes of file parts the reader
 * processes for one request at all, because a body may carry any number of file parts and each one
 * of them below the single-image limit still adds up. Both are enforced while the body is still
 * arriving, so the server never holds a body it has already decided to reject.
 *
 * Repeated parts are not an error: the last `image`, the last `promptId`, and the last `articleId`
 * of a body win, the way every form parser resolves a repeated field. Each repetition still costs
 * against [MAX_REQUEST_BYTES].
 *
 * Only file parts are counted. The form values are the tiny `promptId` and `articleId` fields here,
 * and Ktor has already materialized them by the time this reader sees them, so counting them would
 * bound nothing.
 *
 * A body missing several parts is reported as [GenerationUpload.MissingImage] first and
 * [GenerationUpload.MissingPromptId] before [GenerationUpload.MissingArticleId], because one
 * rejection names one field and the client fixes them in that order.
 */
private suspend fun MultiPartData.readGenerationUpload(): GenerationUpload {
    var image: RawImage? = null
    var promptId: Long? = null
    var articleId: Long? = null
    var budget = MAX_REQUEST_BYTES
    var refused = false
    while (true) {
        val part = readPart() ?: break
        try {
            when {
                part is PartData.FileItem && part.name == IMAGE_PART_NAME -> {
                    val read = readImagePart(part, minOf(MAX_IMAGE_BYTES, budget))
                    if (read == null) {
                        refused = true
                    } else {
                        budget -= read.bytes.size
                        image = read
                    }
                }
                part is PartData.FileItem -> {
                    val discarded = discardPart(part, budget)
                    if (discarded == null) refused = true else budget -= discarded
                }
                part is PartData.FormItem && part.name == PROMPT_ID_PART_NAME ->
                    promptId = part.value.trim().toLongOrNull()
                part is PartData.FormItem && part.name == ARTICLE_ID_PART_NAME ->
                    articleId = part.value.trim().toLongOrNull()
            }
        } finally {
            part.release()
        }
        if (refused) return drainAndReportTooLarge()
    }
    return when {
        image == null || image.bytes.isEmpty() -> GenerationUpload.MissingImage
        promptId == null -> GenerationUpload.MissingPromptId
        articleId == null -> GenerationUpload.MissingArticleId
        else -> GenerationUpload.Received(image, promptId, articleId)
    }
}

/**
 * Reports the refusal, after letting the rest of the body arrive and throwing it away.
 *
 * Not collecting it is the point of both limits; not reading it at all is a different thing and
 * does not work, for two reasons. A client is still sending when the decision is made, and a server
 * that simply stops consuming leaves it writing into a body nobody reads, so the `400` never
 * arrives — and a Ktor multipart read that is abandoned mid-body never lets the call finish at all,
 * because the parser behind [MultiPartData] stays waiting for a reader that never comes. Cutting
 * the transfer off is therefore not this reader's decision to make: an engine-level request-size
 * limit is where that belongs, the way the legacy application had one in Kestrel.
 *
 * The discarded bytes are never held — only the file part already refused was, and it was bounded.
 */
private suspend fun MultiPartData.drainAndReportTooLarge(): GenerationUpload {
    while (true) {
        val part = readPart() ?: break
        part.release()
    }
    return GenerationUpload.TooLarge
}

/** The image of [part], or `null` once it turns out to be larger than [limit]. */
private suspend fun readImagePart(
    part: PartData.FileItem,
    limit: Int,
): RawImage? {
    val collected = ByteArrayOutputStream()
    consumePart(part, limit, collected) ?: return null
    return RawImage(collected.toByteArray(), part.contentType?.toString().orEmpty())
}

/** How many bytes [part] held, read and thrown away, or `null` once it is larger than [limit]. */
private suspend fun discardPart(
    part: PartData.FileItem,
    limit: Int,
): Int? = consumePart(part, limit, sink = null)

/**
 * Reads [part] chunk by chunk into [sink] — or into nothing, when there is none — and answers with
 * the number of bytes it took, or `null` as soon as one more chunk would pass [limit]. Reading
 * stops at that moment, so what a part announces about its size never decides how much of it this
 * server moves.
 *
 * The read goes through [readChunks] rather than a hand-written loop, which makes the other way a
 * part can end an error: a part that was cut off mid-transfer — the application-wide body limit
 * refusing an oversized request while it arrives, or a failing connection — fails the request
 * instead of being taken for a complete, merely shorter one. Nothing is generated from half an
 * upload. See `docs/dev/backend/request-size-limits.md`.
 */
private suspend fun consumePart(
    part: PartData.FileItem,
    limit: Int,
    sink: ByteArrayOutputStream?,
): Int? {
    var consumed = 0
    val complete =
        part.provider().readChunks { chunk, count ->
            if (consumed + count > limit) {
                false
            } else {
                sink?.write(chunk, 0, count)
                consumed += count
                true
            }
        }
    return consumed.takeIf { complete }
}

/**
 * The name of the image part, and therefore also the field name under which every rejected image is
 * reported. The two are deliberately the same constant, so the error cannot name a field the reader
 * does not look for (the image module's `FILE_PART_NAME` precedent).
 */
internal const val IMAGE_PART_NAME: String = "image"

/** The name of the prompt id field, and the field name of every rejection concerning it. */
internal const val PROMPT_ID_PART_NAME: String = "promptId"

/**
 * The name of the article id field, and the field name of every rejection concerning it. The
 * article decides the shape of the generated image, so a generation cannot be asked for without
 * one.
 */
internal const val ARTICLE_ID_PART_NAME: String = "articleId"

internal const val MAX_IMAGE_BYTES: Int = 10 * 1024 * 1024

/**
 * How many bytes of file parts one request may spend before the reader gives up on it. Two images'
 * worth is deliberate slack: one legitimate upload plus whatever else a browser puts around it fits
 * comfortably, and a body that repeats parts to keep the server working does not.
 */
internal const val MAX_REQUEST_BYTES: Int = 2 * MAX_IMAGE_BYTES
