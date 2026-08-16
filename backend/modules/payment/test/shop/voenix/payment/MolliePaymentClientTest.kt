package shop.voenix.payment

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.slf4j.LoggerFactory
import shop.voenix.order.OrderPaymentStatus
import shop.voenix.payment.PaymentTestSupport.payableOrder

/**
 * What this adapter promises the rest of the module: exactly one request shape goes to Mollie, and
 * everything that can go wrong afterwards comes back as an absent answer — with nothing the
 * provider wrote in the log.
 *
 * Every test drives a [MockEngine], so no test ever reaches the real provider. That is also why the
 * settings carry Mollie's production URL: what these tests pin is the constant a deployment uses,
 * and the engine answers it instead of the internet.
 */
internal class MolliePaymentClientTest {
    @Test
    fun `the create request carries the whole Mollie contract`() = runBlocking {
        var url = ""
        var method = ""
        var authorization = ""
        var idempotencyKey = ""
        var contentType = ""
        var body = ""
        val client = mollieClient { request ->
            url = request.url.toString()
            method = request.method.value
            authorization = request.headers[HttpHeaders.Authorization].orEmpty()
            idempotencyKey = request.headers["Idempotency-Key"].orEmpty()
            contentType = request.body.contentType.toString()
            body = request.body.toByteArray().decodeToString()
            respondPayment(id = "tr_created", status = "open")
        }

        val created = assertNotNull(client.create(payableOrder(orderId = 42), "key-4711"))

        assertEquals("https://api.mollie.com/v2/payments", url)
        assertEquals("POST", method)
        assertEquals("Bearer test_mollie_key", authorization)
        assertEquals("key-4711", idempotencyKey, "every attempt names its own idempotency key")
        assertContains(contentType, "application/json")

        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals(
            setOf(
                "amount",
                "description",
                "redirectUrl",
                "webhookUrl",
                "billingAddress",
                "shippingAddress",
                "metadata",
            ),
            sent.keys,
        )
        assertEquals("EUR", sent.getValue("amount").jsonObject.getValue("currency").text())
        assertEquals("40.70", sent.getValue("amount").jsonObject.getValue("value").text())
        assertEquals("Order #42", sent.getValue("description").text())
        assertEquals(
            "https://voenix.test/checkout/success?orderId=42",
            sent.getValue("redirectUrl").text(),
        )
        assertEquals(WEBHOOK_URL, sent.getValue("webhookUrl").text())
        assertEquals(
            42L,
            sent.getValue("metadata").jsonObject.getValue("orderId").jsonPrimitive.long,
            "the order id travels as metadata, as a number",
        )

        val billing = sent.getValue("billingAddress").jsonObject
        assertEquals("Max", billing.getValue("givenName").text())
        assertEquals("Mustermann", billing.getValue("familyName").text())
        assertEquals("customer@example.com", billing.getValue("email").text())
        assertEquals("+4917623123456", billing.getValue("phone").text())
        assertEquals("Musterstraße 1", billing.getValue("streetAndNumber").text())
        assertEquals("10115", billing.getValue("postalCode").text())
        assertEquals("Berlin", billing.getValue("city").text())
        assertEquals("DE", billing.getValue("country").text())

        val shipping = sent.getValue("shippingAddress").jsonObject
        assertEquals("Erika", shipping.getValue("givenName").text())
        assertEquals("Musterfrau", shipping.getValue("familyName").text())
        assertEquals("Lieferweg 5", shipping.getValue("streetAndNumber").text())
        assertEquals("20095", shipping.getValue("postalCode").text())
        assertEquals("Hamburg", shipping.getValue("city").text())

        assertEquals("tr_created", created.id)
        assertEquals(OrderPaymentStatus.OPEN, created.status)
        assertEquals(4_070, created.amountCents)
        assertEquals("https://checkout.mollie.com/pay/tr_created", created.checkoutUrl)
    }

