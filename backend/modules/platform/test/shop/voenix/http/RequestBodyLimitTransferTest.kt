package shop.voenix.http

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.IOException
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What a client on a real socket sees when it announces more than [MAX_REQUEST_BODY_BYTES].
 *
 * The in-memory test host cannot answer this question: it hands a body over as a channel and never
 * puts a byte on a wire. Whether the refusal costs the transfer is exactly the point of this limit,
 * so this one test runs the real Netty engine and talks to it with a plain socket.
 */
internal class RequestBodyLimitTransferTest {
    @Test
    fun `an announced body above the limit is refused without being transferred`() = runBlocking {
        val handlerRan = mutableListOf<String>()
        val server =
            embeddedServer(Netty, port = 0) {
                installHttpRuntime()
                routing {
                    post("/upload") {
                        handlerRan += "upload"
                        call.receiveChannel()
                        call.respondText("read")
                    }
                }
            }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val exchange = postOversizedBody(port)

            assertTrue(
                exchange.statusLine.startsWith("HTTP/1.1 413"),
                "expected a 413 status line, got: ${exchange.statusLine}",
            )
            assertEquals(emptyList(), handlerRan)
            // Measured: about 1.4 MB of the announced 60 MB reach the socket buffers before the
            // 413 arrives and the writing stops. The bound asserted here is deliberately far
            // looser than that, and still far below a drained body.
            assertTrue(
                exchange.bytesWritten < MAX_REQUEST_BODY_BYTES,
                "the server took ${exchange.bytesWritten} bytes instead of cutting the transfer off",
            )
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    /**
     * Announces [ANNOUNCED_BYTES] and then sends them, until the server answers or refuses to take
     * more. Reading runs on its own thread because a server that stops reading blocks the writer as
     * soon as the socket buffers are full — which is the behavior under test, not a failure.
     */
    private fun postOversizedBody(port: Int): Exchange =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val output = socket.getOutputStream()
            val head =
                listOf(
                        "POST /upload HTTP/1.1",
                        "Host: 127.0.0.1:$port",
                        "Content-Type: application/octet-stream",
                        "Content-Length: $ANNOUNCED_BYTES",
                        "",
                        "",
                    )
                    .joinToString("\r\n")
            output.write(head.toByteArray())
            output.flush()

            var statusLine = ""
            val reader = Thread {
                statusLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
            }
            reader.start()

            val chunk = ByteArray(CHUNK_BYTES)
            var written = 0L
            try {
                while (written < ANNOUNCED_BYTES && reader.isAlive) {
                    output.write(chunk)
                    output.flush()
                    written += chunk.size
                }
            } catch (_: IOException) {
                // The server cut the connection off — the refusal this test is about.
            }
            reader.join(SOCKET_TIMEOUT_MILLIS.toLong())
            Exchange(statusLine, written)
        }

    private data class Exchange(val statusLine: String, val bytesWritten: Long)

    private companion object {
        val ANNOUNCED_BYTES = MAX_REQUEST_BODY_BYTES * 2
        const val CHUNK_BYTES = 64 * 1024
        const val SOCKET_TIMEOUT_MILLIS = 10_000
    }
}
