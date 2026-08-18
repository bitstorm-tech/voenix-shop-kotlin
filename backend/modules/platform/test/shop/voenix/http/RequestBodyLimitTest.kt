package shop.voenix.http

import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
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
        // A body of known size announces it, and that is what lets the limit refuse an
        // oversized request before the handler runs at all.
        assertEquals(listOf<Long?>(1024), log.announcedLength)
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
            // limit can only be met while the handler receives the body. The handler does
            // run here, and that is the difference to the announced case above — it only
            // learns of the refusal because its read asks the channel *why* the body
            // ended. A plain read loop would see a body that is simply over, count the
            // truncated bytes and answer 200.
            val response = client.post("/upload") { setBody(ByteReadChannel(ByteArray(LIMIT + 1))) }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(
                """{"message":"Request body too large","errors":{}}""",
                response.bodyAsText(),
            )
            assertEquals(1, log.entered)
            // The handler's read never completes, so it never records a byte count. How many
            // bytes it saw before the cut-off is not fixed and is deliberately not asserted.
            assertEquals(emptyList(), log.read)
            assertEquals(listOf<Long?>(null), log.announcedLength)
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

    @Test
    fun `a multipart body that only turns out too large while arriving is refused as well`() =
        testApplication {
            application {
                installHttpRuntime()
                routing {
                    post("/multipart") {
                        val parts = call.receiveMultipart()
                        var total = 0
                        while (true) {
                            val part = parts.readPart() ?: break
                            if (part is PartData.FileItem) {
                                total += part.provider().countBytes()
                            }
                            part.release()
                        }
                        call.respondText("read $total")
                    }
                }
            }

            // A browser upload without a Content-Length: the multipart parser hands the file
            // part out as a channel of its own, and the refusal arrives in the middle of it.
            val response =
                client.post("/multipart") {
                    header(HttpHeaders.ContentType, "multipart/form-data; boundary=$BOUNDARY")
                    setBody(ByteReadChannel(chunkedMultipartBody(LIMIT + 1)))
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(
                """{"message":"Request body too large","errors":{}}""",
                response.bodyAsText(),
            )
        }

    /** One `file` part of [fileBytes] bytes, framed by hand so the body announces no size. */
    private fun chunkedMultipartBody(fileBytes: Int): ByteArray {
        val head = buildString {
            append("--$BOUNDARY\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"upload.bin\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }
        val tail = "\r\n--$BOUNDARY--\r\n"
        return head.toByteArray() + ByteArray(fileBytes) + tail.toByteArray()
    }

    /** A route that reports that it ran and how many body bytes it managed to read. */
    private fun Application.installCountingApplication(log: HandlerLog) {
        installHttpRuntime()
        routing {
            post("/upload") {
                log.entered++
                log.announcedLength += call.request.contentLength()
                val total = call.receiveChannel().countBytes()
                log.read += total
                call.respondText("read $total")
            }
        }
    }

    /**
     * How many bytes this channel carried, read and thrown away. It reads through [readChunks], so
     * a body that was cut off mid-arrival ends this function with the refusal instead of a byte
     * count.
     */
    private suspend fun ByteReadChannel.countBytes(): Int {
        var total = 0
        readChunks { _, count ->
            total += count
            true
        }
        return total
    }

    /**
     * What the counting route did. [entered] is raised *before* the body is touched, so the two
     * numbers tell "the handler never ran" apart from "the handler ran and read nothing".
     * [announcedLength] is the request's `Content-Length`, `null` for a body that announces no
     * size.
     */
    private class HandlerLog {
        var entered: Int = 0
        val announcedLength: MutableList<Long?> = mutableListOf()
        val read: MutableList<Int> = mutableListOf()
    }

    private companion object {
        val LIMIT = MAX_REQUEST_BODY_BYTES.toInt()
        const val FILE_PART_BYTES = 4 * 1024 * 1024
        const val BOUNDARY = "voenix-test-boundary"
    }
}
