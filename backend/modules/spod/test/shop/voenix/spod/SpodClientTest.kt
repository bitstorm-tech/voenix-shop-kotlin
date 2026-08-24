package shop.voenix.spod

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * What this adapter promises the submission stage: exactly five request shapes go out, every one of
 * them carries the destination's token, requests are paced apart, and everything that can come back
 * other than a usable answer is a bounded code — with nothing the partner wrote in the log.
 *
 * Every test drives a [MockEngine], so no test ever reaches the real partner. The access carries
 * the staging environment, which is also what pins the base URL a deployment would use.
 */
internal class SpodClientTest {
    @Test
    fun `the design upload posts the png as multipart with the access token`() = runBlocking {
        var url = ""
        var method = ""
        var token = ""
        var body = ""
        val client = spodClient { request ->
            url = request.url.toString()
            method = request.method.value
            token = request.headers[SpodClient.ACCESS_TOKEN_HEADER].orEmpty()
            body = request.body.toByteArray().decodeToString()
            respondJson("""{"designId":"design-1"}""")
        }

        val result = client.uploadDesign(access(), "ORD-1-JOB-1-1.png", PNG_BYTES)

        assertEquals("design-1", assertIs<SpodResult.Answered<String>>(result).value)
        assertEquals("https://rest.spreadconnect-staging.app/designs/upload", url)
        assertEquals("POST", method)
        assertEquals(ACCESS_TOKEN, token)
        assertContains(body, "filename=\"ORD-1-JOB-1-1.png\"")
        assertContains(body, "image/png")
    }

    /**
     * The partner answers ids as numbers in some fields and as strings in others. A numeric order
     * id must read into the same `String` a quoted one does — a decode failure here would count as
     * an ambiguous creation and quarantine the job after a second orphan, on every order.
     */
    @Test
    fun `an order id answered as a number is read like a quoted one`() = runBlocking {
        val client = spodClient { respondJson("""{"id":12345,"state":"NEW"}""") }

        val result = client.createOrder(access(), sampleRequest())

        assertEquals("12345", assertIs<SpodResult.Answered<String>>(result).value)
    }

    @Test
    fun `the order creation sends the whole contract of this shop`() = runBlocking {
        var url = ""
        var contentType = ""
        var body = ""
        val client = spodClient { request ->
            url = request.url.toString()
            contentType = request.body.contentType.toString()
            body = request.body.toByteArray().decodeToString()
            respondJson("""{"id":"spod-42","state":"NEW"}""")
        }

        val result = client.createOrder(access(), sampleRequest())

        assertEquals("spod-42", assertIs<SpodResult.Answered<String>>(result).value)
        assertEquals("https://rest.spreadconnect-staging.app/orders", url)
        assertContains(contentType, "application/json")

        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals(
            setOf(
                "externalOrderReference",
                "email",
                "phone",
                "shipping",
                "oneTimeItems",
                "orderItems",
                "state",
            ),
            sent.keys,
        )
        assertEquals("ORD-7-JOB-3", sent.getValue("externalOrderReference").jsonPrimitive.content)
        assertEquals(
            "NEW",
            sent.getValue("state").jsonPrimitive.content,
            "a confirmed order is never created in one call — the id must be persisted first",
        )
        assertEquals(
            "STANDARD",
            sent.getValue("shipping").jsonObject.getValue("preferredType").jsonPrimitive.content,
        )
        val item = sent.getValue("oneTimeItems").jsonArray.single().jsonObject
        val configuration = item.getValue("configurations").jsonArray.single().jsonObject
        assertEquals("FRONT", configuration.getValue("view").jsonPrimitive.content)
        assertEquals("MEDIUM_FRONT", configuration.getValue("hotspot").jsonPrimitive.content)
        assertEquals(
            "design-1",
            configuration.getValue("image").jsonObject.getValue("designId").jsonPrimitive.content,
        )
    }

