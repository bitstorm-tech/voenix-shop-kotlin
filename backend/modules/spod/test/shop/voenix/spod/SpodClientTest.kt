package shop.voenix.spod

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
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
 * What this adapter promises the submission stage and the catalog sync: exactly the request shapes
 * below go out, every API one of them carries the destination's token, API requests are paced
 * apart, and everything that can come back other than a usable answer is a bounded code — with
 * nothing the partner wrote in the log.
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

    /**
     * The client itself does not page — the sync does, because only it can tell a complete listing
     * from a broken-off one. What the client owes it is the plain wire shape: `limit` and `offset`
     * as query parameters, and the total `count` read back off every page.
     */
    @Test
    fun `the article listing pages with limit and offset until the count is reached`() =
        runBlocking {
            val urls = mutableListOf<String>()
            val pages = ArrayDeque(listOf(ARTICLES_PAGE_1, ARTICLES_PAGE_2))
            val client = spodClient { request ->
                urls += request.url.toString()
                respondJson(pages.removeFirst())
            }

            val seen = mutableListOf<SpodCatalogArticle>()
            var page = client.articlePage(access(), limit = 2, offset = 0)
            seen += page.items
            while (seen.size < checkNotNull(page.count)) {
                page = client.articlePage(access(), limit = 2, offset = seen.size)
                seen += page.items
            }

            assertEquals(listOf("11", "12", "13"), seen.map(SpodCatalogArticle::id))
            assertEquals(
                listOf(
                    "https://rest.spreadconnect-staging.app/articles?limit=2&offset=0",
                    "https://rest.spreadconnect-staging.app/articles?limit=2&offset=2",
                ),
                urls,
            )
            assertTrue(pages.isEmpty(), "the second page was asked for exactly once")

            val variant = seen.first().variants.single()
            assertEquals(812, variant.productTypeId)
            assertEquals("#0a0b0c", parseColorHex(variant.appearanceColorValue))
            assertEquals("front", seen.first().images.single().perspective)
        }

    /**
     * The partner answers ids as numbers in some fields and as strings in others, and the same
     * article must be one article either way — otherwise a re-sync would create a second row for
     * every shirt.
     */
    @Test
    fun `article and variant ids answered as numbers read like quoted ones`() = runBlocking {
        val numeric = spodClient { respondJson(articlesPage(articleId = "42", variantId = "7")) }
        val quoted = spodClient {
            respondJson(articlesPage(articleId = "\"42\"", variantId = "\"7\""))
        }

        val fromNumbers = numeric.articlePage(access(), limit = 1, offset = 0)
        val fromStrings = quoted.articlePage(access(), limit = 1, offset = 0)

        assertEquals("42", fromNumbers.items.single().id)
        assertEquals("7", fromNumbers.items.single().variants.single().id)
        assertEquals(fromNumbers, fromStrings)
    }

    @Test
    fun `the size chart is asked per product type`() = runBlocking {
        var url = ""
        val client = spodClient { request ->
            url = request.url.toString()
            respondJson("""{"sizeImageUrl":"https://image.cdn.example/chart.png"}""")
        }

        val result = client.sizeChart(access(), productTypeId = 812)

        assertEquals(
            "https://image.cdn.example/chart.png",
            assertIs<SpodResult.Answered<SpodSizeChart>>(result).value.sizeImageUrl,
        )
        assertEquals("https://rest.spreadconnect-staging.app/productTypes/812/size-chart", url)
    }

    /**
     * The download is the documented exception to "the base URL comes from the environment": the
     * URL is the partner's, so the call is bounded instead — and the token stays home, because a
     * host this adapter never chose must not be handed the key to the merchant's account.
     */
    @Test
    fun `an image download sends no token and answers the bytes with their type`() = runBlocking {
        var url = ""
        var token: String? = "not asked yet"
        val client = spodClient { request ->
            url = request.url.toString()
            token = request.headers[SpodClient.ACCESS_TOKEN_HEADER]
            respondImage(PNG_BYTES, "image/png")
        }

        val result = client.download(IMAGE_URL, timeoutSeconds = 30)

        val image = assertIs<SpodResult.Answered<SpodBinary>>(result).value
        assertContentEquals(PNG_BYTES, image.bytes)
        assertEquals("image/png", image.contentType)
        assertEquals(IMAGE_URL, url)
        assertEquals(null, token, "the access token belongs to the API, never to a CDN")
    }

    @Test
    fun `an image download refuses a URL that is not https`() = runBlocking {
        var requests = 0
        val client = spodClient {
            requests++
            respondImage(PNG_BYTES, "image/png")
        }

        val failure =
            assertIs<SpodResult.Failed>(
                client.download("http://image.cdn.example/a.png", timeoutSeconds = 30)
            )

        assertEquals(SpodError.PROVIDER_ANSWER_UNREADABLE, failure.error)
        assertEquals(0, requests, "an unencrypted URL is refused before anything goes out")
    }

    @Test
    fun `an image download refuses an answer that is not an image`() = runBlocking {
        val client = spodClient { respondImage(PROVIDER_BODY.encodeToByteArray(), "text/html") }

        val failure = assertIs<SpodResult.Failed>(client.download(IMAGE_URL, timeoutSeconds = 30))

        assertEquals(SpodError.PROVIDER_ANSWER_UNREADABLE, failure.error)
        assertFalse(failure.ambiguous, "a download that was refused took no effect")
    }

    /** What the answer announces about its size never decides how much of it this shop holds. */
    @Test
    fun `an image download refuses a body over the cap`() = runBlocking {
        val client = spodClient {
            respondImage(ByteArray(SpodClient.MAX_IMAGE_BYTES + 1), "image/png")
        }

        val failure = assertIs<SpodResult.Failed>(client.download(IMAGE_URL, timeoutSeconds = 30))

        assertEquals(SpodError.PROVIDER_ANSWER_UNREADABLE, failure.error)
    }

    /** The 60-per-minute budget is the API's. A CDN image is not one of those 60. */
    @Test
    fun `image downloads are not paced while the catalog calls are`() = runBlocking {
        val waits = mutableListOf<Long>()
        var now = 1_000L
        val client =
            SpodClient(
                engine =
                    MockEngine { request ->
                        if (request.url.host == "image.cdn.example") {
                            respondImage(PNG_BYTES, "image/png")
                        } else {
                            respondJson(ARTICLES_PAGE_2)
                        }
                    },
                nowMillis = { now },
                pause = { millis ->
                    waits += millis
                    now += millis
                },
            )

        client.download(IMAGE_URL, timeoutSeconds = 30)
        client.download(IMAGE_URL, timeoutSeconds = 30)
        assertTrue(waits.isEmpty(), "a download never waits for the API's pacer")

        client.articles(access(), limit = 2, offset = 0)
        client.articles(access(), limit = 2, offset = 2)

        assertEquals(
            listOf(SpodClient.MIN_REQUEST_INTERVAL_MILLIS),
            waits,
            "the catalog calls are paced like every other API call",
        )
    }

    @Test
    fun `the catalog calls log neither the token nor a URL nor a provider body`() = runBlocking {
        captureLog { messages ->
            val refusing = spodClient { respondError(HttpStatusCode.Forbidden, PROVIDER_BODY) }
            refusing.articles(access(), limit = 2, offset = 0)
            refusing.sizeChart(access(), productTypeId = 812)

            val notAnImage = spodClient {
                respondImage(PROVIDER_BODY.encodeToByteArray(), "text/html")
            }
            notAnImage.download(IMAGE_URL, timeoutSeconds = 30)

            val logged = messages()
            assertTrue(
                logged.any { message -> message.contains("403") },
                "the status number is this adapter's own observation and may be logged",
            )
            assertTrue(
                logged.any { message -> message.contains("image.cdn.example") },
                "the host of a refused download is the one part of its URL worth logging",
            )
            assertFalse(
                logged.any { message -> message.contains(IMAGE_PATH) },
                "the path of the image URL is partner input and stays out of the log",
            )
            assertNoSecrets(logged)
        }
    }

    /**
     * A download URL is the partner's, and a Ktor timeout writes the URL it timed out on into its
     * own message. Logging the throwable next to the context would therefore publish the whole
     * signed CDN URL through the stack trace, so a download logs the exception class instead — and
     * the assertion has to inspect the event's throwable, because a formatted message never shows
     * one.
     */
    @Test
    fun `a timed-out download logs the failure without the throwable that carries its URL`() =
        runBlocking {
            captureEvents { events ->
                val client = spodClient { request -> throw HttpRequestTimeoutException(request) }

                val failure =
                    assertIs<SpodResult.Failed>(
                        client.download(SIGNED_IMAGE_URL, timeoutSeconds = 30)
                    )

                assertEquals(SpodError.PROVIDER_UNAVAILABLE, failure.error)
                val logged = events()
                assertTrue(logged.isNotEmpty(), "the failure is logged at all")
                assertTrue(
                    logged.none { event -> event.throwableProxy != null },
                    "a download failure never carries its throwable into the log",
                )
                assertFalse(
                    logged.any { event -> event.formattedMessage.contains(IMAGE_PATH) },
                    "the path of the image URL stays out of the log",
                )
                assertFalse(
                    logged.any { event -> event.formattedMessage.contains(SIGNATURE) },
                    "and so does its query",
                )
            }
        }

    /** The other side of the same rule: an API call times out on a URL this shop chose itself. */
    @Test
    fun `a timed-out API call keeps its throwable`() = runBlocking {
        captureEvents { events ->
            val client = spodClient { request -> throw HttpRequestTimeoutException(request) }

            client.articles(access(), limit = 2, offset = 0)

            assertTrue(events().any { event -> event.throwableProxy != null })
        }
    }

    /** A signed CDN URL carries its secret in the query, which the host must be cut off before. */
    @Test
    fun `a refused download logs the host without the query behind it`() = runBlocking {
        captureLog { messages ->
            val client = spodClient { respondImage(PROVIDER_BODY.encodeToByteArray(), "text/html") }

            client.download(SIGNED_IMAGE_URL, timeoutSeconds = 30)

            val logged = messages()
            assertTrue(
                logged.any { message -> message.contains("image.cdn.example") },
                "the host is the one part of the URL worth logging",
            )
            assertFalse(
                logged.any { message -> message.contains(SIGNATURE) },
                "the query of a signed URL is a secret, not a host",
            )
        }
    }

    /** One page read as its typed answer; a failure here is a broken test fixture, not a case. */
    private suspend fun SpodClient.articlePage(
        access: SpodAccess,
        limit: Int,
        offset: Int,
    ): SpodCatalogPage =
        assertIs<SpodResult.Answered<SpodCatalogPage>>(articles(access, limit, offset)).value

    private fun spodClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): SpodClient = SpodClient(engine = MockEngine(handler), nowMillis = { 0 }, pause = {})

    private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
        respond(
            content = body,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    private fun MockRequestHandleScope.respondImage(
        bytes: ByteArray,
        contentType: String,
    ): HttpResponseData =
        respond(content = bytes, headers = headersOf(HttpHeaders.ContentType, contentType))

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
        captureEvents { events -> read { events().map(ILoggingEvent::getFormattedMessage) } }
    }

    /** The same capture, as the events themselves — the only way to see a logged throwable. */
    private suspend fun captureEvents(read: suspend (() -> List<ILoggingEvent>) -> Unit) {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(SpodClient::class.java) as Logger
        logger.addAppender(events)
        try {
            read { events.list.toList() }
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

        /** A CDN URL of the shape a catalog answer carries; its path may never be logged. */
        const val IMAGE_PATH = "front-view-of-a-shirt.png"

        const val IMAGE_URL = "https://image.cdn.example/$IMAGE_PATH"

        /** The query of a signed CDN URL: partner input, and the part worth keeping secret. */
        const val SIGNATURE = "signature-that-must-never-be-logged"

        const val SIGNED_IMAGE_URL = "$IMAGE_URL?signature=$SIGNATURE"

        /**
         * Two pages of one three-article catalog. The first article carries the whole variant and
         * image shape; the ids are numbers here and quoted in the second page, which is exactly how
         * the partner mixes them.
         */
        val ARTICLES_PAGE_1 =
            """
            {"count":3,"limit":2,"offset":0,"items":[
              {"id":11,"title":"Shirt","description":"A shirt","unknownField":"ignored",
               "variants":[{"id":101,"productTypeId":812,"appearanceId":4,"appearanceName":"Schwarz",
                            "appearanceColorValue":"#0A0B0C","sizeId":3,"sizeName":"M",
                            "sku":"SKU-1","imageIds":[901]}],
               "images":[{"id":901,"appearanceId":4,"perspective":"front",
                          "imageUrl":"https://image.cdn.example/901.png"}]},
              {"id":12}
            ]}
            """
                .trimIndent()

        const val ARTICLES_PAGE_2 = """{"count":3,"limit":2,"offset":2,"items":[{"id":"13"}]}"""

        /** One article whose two ids are written as [articleId] and [variantId] say. */
        fun articlesPage(articleId: String, variantId: String): String =
            """{"count":1,"limit":1,"offset":0,"items":[{"id":$articleId,""" +
                """"variants":[{"id":$variantId,"productTypeId":812}]}]}"""
    }
}
