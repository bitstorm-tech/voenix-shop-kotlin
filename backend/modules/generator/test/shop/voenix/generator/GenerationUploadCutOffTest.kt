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
import java.io.ByteArrayOutputStream
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
 * The body is framed so that the cut-off lands *inside the read of the image part*: about 26 MB of
 * form fields the reader ignores go first, then an image below the module's own 10 MiB limit, so
 * the reader is still collecting the image when the 30,000,000th byte passes. (An image that is
 * simply oversized would be stopped by the module's own limit first, and the refusal would then
 * arrive while the rest of the body is drained — a different path.) What matters is the answer:
 * `413`, the refusal, and not a `200` for the bytes that did arrive — half an upload would still
 * cost a fal.ai call and a Magic Coin. See `docs/dev/backend/request-size-limits.md`.
 */
internal class GenerationUploadCutOffTest {
    @Test
    fun `an image cut off by the request size limit while it arrives is refused`() =
        testApplication {
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
                    setBody(ByteReadChannel(chunkedUpload()))
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(
                """{"message":"Request body too large","errors":{}}""",
                response.bodyAsText(),
            )
        }

    /**
     * Framed by hand: [IGNORED_FIELDS] form fields of [IGNORED_FIELD_BYTES] bytes each, the
     * `promptId`, and last an `image` of [IMAGE_BYTES] bytes — the whole thing just past the
     * application-wide limit, so the limit is met while the image is being read.
     */
    private fun chunkedUpload(): ByteArray {
        val body = ByteArrayOutputStream()
        val ignoredValue = ByteArray(IGNORED_FIELD_BYTES) { 'x'.code.toByte() }
        repeat(IGNORED_FIELDS) {
            body.write(
                "--$BOUNDARY\r\nContent-Disposition: form-data; name=\"ignored\"\r\n\r\n"
                    .toByteArray()
            )
            body.write(ignoredValue)
            body.write("\r\n".toByteArray())
        }
        body.write(
            "--$BOUNDARY\r\nContent-Disposition: form-data; name=\"$PROMPT_ID_PART_NAME\"\r\n\r\n42\r\n"
                .toByteArray()
        )
        val imageHead = buildString {
            append("--$BOUNDARY\r\n")
            append("Content-Disposition: form-data; name=\"$IMAGE_PART_NAME\"; ")
            append("filename=\"cropped.png\"\r\n")
            append("Content-Type: image/png\r\n\r\n")
        }
        body.write(imageHead.toByteArray())
        body.write(ByteArray(IMAGE_BYTES))
        body.write("\r\n--$BOUNDARY--\r\n".toByteArray())
        check(body.size() > APPLICATION_LIMIT_BYTES) { "the body must pass the limit" }
        // The limiter runs up to about 2 MiB ahead of the parser (its own write buffer plus the
        // channel's flush buffer), so the image has to start well before the limit for the
        // cut-off to be met while the image is read, and not still among the form fields.
        check(APPLICATION_LIMIT_BYTES - (body.size() - IMAGE_BYTES) > MARGIN_BYTES) {
            "the limit must be met well inside the image part"
        }
        return body.toByteArray()
    }

    private companion object {
        const val BOUNDARY = "generator-test-boundary"

        /** The HTTP runtime's limit; the platform keeps the constant to itself. */
        const val APPLICATION_LIMIT_BYTES = 30_000_000

        /** Below Ktor's default form-field size limit, so each field is parsed like any other. */
        const val IGNORED_FIELD_BYTES = 40 * 1024
        const val IGNORED_FIELDS = 628
        const val IMAGE_BYTES = 8 * 1024 * 1024
        const val MARGIN_BYTES = 4 * 1024 * 1024
    }
}