    @Test
    fun `reading and confirming address the order by the partner's id`() = runBlocking {
        val urls = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        val client = spodClient { request ->
            urls += request.url.toString()
            tokens += request.headers[SpodClient.ACCESS_TOKEN_HEADER].orEmpty()
            respondJson("""{"id":"spod-42","state":"NEW"}""")
        }

        val state = client.getOrder(access(), "spod-42")
        val confirmed = client.confirmOrder(access(), "spod-42")

        assertEquals("NEW", assertIs<SpodResult.Answered<String>>(state).value)
        assertIs<SpodResult.Answered<Unit>>(confirmed)
        assertEquals(
            listOf(
                "https://rest.spreadconnect-staging.app/orders/spod-42",
                "https://rest.spreadconnect-staging.app/orders/spod-42/confirm",
            ),
            urls,
        )
        assertEquals(listOf(ACCESS_TOKEN, ACCESS_TOKEN), tokens)
    }

    @Test
    fun `the hotspots of a product type are asked for one design`() = runBlocking {
        var url = ""
        val client = spodClient { request ->
            url = request.url.toString()
            respondJson("""{"hotspots":[{"name":"LEFT_CHEST"},{"name":"MEDIUM_FRONT"}]}""")
        }

        val result = client.availableHotspots(access(), productTypeId = 812, "design-1")

        assertEquals(
            listOf("LEFT_CHEST", "MEDIUM_FRONT"),
            assertIs<SpodResult.Answered<List<String>>>(result).value,
        )
        assertEquals(
            "https://rest.spreadconnect-staging.app/productTypes/812/hotspots/design/design-1",
            url,
        )
    }

    /**
     * The partner allows 60 requests per minute. With a clock and a sleeping function as seams, the
     * pacer's arithmetic is observable exactly: the first request goes out at once, every later one
     * waits out the remainder of the interval since the previous one.
     */
    @Test
    fun `requests are paced at least the minimum interval apart`() = runBlocking {
        val waits = mutableListOf<Long>()
        var now = 1_000L
        val client =
            SpodClient(
                engine = MockEngine { respondJson("""{"id":"spod-1","state":"NEW"}""") },
                nowMillis = { now },
                pause = { millis ->
                    waits += millis
                    now += millis
                },
            )

        client.getOrder(access(), "spod-1")
        client.getOrder(access(), "spod-1")
        now += 50
        client.getOrder(access(), "spod-1")
        now += SpodClient.MIN_REQUEST_INTERVAL_MILLIS * 2
        client.getOrder(access(), "spod-1")

        assertEquals(
            listOf(
                SpodClient.MIN_REQUEST_INTERVAL_MILLIS,
                SpodClient.MIN_REQUEST_INTERVAL_MILLIS - 50,
            ),
            waits,
            "the first request never waits, the later ones wait out the rest of the interval, " +
                "and a caller that was slow anyway waits not at all",
        )
    }

    @Test
    fun `a rate limited answer is the retryable code and not an ambiguity`() = runBlocking {
        val client = spodClient { respondError(HttpStatusCode.TooManyRequests, PROVIDER_BODY) }

        val failure = assertIs<SpodResult.Failed>(client.createOrder(access(), sampleRequest()))

        assertEquals(SpodError.RATE_LIMITED, failure.error)
        assertFalse(failure.ambiguous, "a stated refusal created nothing")
    }

    @Test
    fun `a refusal is known and a server failure is ambiguous`() = runBlocking {
        val refused = spodClient { respondError(HttpStatusCode.BadRequest, PROVIDER_BODY) }
        val broken = spodClient { respondError(HttpStatusCode.BadGateway, PROVIDER_BODY) }

        val refusal = assertIs<SpodResult.Failed>(refused.createOrder(access(), sampleRequest()))
        val outage = assertIs<SpodResult.Failed>(broken.createOrder(access(), sampleRequest()))

        assertEquals(SpodError.REFUSED, refusal.error)
        assertFalse(refusal.ambiguous, "the partner said it created nothing")
        assertEquals(SpodError.PROVIDER_UNAVAILABLE, outage.error)
        assertTrue(outage.ambiguous, "a 5xx after the request went out may have created an order")
    }

