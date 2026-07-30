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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.auth.AuthRouting
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
    fun `the image may arrive before or after the prompt id`() = testApplication {
        application { installUploadTestApplication() }
        val client = guestClient()
        val token = antiforgeryToken(client)

        val imageFirst = client.generate(token, imagePart(), promptIdPart("42"))
        val promptIdFirst = client.generate(token, promptIdPart("42"), imagePart())

        listOf(imageFirst, promptIdFirst).forEach { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertContentEquals(BYTES, response.bodyAsBytes())
        }
    }

    @Test
    fun `a request without an image part is rejected on the image field`() = testApplication {
        application { installUploadTestApplication() }
        val client = guestClient()

        val response = client.generate(antiforgeryToken(client), promptIdPart("42"))

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
            )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(IMAGE_PART_NAME, response.rejectedField())
    }

    @Test
    fun `a missing or unreadable prompt id is rejected on the prompt id field`() = testApplication {
        application { installUploadTestApplication() }
        val client = guestClient()
        val token = antiforgeryToken(client)

        val missing = client.generate(token, imagePart())
        val text = client.generate(token, imagePart(), promptIdPart("not-a-number"))

        listOf(missing, text).forEach { response ->
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(PROMPT_ID_PART_NAME, response.rejectedField())
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
            )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(IMAGE_PART_NAME, response.rejectedField())
    }

    /** The real service with a dummy generator: what goes in comes back out, unchanged. */
    private fun Application.installUploadTestApplication() {
        val calls = mutableListOf<String>()
        installGeneratorTestApplication(
            GeneratorService(FakeCoins(calls), FakePrompts(calls), dummyImageGenerator())
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

    private companion object {
        val BYTES = byteArrayOf(4, 8, 15, 16, 23, 42)
    }
}
