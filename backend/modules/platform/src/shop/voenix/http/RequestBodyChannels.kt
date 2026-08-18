package shop.voenix.http

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/** How much of a body is read in one go. */
private const val CHUNK_BYTES = 64 * 1024

/**
 * Reads this request body chunk by chunk and hands every chunk to [onChunk], which answers `true`
 * to carry on and `false` to stop right there. Returns `true` when the body was read to its end and
 * `false` when [onChunk] stopped it — a reader that has seen enough (its own size limit, for
 * example) does not have to read the rest.
 *
 * Use this instead of a hand-written `readAvailable` loop whenever a handler reads a request body
 * itself. A body channel ends for two very different reasons: either the body was over, or
 * something cut it off — the application-wide request body limit refusing an oversized upload while
 * it arrives, or the connection failing mid-transfer. Ktor's `readAvailable` answers `-1` in *both*
 * cases and says nothing about which one happened, so a plain loop quietly treats a body that was
 * cut off in the middle as a complete one. Asking the channel for its `closedCause` afterwards is
 * what tells the two apart, and that is what this function does: it rethrows that cause, so the
 * refusal reaches `StatusPages` as the `PayloadTooLargeException` it is and answers `413` instead
 * of the handler storing half an upload.
 *
 * [onChunk] is deliberately not `suspend`: it is meant to count, hash, or copy bytes into memory or
 * a file, and keeping it non-suspending keeps callers from starting slow work — a database write,
 * an HTTP call — while the client is still sending. The chunk array is reused between calls, so a
 * caller that wants to keep bytes must copy them out.
 *
 * The `count` passed to [onChunk] is how many bytes of `chunk` are filled; the rest of the array is
 * leftovers from the previous chunk.
 *
 * The whole picture is in `docs/dev/backend/request-size-limits.md`.
 */
public suspend fun ByteReadChannel.readChunks(
    onChunk: (chunk: ByteArray, count: Int) -> Boolean
): Boolean {
    val chunk = ByteArray(CHUNK_BYTES)
    while (true) {
        val count = readAvailable(chunk, 0, chunk.size)
        if (count <= 0) break
        if (!onChunk(chunk, count)) return false
    }
    // The loop above cannot tell "the body was over" from "the body was cut off": both end it with
    // -1. The close cause can, and rethrowing it is the whole point of this function.
    closedCause?.let { throw it }
    return true
}
