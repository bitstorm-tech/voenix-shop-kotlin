package shop.voenix.generator

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormPart
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.auth.AuthRouting
import shop.voenix.generator.GeneratorTestSupport.ARTICLE_ID
import shop.voenix.generator.GeneratorTestSupport.FakeArticles
import shop.voenix.generator.GeneratorTestSupport.FakeCoins
import shop.voenix.generator.GeneratorTestSupport.FakePrompts

/**
 * What the multipart reader makes of a request body, proven through the answer the endpoint gives.
 *
 * These tests run against a real service with a dummy generator, because the point is the whole
 * path from the wire to the response: a part the reader never found and a part it refused have to
 * reach the client as the field it has to fix.
 */
internal class GenerationUploadTest {
    @Test
    fun `the image may arrive before or after the id fields`() = testApplication {
        application { installUploadTestApplication() }
        val client = guestClient()
        val token = antiforgeryToken(client)

        val imageFirst = client.generate(token, imagePart(), promptIdPart("42"), articleIdPart())
        val idsFirst = client.generate(token, articleIdPart(), promptIdPart("42"), imagePart())

        listOf(imageFirst, idsFirst).forEach { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertContentEquals(BYTES, response.bodyAsBytes())
        }
    }

    @Test
    fun `a request without an image part is rejected on the image field`() = testApplication {
        application { installUploadTestApplication() }
        val client = guestClient()

        val response =
            client.generate(antiforgeryToken(client), promptIdPart("42"), articleIdPart())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(IMAGE_PART_NAME, response.rejectedField())
    }

    @Test
    fun `an image part without a single byte counts as no image at all`() = testApplication {
        application { installUploadTestApplication() }
        val client = guestClient()

        val response =
            client.generate(
                antiforgeryToken(client),
                imagePart(bytes = ByteArray(0)),
                promptIdPart("42"),
                articleIdPart(),
            )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(IMAGE_PART_NAME, response.rejectedField())
    }

    @Test
    fun `a missing or unreadable prompt id is rejected on the prompt id field`() = testApplication {
        application { installUploadTestApplication() }
        val client = guestClient()
        val token = antiforgeryToken(client)

        val missing = client.generate(token, imagePart(), articleIdPart())
        val text =
            client.generate(token, imagePart(), promptIdPart("not-a-number"), articleIdPart())

        listOf(missing, text).forEach { response ->
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(PROMPT_ID_PART_NAME, response.rejectedField())
        }
    }

    /**
     * The article decides the shape of the generated image, so a body without a readable article id
     * is refused exactly like one without a readable prompt id — on its own field, from the same
     * constant the reader looks the part up by.
     */
    @Test
    fun `a missing or unreadable article id is rejected on the article id field`() =
        testApplication {
            application { installUploadTestApplication() }
            val client = guestClient()
            val token = antiforgeryToken(client)

            val missing = client.generate(token, imagePart(), promptIdPart("42"))
            val text =
                client.generate(
                    token,
                    imagePart(),
                    promptIdPart("42"),
                    articleIdPart("not-a-number"),
                )

            listOf(missing, text).forEach { response ->
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals(ARTICLE_ID_PART_NAME, response.rejectedField())
            }
        }

    @Test
    fun `an image one byte past the limit is refused`() = testApplication {
        application { installUploadTestApplication() }
        val client = guestClient()

        val response =
            client.generate(
                antiforgeryToken(client),
                imagePart(bytes = ByteArray(MAX_IMAGE_BYTES + 1)),
                promptIdPart("42"),
                articleIdPart(),
            )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(IMAGE_PART_NAME, response.rejectedField())
    }

    /** The limit is a maximum, not a threshold: the largest allowed image is still generated. */
    @Test
    fun `an image of exactly the limit is accepted`() = testApplication {
        application { installUploadTestApplication() }
        val client = guestClient()

        val response =
            client.generate(
                antiforgeryToken(client),
                imagePart(bytes = ByteArray(MAX_IMAGE_BYTES)),
                promptIdPart("42"),
                articleIdPart(),
            )

        assertEquals(HttpStatusCode.OK, response.status)
    }

