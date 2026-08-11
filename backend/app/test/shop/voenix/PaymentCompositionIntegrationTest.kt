package shop.voenix

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import shop.voenix.auth.AuthRouting
import shop.voenix.payment.MollieSettings
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The two bindings the Payment migration adds, against the composition root itself.
 *
 * They point in opposite directions and one test journey proves both:
 *
 * 1. **payment → order**: a webhook delivery reaches `OrderPaymentGateway.confirm` of the *real*
 *    order module, so the order becomes `PAID` and everything a paid order sets in motion — the
 *    production request and the confirmation mail — is written by that same transaction;
 * 2. **order → payment**: `GET /api/orders/{id}` answers a `paymentStatus`, which it can only do
 *    through `LateBoundPaymentStatus` having been bound to the payment module's status source. An
 *    unbound source fails loudly, so a green assertion here is proof that the `bind` line ran.
 *
 * The second journey exercises the other half of the status source: an order whose webhook never
 * arrived is repaired by the customer looking at it — the detail read refreshes the payment, learns
 * it was paid, and confirms the order through the same path the webhook takes.
 *
 * Mollie itself is a local stub. `MollieSettings.apiUrl` is deliberately not a configuration key,
 * so the composed application is started through the `module(mollie)` seam — the only way to point
 * the real `MolliePaymentClient` somewhere that is not Mollie.
 */
internal class PaymentCompositionIntegrationTest : PostgresIntegrationTest() {
    private val imageRoot: Path = createTempDirectory("payment-composition-test")

    /**
     * Reads and seeds the rows the journey asserts on; the application owns its own connections.
     */
    private var rows: HikariDataSource? = null

    /** The schema the application under test was configured with, and the one the seeds go into. */
    private var schema: String = SCHEMA

    private val mollie = MollieStub()

    @AfterTest
    fun cleanUp() {
        mollie.stop()
        rows?.close()
        imageRoot.toFile().deleteRecursively()
    }