    /**
     * The one formatting rule money has. On a machine whose default locale writes `40,70`, a
     * rendered amount would go out with a comma and Mollie would refuse the payment — so the amount
     * is *built* from the integer cents rather than formatted.
     */
    @Test
    fun `the amount is an exact two-decimal string under a comma-decimal locale`() = runBlocking {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            listOf(4_070 to "40.70", 1 to "0.01", 100 to "1.00", 123_456 to "1234.56").forEach {
                (cents, expected) ->
                var body = ""
                val client = mollieClient { request ->
                    body = request.body.toByteArray().decodeToString()
                    respondPayment(id = "tr_amount", status = "open")
                }

                client.create(payableOrder(totalCents = cents), "key")

                assertEquals(
                    expected,
                    Json.parseToJsonElement(body)
                        .jsonObject
                        .getValue("amount")
                        .jsonObject
                        .getValue("value")
                        .text(),
                )
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    /**
     * The four legacy cases plus the two ways a number is left out. Nothing here is a Kotlin
     * decision: this is the matrix `PaymentService.NormalizePhoneForMollie` implemented, kept
     * because Mollie rejects a whole payment over one malformed field.
     */
    @Test
    fun `a phone number is normalized to E164 or left out`() = runBlocking {
        listOf(
                Triple("017623123456", "DE", "+4917623123456"),
                Triple("+49 176 / 231-23456", "DE", "+4917623123456"),
                Triple("0612345678", "NL", "+31612345678"),
                Triple("+31612345678", "DE", "+31612345678"),
                Triple("  017623123456  ", "DE", "+4917623123456"),
                Triple("abc", "DE", null),
                Triple("017623123456", "", null),
                Triple("   ", "DE", null),
                Triple(null, "DE", null),
            )
            .forEach { (phone, country, expected) ->
                var body = ""
                val client = mollieClient { request ->
                    body = request.body.toByteArray().decodeToString()
                    respondPayment(id = "tr_phone", status = "open")
                }

                client.create(
                    payableOrder(
                        phone = phone,
                        billingCountry = country,
                        shippingCountry = country,
                    ),
                    "key",
                )

                val addresses =
                    listOf("billingAddress", "shippingAddress").map { name ->
                        Json.parseToJsonElement(body)
                            .jsonObject
                            .getValue(name)
                            .jsonObject["phone"]
                            ?.text()
                    }
                assertEquals(
                    listOf(expected, expected),
                    addresses,
                    "phone '$phone' in country '$country'",
                )
            }
    }

    /**
     * Each address is its own region hint, and a national number proves it: the same digits are a
     * German number under the billing address and a Dutch one under the shipping address. A single
     * shared country would silently send one of the two customers' numbers as somebody else's.
     */
    @Test
    fun `each address parses the phone number in its own country`() = runBlocking {
        var body = ""
        val client = mollieClient { request ->
            body = request.body.toByteArray().decodeToString()
            respondPayment(id = "tr_two_countries", status = "open")
        }

        client.create(
            payableOrder(phone = "0612345678", billingCountry = "DE", shippingCountry = "NL"),
            "key",
        )

        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals(
            "+31612345678",
            sent.getValue("shippingAddress").jsonObject.getValue("phone").text(),
            "the shipping address is Dutch, so its national number is a Dutch one",
        )
        // Whatever the same digits mean in Germany — another number or no valid number at all —
        // they must not come out as the Dutch one: that would be one region hint for both.
        assertNotEquals(
            "+31612345678",
            sent.getValue("billingAddress").jsonObject["phone"]?.text(),
        )
    }

    /**
     * The client's own configuration, pinned where it is observable: the plugin attaches the
     * effective timeouts to every request as a capability. The request read here is one the adapter
     * made through the client it built itself, so what the assertions describe is the deployment's
     * configuration and not a copy of it. Without this, dropping `HttpTimeout` would break nothing
     * any test can see — until a Mollie that stops answering holds a checkout request open forever.
     */
    @Test
    fun `the client Mollie is called through carries the configured timeouts`(): Unit =
        runBlocking {
            var timeouts: HttpTimeoutConfig? = null
            val client = mollieClient { request ->
                timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
                respondPayment(id = "tr_timeout", status = "open")
            }

            client.find("tr_timeout")

            val configured = assertNotNull(timeouts)
            assertEquals(5_000L, configured.connectTimeoutMillis)
            assertEquals(10_000L, configured.requestTimeoutMillis)
            assertEquals(10_000L, configured.socketTimeoutMillis)
        }

    /**
     * Redirects are not followed, and that is a credential rule rather than a preference: every
     * request carries the Mollie API key as a bearer token, and a followed redirect would hand that
     * token to wherever the redirect points. The adapter therefore sees the `302` as the refusal it
     * treats every other unsuccessful status as — one request, an absent payment, and a log line
     * naming the status number and nothing Mollie wrote.
     */
    @Test
    fun `a redirect is not followed and its target never sees the credential`() = runBlocking {
        captureLog { logged ->
            var requests = 0
            val client = mollieClient {
                requests++
                respond(
                    content = PROVIDER_BODY,
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://attacker.test/collect"),
                )
            }

            assertNull(client.find("tr_redirected"))

            assertEquals(1, requests, "a redirect is answered, not walked")
            assertTrue(logged().any { message -> message.contains("302") })
            assertFalse(
                logged().any { message -> message.contains("attacker.test") },
                "where Mollie pointed is provider output: ${logged()}",
            )
            assertNoProviderOutput(logged())
        }
    }

    /**
     * Deviation D19: the order id is appended as a parameter, whatever query the URL already has.
     */
    @Test
    fun `the order id is appended to a redirect URL with and without a query`() = runBlocking {
        listOf(
                "https://voenix.test/checkout/success" to
                    "https://voenix.test/checkout/success?orderId=42",
                "https://voenix.test/checkout/success?lang=de" to
                    "https://voenix.test/checkout/success?lang=de&orderId=42",
            )
            .forEach { (configured, expected) ->
                var body = ""
                val client =
                    mollieClient(settings(redirectUrl = configured)) { request ->
                        body = request.body.toByteArray().decodeToString()
                        respondPayment(id = "tr_redirect", status = "open")
                    }

                client.create(payableOrder(orderId = 42), "key")

                assertEquals(
                    expected,
                    Json.parseToJsonElement(body).jsonObject.getValue("redirectUrl").text(),
                )
            }
    }

    @Test
    fun `a created payment without a checkout link is a failure`() = runBlocking {
        val client = mollieClient {
            respondJson(
                """{"id":"tr_linkless","status":"open","amount":{"currency":"EUR","value":"40.70"}}"""
            )
        }

        assertNull(client.create(payableOrder(), "key"))
    }

    @Test
    fun `a refused create is an absent payment and never logs the provider body`() = runBlocking {
        captureLog { logged ->
            listOf(HttpStatusCode.UnprocessableEntity, HttpStatusCode.BadGateway).forEach { status
                ->
                val client = mollieClient { respondError(status, PROVIDER_BODY) }

                assertNull(client.create(payableOrder(), "key"))
            }

            assertTrue(logged().any { message -> message.contains("${422}") })
            assertNoProviderOutput(logged())
        }
    }

    @Test
    fun `an unreadable answer is an absent payment and never logs what could not be read`() =
        runBlocking {
            captureLog { logged ->
                val client = mollieClient { respondJson("{ this is not json $PROVIDER_BODY") }

                assertNull(client.create(payableOrder(), "key"))

                assertNoProviderOutput(logged())
            }
        }

    /**
     * An answer missing one of the three fields this module needs is unusable, and it must not
     * decode into a plausible-looking payment: an id of `""` or an amount of zero cents would be
     * written down and compared against, and the mismatch would never resolve itself. As a decoding
     * failure it becomes `null`, the webhook answers `502`, and Mollie redelivers.
     */
    @Test
    fun `a truncated answer is an absent payment, whichever required field is missing`() =
        runBlocking {
            captureLog { logged ->
                listOf(
                        """{"status":"open","amount":{"currency":"EUR","value":"40.70"}}""",
                        """{"id":"tr_short","amount":{"currency":"EUR","value":"40.70"}}""",
                        """{"id":"tr_short","status":"open"}""",
                    )
                    .forEach { payload ->
                        assertNull(
                            mollieClient { respondJson(payload) }.find("tr_short"),
                            "a payment without every required field is no payment: $payload",
                        )
                    }

                assertNoProviderOutput(logged())
            }
        }

    /**
     * A status word this backend does not know is an error, and the word itself is provider output:
     * it never reaches a log line, and the webhook answers `502` so Mollie retries once the word is
     * known.
     */
    @Test
    fun `an unknown status is an absent payment and the raw value is in no log line`() =
        runBlocking {
            captureLog { logged ->
                val client = mollieClient { respondPayment(id = "tr_odd", status = "chargedback") }

                assertNull(client.find("tr_odd"))

                assertTrue(logged().any { message -> message.contains("does not know") })
                assertFalse(logged().any { message -> message.contains("chargedback") })
                assertTrue(
                    logged().any { message -> message.contains("tr_odd") },
                    "the line names the payment *this* backend asked about",
                )
            }
        }

    @Test
    fun `an amount Mollie states in something other than whole cents is refused`() = runBlocking {
        val client = mollieClient {
            respondJson(
                """{"id":"tr_odd","status":"paid","amount":{"currency":"EUR","value":"40.7051"}}"""
            )
        }

        assertNull(client.find("tr_odd"))
    }

    /**
     * This whole system is EUR cents (deviation D4). An amount in another currency is a number that
     * means something else, and comparing it with the stored `amount_cents` would be the amount
     * check on `PAID` silently accepting whatever Mollie sent.
     */
    @Test
    fun `an amount in another currency is refused and the currency is in no log line`() =
        runBlocking {
            captureLog { logged ->
                listOf("USD", "eur", " ").forEach { currency ->
                    val client = mollieClient {
                        respondJson(
                            """{"id":"tr_odd","status":"paid",""" +
                                """"amount":{"currency":"$currency","value":"40.70"}}"""
                        )
                    }

                    assertNull(client.find("tr_odd"), "currency '$currency' is not this shop's")
                }

                assertTrue(logged().any { message -> message.contains("unusable amount") })
                assertFalse(
                    logged().any { message -> message.contains("USD") },
                    "the currency Mollie named is provider output: ${logged()}",
                )
            }
        }

    /** A checkout link that is there but empty sends the customer nowhere. */
    @Test
    fun `a blank checkout link is a failed creation`() = runBlocking {
        val client = mollieClient {
            respondJson(
                """{"id":"tr_blank","status":"open","amount":{"currency":"EUR","value":"40.70"},
                   "_links":{"checkout":{"href":"   "}}}"""
            )
        }

        assertNull(client.create(payableOrder(), "key"))
    }

    /**
     * An answer about a different payment is unusable whatever it says: the status write and the
     * amount check are both keyed to the payment that was asked about.
     */
    @Test
    fun `an answer about another payment is refused and its id is in no log line`() = runBlocking {
        captureLog { logged ->
            val client = mollieClient { respondPayment(id = "tr_someone_else", status = "paid") }

            assertNull(client.find("tr_asked_about"))

            assertTrue(logged().any { message -> message.contains("tr_asked_about") })
            assertFalse(logged().any { message -> message.contains("tr_someone_else") })
        }
    }

    @Test
    fun `a timeout and an unreachable provider are an absent payment`() = runBlocking {
        assertNull(
            mollieClient { throw SocketTimeoutException("Read timed out") }
                .create(payableOrder(), "key")
        )
        assertNull(
            mollieClient { throw IOException("Connection reset") }.create(payableOrder(), "key")
        )
        assertNull(mollieClient { throw IOException("Connection reset") }.find("tr_first"))
        assertFalse(
            mollieClient { throw IOException("Connection reset") }.cancel("tr_first"),
            "an unreachable provider is a cancellation that did not happen",
        )
    }

    @Test
    fun `a cancelled request stays cancelled`(): Unit = runBlocking {
        val client = mollieClient { throw CancellationException("The customer left") }

        assertFailsWith<CancellationException> { client.create(payableOrder(), "key") }
    }

    @Test
    fun `a payment is read by its id`() = runBlocking {
        var url = ""
        var method = ""
        val client = mollieClient { request ->
            url = request.url.toString()
            method = request.method.value
            respondPayment(id = "tr_read", status = "paid", value = "40.70")
        }

        val found = assertNotNull(client.find("tr_read"))

        assertEquals("https://api.mollie.com/v2/payments/tr_read", url)
        assertEquals("GET", method)
        assertEquals(OrderPaymentStatus.PAID, found.status)
        assertEquals(4_070, found.amountCents)
    }

    @Test
    fun `a payment is cancelled by its id, and a refusal is only a log line`() = runBlocking {
        var url = ""
        var method = ""
        val cancelling = mollieClient { request ->
            url = request.url.toString()
            method = request.method.value
            respondJson(
                """{"id":"tr_gone","status":"canceled","amount":{"currency":"EUR","value":"40.70"}}"""
            )
        }

        assertTrue(cancelling.cancel("tr_gone"))
        assertEquals("https://api.mollie.com/v2/payments/tr_gone", url)
        assertEquals(HttpMethod.Delete.value, method)

        captureLog { logged ->
            val refusing = mollieClient {
                respondError(HttpStatusCode.UnprocessableEntity, PROVIDER_BODY)
            }

            assertFalse(refusing.cancel("tr_paid"))
            assertNoProviderOutput(logged())
        }
    }

    private fun MockRequestHandleScope.respondPayment(
        id: String,
        status: String,
        value: String = "40.70",
    ): HttpResponseData =
        respondJson(
            """
            {"id":"$id","status":"$status","mode":"test",
             "amount":{"currency":"EUR","value":"$value"},
             "_links":{"self":{"href":"x"},
                       "checkout":{"href":"https://checkout.mollie.com/pay/$id"}}}
            """
        )

    /** An unknown field rides along in every Mollie answer: the adapter must ignore it. */
    private fun MockRequestHandleScope.respondJson(payload: String): HttpResponseData =
        respond(
            content = payload,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    /**
     * The adapter under test, on a [MockEngine] answering [handler] instead of the network.
     *
     * Only the engine is handed in: the adapter builds its own client on top of it, with the
     * configuration a deployment runs. So every request in this file — its timeouts, its redirect
     * rule, the absent content negotiation the adapter serializes around — is a production request
     * that happens to be answered locally.
     */
    private fun mollieClient(
        settings: MollieSettings = settings(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): MolliePaymentClient = MolliePaymentClient(settings, MockEngine(handler))

    private fun settings(redirectUrl: String = "https://voenix.test/checkout/success") =
        MollieSettings(
            apiKey = "test_mollie_key",
            redirectUrl = redirectUrl,
            webhookUrl = WEBHOOK_URL,
            webhookSecret = WEBHOOK_SECRET,
        )

    /**
     * Everything the adapter logs while [read] runs, handed to it as formatted messages.
     *
     * The appender is detached in a `finally`, the way `PaymentServiceTestBase` does it: an
     * appender left on the module logger keeps collecting for every test that runs afterwards.
     */
    private suspend fun captureLog(read: suspend (() -> List<String>) -> Unit) {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(MolliePaymentClient::class.java) as Logger
        logger.addAppender(events)
        try {
            read { events.list.map(ILoggingEvent::getFormattedMessage) }
        } finally {
            logger.detachAppender(events)
        }
    }

    private fun assertNoProviderOutput(messages: List<String>) {
        assertFalse(
            messages.any { message -> message.contains(PROVIDER_BODY) },
            "no provider body, and no decoder message quoting one, may reach a log line",
        )
    }

    private fun kotlinx.serialization.json.JsonElement.text(): String = jsonPrimitive.content

    private companion object {
        const val WEBHOOK_SECRET = "adapter-test-webhook-secret"
        const val WEBHOOK_URL = "https://voenix.test/api/payments/webhook/$WEBHOOK_SECRET"

        /** A marker no log line may ever contain: it stands in for whatever Mollie writes. */
        const val PROVIDER_BODY = "PROVIDER-SECRET-ECHO"
    }
}
