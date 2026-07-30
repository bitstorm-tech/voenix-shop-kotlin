package shop.voenix.generator

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
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
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.auth.AuthRouting
import shop.voenix.generator.GeneratorTestSupport.StubOperations
import shop.voenix.generator.GeneratorTestSupport.image

/**
 * The one translation this module's HTTP surface performs: an outcome into a status and a body.
 *
 * The operation is a stub, so what the routes decide before any generation runs — the CSRF
 * rejection above all — is a statement this test can actually make.
 */
internal class GeneratorRoutesTest {
    @Test
    fun `a generated image is answered as raw bytes in its own content type`() = testApplication {
        val operations = StubOperations(GenerationOutcome.Generated(image("image/webp", BYTES)))
        application { installGeneratorTestApplication(operations) }
        val client = guestClient()

        val response = client.generate(antiforgeryToken(client))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("image/webp", response.contentType()?.toString())
        assertContentEquals(BYTES, response.bodyAsBytes())
    }

    @Test
    fun `a rejected upload names the field the client has to fix`() = testApplication {
        val operations = StubOperations(GenerationOutcome.Invalid(IMAGE_PART_NAME, "Broken image"))
        application { installGeneratorTestApplication(operations) }
        val client = guestClient()

        val response = client.generate(antiforgeryToken(client))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Validation failed", response.message())
        assertEquals(listOf("Broken image"), response.fieldErrors(IMAGE_PART_NAME))
    }

    @Test
    fun `an empty balance is answered with the code the storefront reads`() = testApplication {
        val operations = StubOperations(GenerationOutcome.InsufficientCoins)
        application { installGeneratorTestApplication(operations) }
        val client = guestClient()

        val response = client.generate(antiforgeryToken(client))

        assertEquals(HttpStatusCode.PaymentRequired, response.status)
        assertEquals("Not enough Magic Coins", response.message())
        assertEquals("INSUFFICIENT_MAGIC_COINS", response.code())
    }

    @Test
    fun `an unusable prompt is a not found`() = testApplication {
        val operations = StubOperations(GenerationOutcome.PromptUnavailable)
        application { installGeneratorTestApplication(operations) }
        val client = guestClient()

        val response = client.generate(antiforgeryToken(client))

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Prompt not found", response.message())
        assertNull(response.code())
    }

    @Test
    fun `a provider failure is a bad gateway, not our own error`() = testApplication {
        val operations = StubOperations(GenerationOutcome.UpstreamFailure)
        application { installGeneratorTestApplication(operations) }
        val client = guestClient()

        val response = client.generate(antiforgeryToken(client))

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("Generator API error", response.message())
    }

    @Test
    fun `an unexpected failure stays an internal error`() = testApplication {
        val operations = StubOperations(GenerationOutcome.UnexpectedFailure)
        application { installGeneratorTestApplication(operations) }
        val client = guestClient()

        val response = client.generate(antiforgeryToken(client))

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("Internal server error", response.message())
    }

    @Test
    fun `a generation without a csrf token never reaches the operation`() = testApplication {
        val operations = StubOperations(GenerationOutcome.Generated(image()))
        application { installGeneratorTestApplication(operations) }
        val client = guestClient()

        val response = client.generate(token = null)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Invalid CSRF token", response.message())
        assertEquals(emptyList(), operations.uploads, "A rejected request generates nothing")
    }

    private fun ApplicationTestBuilder.guestClient(): HttpClient = createClient {
        install(HttpCookies)
    }

    private suspend fun HttpClient.generate(token: String?): HttpResponse =
        post("/api/generator/generate") {
            token?.let { header(AuthRouting.CSRF_HEADER, it) }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            IMAGE_PART_NAME,
                            BYTES,
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/png")
                                append(HttpHeaders.ContentDisposition, "filename=\"cropped.png\"")
                            },
                        )
                        append(PROMPT_ID_PART_NAME, "42")
                    }
                )
            )
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

    private suspend fun HttpResponse.message(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["message"]?.jsonPrimitive?.content

    private suspend fun HttpResponse.code(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content

    private suspend fun HttpResponse.fieldErrors(field: String): List<String> =
        Json.parseToJsonElement(bodyAsText())
            .jsonObject["errors"]
            ?.jsonObject
            ?.get(field)
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()

    private companion object {
        val BYTES = byteArrayOf(4, 8, 15, 16, 23, 42)
    }
}