    @Test
    fun `a webhook pays a real order and the order answer carries the payment status`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module(mollieSettings()) }
            startApplication()
            rows = dataSource("payment-composition", SCHEMA)

            val visitor = createClient { install(HttpCookies) }
            val csrf = visitor.get("/api/antiforgery/token").bodyAsText().field("requestToken")
            visitor.uploadPrintImage(csrf)
            val guestToken =
                checkNotNull(singleValue("SELECT guest_session_token FROM $schema.print_images"))

            val orderId = seedOrder(guestToken)
            seedPayment(orderId, "tr_webhook")
            mollie.answerPaid("tr_webhook")

            val delivered =
                visitor.post("/api/payments/webhook/$WEBHOOK_SECRET") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("id=tr_webhook")
                }
            assertEquals(HttpStatusCode.OK, delivered.status)

            assertEquals(
                "PAID",
                singleValue("SELECT status FROM $schema.orders WHERE id = $orderId"),
            )
            assertEquals(
                "1",
                singleValue(
                    "SELECT count(*) FROM $schema.production_requests WHERE order_id = $orderId"
                ),
                "the real order module ran: a paid order is queued for production",
            )
            assertEquals(
                "1",
                singleValue(
                    "SELECT count(*) FROM $schema.email_jobs WHERE source_id = $orderId " +
                        "AND email_kind = 'ORDER_CONFIRMATION'"
                ),
                "and the customer's confirmation mail is queued with it",
            )

            val order = visitor.get("/api/orders/$orderId")
            assertEquals(HttpStatusCode.OK, order.status)
            assertContains(
                order.bodyAsText(),
                "\"paymentStatus\":\"PAID\"",
                message = "the late-bound status source is bound to the payment module",
            )
        }

    @Test
    fun `an order whose webhook never arrived is repaired by reading it`() = testApplication {
        environment { config = applicationConfig(REFRESH_SCHEMA) }
        application { module(mollieSettings()) }
        startApplication()
        rows = dataSource("payment-composition-refresh", REFRESH_SCHEMA)

        val visitor = createClient { install(HttpCookies) }
        val csrf = visitor.get("/api/antiforgery/token").bodyAsText().field("requestToken")
        visitor.uploadPrintImage(csrf)
        val guestToken =
            checkNotNull(singleValue("SELECT guest_session_token FROM $schema.print_images"))

        val orderId = seedOrder(guestToken)
        seedPayment(orderId, "tr_missed")
        mollie.answerPaid("tr_missed")

        val listed = visitor.get("/api/orders")
        assertEquals(HttpStatusCode.OK, listed.status)
        assertContains(
            listed.bodyAsText(),
            "\"paymentStatus\":\"OPEN\"",
            message = "the history answers the stored status and never asks Mollie",
        )
        assertEquals(0, mollie.requests.size)

        val order = visitor.get("/api/orders/$orderId")
        assertEquals(HttpStatusCode.OK, order.status)
        assertContains(order.bodyAsText(), "\"paymentStatus\":\"PAID\"")
        assertContains(
            order.bodyAsText(),
            "\"status\":\"PAID\"",
            message =
                "the repairing read answers one consistent order: the refresh confirmed it, so " +
                    "the order it answers with must not still say PENDING",
        )
        assertEquals(listOf("tr_missed"), mollie.requests, "the detail read refreshed it once")
        assertEquals("PAID", singleValue("SELECT status FROM $schema.orders WHERE id = $orderId"))
        assertEquals(
            "1",
            singleValue(
                "SELECT count(*) FROM $schema.production_requests WHERE order_id = $orderId"
            ),
            "and the refresh confirmed the order through the very same path a webhook takes",
        )
    }

    private suspend fun HttpClient.uploadPrintImage(csrf: String) {
        val uploaded =
            post("/api/cart/images") {
                header(AuthRouting.CSRF_HEADER, csrf)
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                pngBytes(),
                                Headers.build {
                                    append(HttpHeaders.ContentType, "image/png")
                                    append(HttpHeaders.ContentDisposition, "filename=\"print.png\"")
                                },
                            )
                        }
                    )
                )
            }
        assertEquals(HttpStatusCode.Created, uploaded.status)
    }

    /**
     * One pending single-line order of that browser: placing one has no HTTP route in this wave.
     */
    private fun seedOrder(guestToken: String): String {
        val cartId =
            checkNotNull(
                singleValue(
                    "INSERT INTO $schema.carts (guest_session_token, status) " +
                        "VALUES ('$guestToken', 'CHECKED_OUT') RETURNING id"
                )
            )
        val orderId =
            checkNotNull(
                singleValue(
                    "INSERT INTO $schema.orders (cart_id, guest_session_token, status, " +
                        "shipping_first_name, shipping_last_name, shipping_street, " +
                        "shipping_house_number, shipping_postal_code, shipping_city, " +
                        "shipping_country, billing_first_name, billing_last_name, " +
                        "billing_street, billing_house_number, billing_postal_code, " +
                        "billing_city, billing_country, email, subtotal_cents, " +
                        "shipping_cost_cents, discount_cents, total_cents) " +
                        "VALUES ($cartId, '$guestToken', 'PENDING', 'Erika', 'Musterfrau', " +
                        "'Musterstraße', '1', '12345', 'Berlin', 'DE', 'Erika', 'Musterfrau', " +
                        "'Musterstraße', '1', '12345', 'Berlin', 'DE', 'erika@example.com', " +
                        "$AMOUNT_CENTS, 0, 0, $AMOUNT_CENTS) RETURNING id"
                )
            )
        execute(
            "INSERT INTO $schema.order_items (order_id, position, article_id, variant_id, " +
                "article_name, variant_name, quantity, price_cents, prompt_price_cents) " +
                "VALUES ($orderId, 1, 10, 20, 'Zaubertasse', 'Blau', 1, $AMOUNT_CENTS, 0)"
        )
        return orderId
    }

    /** The open payment a checkout would have written; the Wave-3 caller does not exist yet. */
    private fun seedPayment(
        orderId: String,
        molliePaymentId: String,
    ) {
        execute(
            "INSERT INTO $schema.payments (order_id, mollie_payment_id, status, amount_cents, " +
                "checkout_url) VALUES ($orderId, '$molliePaymentId', 'OPEN', $AMOUNT_CENTS, " +
                "'https://checkout.mollie.com/pay/$molliePaymentId')"
        )
    }

    private fun singleValue(sql: String): String? =
        checkNotNull(rows).connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next()) { "No row for $sql" }
                    result.getString(1)
                }
            }
        }

    private fun execute(vararg statements: String) {
        checkNotNull(rows).connection.use { connection ->
            connection.createStatement().use { statement -> statements.forEach(statement::execute) }
        }
    }

    /**
     * The real `MolliePaymentClient` against the stub: everything but [MollieSettings.apiUrl] is
     * what a deployment would carry, because none of it is what this test is about.
     */
    private fun mollieSettings(): MollieSettings =
        MollieSettings(
            apiKey = "test_composition_mollie_key",
            redirectUrl = "http://localhost:5173/checkout/success",
            webhookUrl = "https://voenix.test/api/payments/webhook/$WEBHOOK_SECRET",
            webhookSecret = WEBHOOK_SECRET,
            apiUrl = mollie.url,
        )

    private fun applicationConfig(schema: String = SCHEMA): MapApplicationConfig =
        MapApplicationConfig().apply {
            this@PaymentCompositionIntegrationTest.schema = schema
            put("database.host", postgres.host)
            put("database.port", postgres.firstMappedPort.toString())
            put("database.database", postgres.databaseName)
            put("database.username", postgres.username)
            put("database.password", postgres.password)
            put("database.searchPath", schema)
            put("database.sslMode", "Disable")
            put("database.maximumPoolSize", "2")
            put("auth.sessionSecret", "payment-composition-test-session-secret")
            put("account.frontendBaseUrl", "http://localhost:5173")
            put("generator.dummyMode", "true")
            put("production.artifactRoot", imageRoot.resolve("production-artifacts").toString())
            put("image.publicRoot", imageRoot.resolve("public").toString())
            put("image.privateRoot", imageRoot.resolve("private").toString())
            put("image.cacheRoot", imageRoot.resolve("cache").toString())
            // The Mollie block is read but overridden by the settings the seam hands in; it stays
            // here so the application is configured exactly as a deployment would be.
            put("mollie.apiKey", "test_composition_mollie_key")
            put("mollie.redirectUrl", "http://localhost:5173/checkout/success")
            put("mollie.webhookUrl", "https://voenix.test/api/payments/webhook/$WEBHOOK_SECRET")
            put("mollie.webhookSecret", WEBHOOK_SECRET)
        }

    /** The app module deliberately has no JSON parser on its test classpath; see the cart test. */
    private fun String.field(name: String): String =
        checkNotNull(Regex("\"$name\"\\s*:\\s*\"?([^\",}]+)\"?").find(this)) {
                "No field $name in $this"
            }
            .groupValues[1]

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)
        return bytes.toByteArray()
    }

    /**
     * Mollie, as far as this journey needs it: one `GET /payments/{id}`, answering what a test told
     * it to and recording every id it was asked about.
     */
    private class MollieStub {
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private val paid: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        private val server =
            embeddedServer(Netty, port = 0) {
                    routing {
                        get("/payments/{id}") {
                            val id = checkNotNull(call.parameters["id"])
                            requests += id
                            val status = if (id in paid) "paid" else "open"
                            call.respondText(
                                """{"id":"$id","status":"$status",""" +
                                    """"amount":{"currency":"EUR","value":"$AMOUNT_VALUE"}}""",
                                ContentType.Application.Json,
                            )
                        }
                    }
                }
                .start(wait = false)

        val url: String = "http://localhost:${resolvedPort()}/payments"

        fun answerPaid(molliePaymentId: String) {
            paid += molliePaymentId
        }

        fun stop() {
            server.stop()
        }

        private fun resolvedPort(): Int = runBlocking {
            server.engine.resolvedConnectors().first().port
        }
    }

    private companion object {
        const val SCHEMA = "payment_composition_test"
        const val REFRESH_SCHEMA = "payment_refresh_composition_test"
        const val WEBHOOK_SECRET = "payment-composition-webhook-secret"
        const val AMOUNT_CENTS = 1_490
        const val AMOUNT_VALUE = "14.90"
    }
}
