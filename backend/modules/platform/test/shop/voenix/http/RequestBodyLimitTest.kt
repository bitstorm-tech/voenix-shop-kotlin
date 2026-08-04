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
        val read = mutableListOf<Int>()
        application { installCountingApplication(read) }

        val response = client.post("/upload") { setBody(ByteArray(1024)) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(1024), read)
    }

    @Test
    fun `a body of exactly the limit reaches the handler`() = testApplication {
        val read = mutableListOf<Int>()
        application { installCountingApplication(read) }

        val response = client.post("/upload") { setBody(ByteArray(LIMIT)) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(LIMIT), read)
    }

    @Test
    fun `an announced body above the limit is refused before the handler runs`() = testApplication {
        val read = mutableListOf<Int>()
        application { installCountingApplication(read) }

        val response = client.post("/upload") { setBody(ByteArray(LIMIT + 1)) }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("""{"message":"Request body too large","errors":{}}""", response.bodyAsText())
        assertEquals(emptyList(), read)
    }

    @Test
    fun `a body that only turns out too large while arriving is refused as well`() =
        testApplication {
            val read = mutableListOf<Int>()
            application { installCountingApplication(read) }

            // No Content-Length: the size is unknown until the bytes are there,
            // so the limit can only be met while the body is arriving.
            val response = client.post("/upload") { setBody(ByteReadChannel(ByteArray(LIMIT + 1))) }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(
                """{"message":"Request body too large","errors":{}}""",
                response.bodyAsText(),
            )
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

    /**
     * A route that reports how many body bytes it managed to read, or nothing when it never ran.
     */
    private fun Application.installCountingApplication(read: MutableList<Int>) {
        installHttpRuntime()
        routing {
            post("/upload") {
                val total = call.receiveChannel().countBytes()
                read += total
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

    private companion object {
        val LIMIT = MAX_REQUEST_BODY_BYTES.toInt()
        const val FILE_PART_BYTES = 4 * 1024 * 1024
    }
}