    /**
     * Every part within the single-image limit and the body past what one request may move: two
     * images of exactly the allowed size use the request budget up, and one more byte in a third
     * part is one byte too many. Nothing but the second limit stops this, and it stops it on the
     * same field.
     */
    @Test
    fun `parts that add up past the request limit are refused`() = testApplication {
        application { installUploadTestApplication() }
        val client = guestClient()

        val response =
            client.generate(
                antiforgeryToken(client),
                imagePart(bytes = ByteArray(MAX_REQUEST_BYTES / 2)),
                imagePart(bytes = ByteArray(MAX_REQUEST_BYTES / 2)),
                imagePart(bytes = byteArrayOf(1)),
                promptIdPart("42"),
                articleIdPart(),
            )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(IMAGE_PART_NAME, response.rejectedField())
        assertEquals(
            listOf("Image files may carry at most 10 MiB each and 20 MiB per request"),
            response.rejectionMessages(),
            "the message names both limits, because no single image broke the single-image one",
        )
    }

    /** A repeated part is not an error — the last one wins, the way a form parser resolves one. */
    @Test
    fun `the last of a repeated part is the one that counts`() = testApplication {
        application { installUploadTestApplication() }
        val client = guestClient()
        val token = antiforgeryToken(client)

        val image =
            client.generate(
                token,
                imagePart(bytes = byteArrayOf(1, 1)),
                imagePart(bytes = OTHER_BYTES),
                promptIdPart("42"),
                articleIdPart(),
            )
        val promptId =
            client.generate(
                token,
                imagePart(),
                articleIdPart(),
                promptIdPart("42"),
                promptIdPart("not-a-number"),
            )

        assertEquals(HttpStatusCode.OK, image.status)
        assertContentEquals(OTHER_BYTES, image.bodyAsBytes(), "the second image is generated")
        assertEquals(HttpStatusCode.BadRequest, promptId.status)
        assertEquals(
            PROMPT_ID_PART_NAME,
            promptId.rejectedField(),
            "the second prompt id is the one that has to be readable",
        )
    }

    /** The real service with a dummy generator: what goes in comes back out, unchanged. */
    private fun Application.installUploadTestApplication() {
        val calls = mutableListOf<String>()
        installGeneratorTestApplication(
            GeneratorService(
                FakeCoins(calls),
                FakeArticles(calls),
                FakePrompts(calls),
                dummyImageGenerator(),
            )
        )
    }

    private fun ApplicationTestBuilder.guestClient(): HttpClient = createClient {
        install(HttpCookies)
    }

    private fun imagePart(
        bytes: ByteArray = BYTES,
        contentType: String = "image/png",
    ): FormPart<ByteArray> =
        FormPart(
            IMAGE_PART_NAME,
            bytes,
            Headers.build {
                append(HttpHeaders.ContentType, contentType)
                append(HttpHeaders.ContentDisposition, "filename=\"cropped.png\"")
            },
        )

    private fun promptIdPart(value: String): FormPart<String> =
        FormPart(PROMPT_ID_PART_NAME, value, Headers.Empty)

    private fun articleIdPart(value: String = ARTICLE_ID.toString()): FormPart<String> =
        FormPart(ARTICLE_ID_PART_NAME, value, Headers.Empty)

    private suspend fun HttpClient.generate(
        token: String,
        vararg parts: FormPart<*>,
    ): HttpResponse =
        post("/api/generator/generate") {
            header(AuthRouting.CSRF_HEADER, token)
            setBody(MultiPartFormDataContent(formData(*parts)))
        }

    private suspend fun antiforgeryToken(client: HttpClient): String {
        val response = client.get("/api/antiforgery/token")
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("requestToken")
            .jsonPrimitive
            .content
    }

    /** The single field an `ApiError` blamed, so a test can assert which part was refused. */
    private suspend fun HttpResponse.rejectedField(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["errors"]?.jsonObject?.keys?.singleOrNull()

    /** The texts under the single rejected field, so a test can pin the rule the client is told. */
    private suspend fun HttpResponse.rejectionMessages(): List<String> =
        Json.parseToJsonElement(bodyAsText())
            .jsonObject["errors"]
            ?.jsonObject
            ?.values
            ?.singleOrNull()
            ?.jsonArray
            ?.map { message -> message.jsonPrimitive.content }
            .orEmpty()

    private companion object {
        val BYTES = byteArrayOf(4, 8, 15, 16, 23, 42)
        val OTHER_BYTES = byteArrayOf(7, 7, 7)
    }
}
