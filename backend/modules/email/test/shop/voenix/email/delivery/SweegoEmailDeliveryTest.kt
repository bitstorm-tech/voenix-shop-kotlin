package shop.voenix.email.delivery

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
        val delivery = sweegoEmailDelivery { request ->
            requestUrl = request.url.toString()
            apiKey = request.headers["Api-Key"].orEmpty()
            body = request.body.toByteArray().decodeToString()
            respond("not-json", HttpStatusCode.Accepted)
        }

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
        val delivery = sweegoEmailDelivery {
            respond(
                content = "recipient@example.com token=secret",
                status = HttpStatusCode.ServiceUnavailable,
            )
        }

        val result = assertIs<EmailDeliveryResult.Failed>(delivery.deliver(renderedEmail(), null))

        assertEquals("PROVIDER_HTTP_503", result.code)
    }

    /**
     * The timeouts are the adapter's own, not the test's: the delivery builds its client around the
     * `MockEngine` handed in, so what a request carries here is what a deployment sends.
     */
    @Test
    fun `the client Sweego is called through carries the configured timeouts`() = runBlocking {
        var timeouts: HttpTimeoutConfig? = null
        val delivery = sweegoEmailDelivery { request ->
            timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
            respond("", HttpStatusCode.Accepted)
        }

        delivery.deliver(renderedEmail(), null)

        val configured = assertNotNull(timeouts)
        assertEquals(10_000L, configured.connectTimeoutMillis)
        assertEquals(30_000L, configured.requestTimeoutMillis)
        assertEquals(30_000L, configured.socketTimeoutMillis)
    }

    /**
     * Sweego's send endpoint never answers with a redirect, so a `302` is a refusal to be reported
     * like every other unsuccessful status — not a route to be walked. Walking it would replay the
     * whole message, the API key header included, against a URL this adapter never chose.
     *
     * What this pins is the reported outcome — one request, `PROVIDER_HTTP_302` — not the
     * `followRedirects` flag itself: Ktor never walks a redirect on a `POST` whatever the flag
     * says, so the flag is unobservable here and stays a second lock (see `configureSweegoClient`).
     */
    @Test
    fun `a redirect is answered as a provider failure and never walked`() = runBlocking {
        var requests = 0
        val delivery = sweegoEmailDelivery {
            requests++
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://attacker.test/collect"),
            )
        }

        val result = assertIs<EmailDeliveryResult.Failed>(delivery.deliver(renderedEmail(), null))

        assertEquals("PROVIDER_HTTP_302", result.code)
        assertEquals(1, requests, "a redirect is answered, not walked")
    }

    /**
     * The adapter under test, answering out of [handler] instead of out of the network. Only the
     * engine is the test's; the client around it is built by the adapter itself, so these tests
     * drive the very configuration a deployment runs.
     */
    private fun sweegoEmailDelivery(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): SweegoEmailDelivery = SweegoEmailDelivery(enabledSettings(), MockEngine(handler))

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
