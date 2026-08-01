package shop.voenix.payment

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
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
import shop.voenix.payment.PaymentTestSupport.paymentRequest

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

        val created = assertNotNull(client.create(paymentRequest(orderId = 42), "key-4711"))

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

                client.create(paymentRequest(amountCents = cents), "key")

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
                    paymentRequest(
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

                client.create(paymentRequest(orderId = 42), "key")

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

        assertNull(client.create(paymentRequest(), "key"))
    }

    @Test
    fun `a refused create is an absent payment and never logs the provider body`() = runBlocking {
        val logged = captureLog()
        listOf(HttpStatusCode.UnprocessableEntity, HttpStatusCode.BadGateway).forEach { status ->
            val client = mollieClient { respondError(status, PROVIDER_BODY) }

            assertNull(client.create(paymentRequest(), "key"))
        }

        assertTrue(logged().any { message -> message.contains("${422}") })
        assertNoProviderOutput(logged())
    }

    @Test
    fun `an unreadable answer is an absent payment and never logs what could not be read`() =
        runBlocking {
            val logged = captureLog()
            val client = mollieClient { respondJson("{ this is not json $PROVIDER_BODY") }

            assertNull(client.create(paymentRequest(), "key"))

            assertNoProviderOutput(logged())
        }

    /**
     * A status word this backend does not know is an error, and the word itself is provider output:
     * it never reaches a log line, and the webhook answers `502` so Mollie retries once the word is
     * known.
     */
    @Test
    fun `an unknown status is an absent payment and the raw value is in no log line`() =
        runBlocking {
            val logged = captureLog()
            val client = mollieClient { respondPayment(id = "tr_odd", status = "chargedback") }

            assertNull(client.find("tr_odd"))

            assertTrue(logged().any { message -> message.contains("does not know") })
            assertFalse(logged().any { message -> message.contains("chargedback") })
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

    @Test
    fun `a timeout and an unreachable provider are an absent payment`() = runBlocking {
        assertNull(
            mollieClient { throw SocketTimeoutException("Read timed out") }
                .create(paymentRequest(), "key")
        )
        assertNull(
            mollieClient { throw IOException("Connection reset") }.create(paymentRequest(), "key")
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

        assertFailsWith<CancellationException> { client.create(paymentRequest(), "key") }
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

        val logged = captureLog()
        val refusing = mollieClient {
            respondError(HttpStatusCode.UnprocessableEntity, PROVIDER_BODY)
        }

        assertFalse(refusing.cancel("tr_paid"))
        assertNoProviderOutput(logged())
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
     * The adapter under test, wired to [handler] instead of the network. The client carries no
     * content negotiation, exactly like the production one: the adapter serializes its own request
     * body, so what these tests read off the wire is what a deployment sends.
     */
    private fun mollieClient(
        settings: MollieSettings = settings(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): MolliePaymentClient =
        MolliePaymentClient(
            settings,
            HttpClient(MockEngine) {
                expectSuccess = false
                engine { addHandler(handler) }
            },
        )

    private fun settings(redirectUrl: String = "https://voenix.test/checkout/success") =
        MollieSettings(
            apiKey = "test_mollie_key",
            redirectUrl = redirectUrl,
            webhookUrl = WEBHOOK_URL,
            webhookSecret = "adapter-test-webhook-secret",
        )

    /** Everything the adapter logs while a test runs, read back as formatted messages. */
    private fun captureLog(): () -> List<String> {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(MolliePaymentClient::class.java) as Logger
        logger.addAppender(events)
        return { events.list.map(ILoggingEvent::getFormattedMessage) }
    }

    private fun assertNoProviderOutput(messages: List<String>) {
        assertFalse(
            messages.any { message -> message.contains(PROVIDER_BODY) },
            "no provider body, and no decoder message quoting one, may reach a log line",
        )
    }

    private fun kotlinx.serialization.json.JsonElement.text(): String = jsonPrimitive.content

    private companion object {
        const val WEBHOOK_URL = "https://voenix.test/api/payments/webhook/secret"

        /** A marker no log line may ever contain: it stands in for whatever Mollie writes. */
        const val PROVIDER_BODY = "PROVIDER-SECRET-ECHO"
    }
}
