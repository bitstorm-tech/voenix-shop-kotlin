package shop.voenix.email.delivery

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.email.EmailRecipient
import shop.voenix.email.EmailSettings
import shop.voenix.email.rendering.RenderedEmail

internal class SweegoEmailDeliveryTest {
    @Test
    fun `sends exact transactional contract and drains an arbitrary success body`() = runBlocking {
        var requestUrl = ""
        var apiKey = ""
        var body = ""
        val client = testClient { request ->
            requestUrl = request.url.toString()
            apiKey = request.headers["Api-Key"].orEmpty()
            body = request.body.toByteArray().decodeToString()
            respond("not-json", HttpStatusCode.Accepted)
        }
        val delivery = SweegoEmailDelivery(enabledSettings(), client)

        val result = delivery.deliver(renderedEmail(), "voenix-email-42")

        assertEquals(EmailDeliveryResult.Accepted, result)
        assertEquals("https://api.sweego.io/send", requestUrl)
        assertEquals("secret-key", apiKey)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("email", json.getValue("channel").jsonPrimitive.content)
        assertEquals("sweego", json.getValue("provider").jsonPrimitive.content)
        assertEquals("transac", json.getValue("campaign-type").jsonPrimitive.content)
        assertEquals("voenix-email-42", json.getValue("campaign-id").jsonPrimitive.content)
        assertContains(body, "mail@voenix.shop")
        assertContains(body, "kunde@example.com")
        assertContains(body, "message-html")
        assertContains(body, "message-txt")
    }

    @Test
    fun `classifies provider failure without exposing its response body`() = runBlocking {
        val client = testClient {
            respond(
                content = "recipient@example.com token=secret",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.RetryAfter, "120"),
            )
        }
        val delivery = SweegoEmailDelivery(enabledSettings(), client)

        val result = assertIs<EmailDeliveryResult.Failed>(delivery.deliver(renderedEmail(), null))

        assertEquals("PROVIDER_HTTP_503", result.code)
        assertEquals(120, result.retryAfter?.seconds)
        kotlin.test.assertFalse(result.safeMessage.contains("recipient@example.com"))
        kotlin.test.assertFalse(result.safeMessage.contains("secret"))
    }

    @Test
    fun `ignores invalid retry after`() = runBlocking {
        val client = testClient {
            respond(
                content = "",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.RetryAfter, "not-a-date"),
            )
        }
        val delivery = SweegoEmailDelivery(enabledSettings(), client)

        val result = assertIs<EmailDeliveryResult.Failed>(delivery.deliver(renderedEmail(), null))

        assertNull(result.retryAfter)
    }

    private fun testClient(handler: io.ktor.client.engine.mock.MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            expectSuccess = false
            followRedirects = false
            install(ContentNegotiation) {
                json(
                    Json {
                        encodeDefaults = true
                        explicitNulls = false
                    }
                )
            }
        }

    private fun enabledSettings(): EmailSettings =
        EmailSettings(
            enabled = true,
            apiKey = "secret-key",
            fromEmail = "mail@voenix.shop",
        )

    private fun renderedEmail(): RenderedEmail =
        RenderedEmail(
            recipient = EmailRecipient("kunde@example.com"),
            recipientName = "Max",
            subject = "Betreff",
            html = "<p>Hallo</p>",
            text = "Hallo",
        )
}
