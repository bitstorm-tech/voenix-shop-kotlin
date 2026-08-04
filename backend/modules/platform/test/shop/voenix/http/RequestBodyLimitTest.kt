package shop.voenix.http

import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The application-wide transfer bound of [MAX_REQUEST_BODY_BYTES], installed by
 * `installHttpRuntime` for every route of the application at once.
 */
internal class RequestBodyLimitTest {
    @Test
    fun `a body below the limit reaches the handler`() = testApplication {
        val log = HandlerLog()
        application { installCountingApplication(log) }

        val response = client.post("/upload") { setBody(ByteArray(1024)) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, log.entered)
        assertEquals(listOf(1024), log.read)
    }

    @Test
    fun `a body of exactly the limit reaches the handler`() = testApplication {
        val log = HandlerLog()
        application { installCountingApplication(log) }

        val response = client.post("/upload") { setBody(ByteArray(LIMIT)) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, log.entered)
        assertEquals(listOf(LIMIT), log.read)
    }

    @Test
    fun `an announced body above the limit is refused before the handler runs`() = testApplication {
        val log = HandlerLog()
        application { installCountingApplication(log) }

        val response = client.post("/upload") { setBody(ByteArray(LIMIT + 1)) }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("""{"message":"Request body too large","errors":{}}""", response.bodyAsText())
        // The handler records its entry before it touches the body, so a zero here
        // states that the route never ran — not merely that it read nothing.
        assertEquals(0, log.entered)
        assertEquals(emptyList(), log.read)
    }

    @Test
    fun `a body that only turns out too large while arriving is refused as well`() =
        testApplication {
            val log = HandlerLog()
            application { installCountingApplication(log) }

            // No Content-Length: the size is unknown until the bytes are there, so the
            // limit can only be met while the handler receives the body. The handler
            // does run here, and that is the difference to the announced case above.
            val response = client.post("/upload") { setBody(ByteReadChannel(ByteArray(LIMIT + 1))) }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(
                """{"message":"Request body too large","errors":{}}""",
                response.bodyAsText(),
            )
            assertEquals(1, log.entered)
        }

    @Test
    fun `a multipart upload below the limit is still read part by part`() = testApplication {
        application {
            installHttpRuntime()
            routing {
                post("/multipart") {
                    val sizes = mutableListOf<Int>()
                    val parts = call.receiveMultipart()
                    while (true) {
                        val part = parts.readPart() ?: break
                        if (part is PartData.FileItem) {
                            sizes += part.provider().countBytes()
                        }
                        part.release()
                    }
                    call.respondText(sizes.joinToString())
                }
            }
        }

        val response =
            client.submitFormWithBinaryData(
                "/multipart",
                formData {
                    append(
                        "file",
                        ByteArray(FILE_PART_BYTES),
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"upload.bin\"")
                        },
                    )
                },
            )

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("$FILE_PART_BYTES", response.bodyAsText())
    }

    /** A route that reports that it ran and how many body bytes it managed to read. */
    private fun Application.installCountingApplication(log: HandlerLog) {
        installHttpRuntime()
        routing {
            post("/upload") {
                log.entered++
                val total = call.receiveChannel().countBytes()
                log.read += total
                call.respondText("read $total")
            }
        }
    }

    /** How many bytes this channel carried, read and thrown away. */
    private suspend fun ByteReadChannel.countBytes(): Int {
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val count = readAvailable(chunk, 0, chunk.size)
            if (count <= 0) break
            total += count
        }
        return total
    }

    /**
     * What the counting route did. [entered] is raised *before* the body is touched, so the two
     * numbers tell "the handler never ran" apart from "the handler ran and read nothing".
     */
    private class HandlerLog {
        var entered: Int = 0
        val read: MutableList<Int> = mutableListOf()
    }

    private companion object {
        val LIMIT = MAX_REQUEST_BODY_BYTES.toInt()
        const val FILE_PART_BYTES = 4 * 1024 * 1024
    }
}
