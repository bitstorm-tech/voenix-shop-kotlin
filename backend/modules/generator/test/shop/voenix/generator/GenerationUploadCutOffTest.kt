package shop.voenix.generator

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import shop.voenix.http.installHttpRuntime

/**
 * The reader against a body the application-wide request size limit cuts off while it arrives.
 *
 * [GenerationUploadTest] drives the reader through the real route, which is the right place for
 * everything a client can express in a body. This case needs the one thing that test's client never
 * sends: a body without a `Content-Length`, so the limit can only trip mid-transfer. The reader is
 * exercised through its own entry point, `receiveGenerationUpload`, behind a probe route.
 *
 * What matters is the answer: `413`, the refusal, and not a `200` for the bytes that did arrive —
 * half an upload would still cost a fal.ai call and a Magic Coin. See
 * `docs/dev/backend/request-size-limits.md`.
 */
internal class GenerationUploadCutOffTest {
    @Test
    fun `an image that only turns out too large while arriving is refused`() = testApplication {
        application {
            installHttpRuntime()
            routing {
                post("/probe") {
                    call.receiveGenerationUpload()
                    call.respondText("read")
                }
            }
        }

        val response =
            client.post("/probe") {
                header(HttpHeaders.ContentType, "multipart/form-data; boundary=$BOUNDARY")
                setBody(ByteReadChannel(chunkedUpload(APPLICATION_LIMIT_BYTES + 1)))
            }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("""{"message":"Request body too large","errors":{}}""", response.bodyAsText())
    }

    /** A `promptId` and an `image` part of [imageBytes] bytes, framed by hand. */
    private fun chunkedUpload(imageBytes: Int): ByteArray {
        val head = buildString {
            append("--$BOUNDARY\r\n")
            append("Content-Disposition: form-data; name=\"$PROMPT_ID_PART_NAME\"\r\n\r\n42\r\n")
            append("--$BOUNDARY\r\n")
            append("Content-Disposition: form-data; name=\"$IMAGE_PART_NAME\"; ")
            append("filename=\"cropped.png\"\r\n")
            append("Content-Type: image/png\r\n\r\n")
        }
        val tail = "\r\n--$BOUNDARY--\r\n"
        return head.toByteArray() + ByteArray(imageBytes) + tail.toByteArray()
    }

    private companion object {
        const val BOUNDARY = "generator-test-boundary"

        /** The HTTP runtime's limit; the platform keeps the constant to itself. */
        const val APPLICATION_LIMIT_BYTES = 30_000_000
    }
}