    @Test
    fun `an answer that cannot be read is ambiguous and quotes nothing of it`() = runBlocking {
        captureLog { messages ->
            val client = spodClient { respondJson("""{"unexpected":"$PROVIDER_BODY"}""") }

            val failure = assertIs<SpodResult.Failed>(client.createOrder(access(), sampleRequest()))

            assertEquals(SpodError.PROVIDER_ANSWER_UNREADABLE, failure.error)
            assertTrue(failure.ambiguous, "the answer may well have described a created order")
            assertNoSecrets(messages())
        }
    }

    @Test
    fun `neither the token nor the provider body reaches a log line`() = runBlocking {
        captureLog { messages ->
            val client = spodClient { respondError(HttpStatusCode.Forbidden, PROVIDER_BODY) }

            client.uploadDesign(access(), "design.png", PNG_BYTES)
            client.createOrder(access(), sampleRequest())
            client.confirmOrder(access(), "spod-42")

            val logged = messages()
            assertTrue(logged.isNotEmpty(), "the refusals are logged at all")
            assertTrue(
                logged.any { message -> message.contains("403") },
                "the status number is this adapter's own observation and may be logged",
            )
            assertNoSecrets(logged)
        }
    }

    private fun spodClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): SpodClient = SpodClient(engine = MockEngine(handler), nowMillis = { 0 }, pause = {})

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(
            content = body,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    private fun access(): SpodAccess =
        SpodAccess(
            destinationId = 5,
            environment = SpodEnvironment.STAGING,
            accessToken = ACCESS_TOKEN,
            timeoutSeconds = 30,
        )

    private fun sampleRequest(): SpodOrderRequest =
        SpodOrderRequest(
            externalOrderReference = "ORD-7-JOB-3",
            email = "kundin@example.com",
            phone = "+49301234567",
            shipping =
                SpodShipping(
                    address =
                        SpodAddress(
                            firstName = "Erika",
                            lastName = "Musterfrau",
                            street = "Musterstraße 1",
                            city = "Berlin",
                            country = "DE",
                            zipCode = "12345",
                        )
                ),
            oneTimeItems =
                listOf(
                    SpodOneTimeItem(
                        productTypeId = 812,
                        quantityItems =
                            listOf(SpodQuantityItem(quantity = 2, sizeId = 3, appearanceId = 4)),
                        configurations =
                            listOf(
                                SpodConfiguration(
                                    image = SpodConfigurationImage(designId = "design-1"),
                                    view = "FRONT",
                                    hotspot = "MEDIUM_FRONT",
                                )
                            ),
                    )
                ),
        )

    /**
     * Everything the adapter logs while [read] runs, handed to it as formatted messages. The
     * appender is detached in a `finally`: one left on the module logger keeps collecting for every
     * test that runs afterwards.
     */
    private suspend fun captureLog(read: suspend (() -> List<String>) -> Unit) {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(SpodClient::class.java) as Logger
        logger.addAppender(events)
        try {
            read { events.list.map(ILoggingEvent::getFormattedMessage) }
        } finally {
            logger.detachAppender(events)
        }
    }

    private fun assertNoSecrets(messages: List<String>) {
        assertFalse(
            messages.any { message -> message.contains(PROVIDER_BODY) },
            "no provider body, and no decoder message quoting one, may reach a log line",
        )
        assertFalse(
            messages.any { message -> message.contains(ACCESS_TOKEN) },
            "the access token may never reach a log line",
        )
        assertFalse(
            messages.any { message -> message.contains("spreadconnect") },
            "not even the request URL is logged",
        )
    }

    private companion object {
        const val ACCESS_TOKEN = "spod-access-token-must-never-be-logged"

        /** A marker no log line may ever contain: it stands in for whatever the partner writes. */
        const val PROVIDER_BODY = "PROVIDER-SECRET-ECHO"

        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    }
}
