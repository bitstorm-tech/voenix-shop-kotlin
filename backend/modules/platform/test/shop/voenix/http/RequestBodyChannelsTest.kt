package shop.voenix.http

import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [readChunks] against a hand-made [ByteChannel] — no application, no server, no bytes on a wire.
 * The point is the one thing a plain read loop cannot see: *why* a body channel ended.
 */
internal class RequestBodyChannelsTest {
    @Test
    fun `a body that simply ends is read to its end`() = runBlocking {
        val body = ByteArray(200_000) { it.toByte() }
        val channel = ByteChannel()
        channel.writeFully(body)
        channel.flushAndClose()

        val read = mutableListOf<Byte>()
        val complete = channel.readChunks { chunk, count ->
            read += chunk.take(count)
            true
        }

        assertTrue(complete)
        assertEquals(body.toList(), read)
    }

    @Test
    fun `a reader that stops early reports it`() = runBlocking {
        val channel = ByteChannel()
        channel.writeFully(ByteArray(200_000))
        channel.flushAndClose()

        var chunks = 0
        val complete = channel.readChunks { _, _ ->
            chunks++
            false
        }

        assertFalse(complete)
        assertEquals(1, chunks)
    }

    @Test
    fun `a body cut off by the request body limit fails the read`() = runBlocking {
        val channel = ByteChannel()
        channel.writeFully(ByteArray(100_000))
        channel.flush()
        // Exactly what Ktor's body-limit plugin does to the request channel once the bytes past
        // the limit have arrived.
        channel.cancel(PayloadTooLargeException(MAX_REQUEST_BODY_BYTES))

        var read = 0
        assertFailsWith<PayloadTooLargeException> {
            channel.readChunks { _, count ->
                read += count
                true
            }
        }
        // Not one of the buffered bytes is handed out: a close cause makes the channel "closed for
        // read" at once, so readAvailable answers -1 before it looks at the buffer, and the only
        // thing the reader gets is the refusal.
        assertEquals(0, read)
    }

    @Test
    fun `Ktor's own readAvailable stays silent about a cut-off body`() = runBlocking {
        val channel = ByteChannel()
        channel.cancel(PayloadTooLargeException(MAX_REQUEST_BODY_BYTES))

        // This is the Ktor behaviour readChunks exists for: a cancelled channel reads like a body
        // that is simply over, and only the close cause says otherwise. If a Ktor upgrade makes
        // readAvailable rethrow the cause instead, this test fails on purpose — readChunks can
        // then be simplified, and everything that relies on it should be revisited.
        val count = channel.readAvailable(ByteArray(64), 0, 64)
        val cause = channel.closedCause

        assertIs<PayloadTooLargeException>(cause)
        assertEquals(-1, count)
    }
}
