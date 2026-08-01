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
import io.ktor.server.testing.testApplication
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import shop.voenix.auth.AuthRouting
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Proves the four ports the Order migration closes, against the composition root itself.
 *
 * Three of them are visible in the running application and are asserted here: production reaches
 * the real order data through the late-bound source, the account module's claim moves order rows by
 * guest token *and* by confirmed e-mail, and the cart's reorder route reads real ordered lines
 * through the exported reader. The fourth — the mail resolver — needs an e-mail provider and is
 * proven in [OrderConfirmationRuntimeIntegrationTest], which runs the same wiring against a stub
 * server.
 */
internal class OrderCompositionIntegrationTest : PostgresIntegrationTest() {
    private val imageRoot: Path = createTempDirectory("order-composition-test")

    /**
     * Reads and seeds the rows the journey asserts on; the application owns its own connections.
     */
    private var rows: HikariDataSource? = null

    /** The schema the application under test was configured with, and the one the seeds go into. */
    private var schema: String = SCHEMA

    @AfterTest
    fun cleanUp() {
        rows?.close()
        imageRoot.toFile().deleteRecursively()
    }

    /**
     * An admin download reaches the stored order through the composed production source.
     *
     * The order's article is not in the catalog, so the live supplier resolution finds nothing and
     * production answers "this order carries data no document can be laid out from" — which is
     * exactly the proof wanted here: the generator did not fail on an unbound source, it read the
     * real stored items and made a decision about them. An unbound source would have thrown, and an
     * unknown order is a plain `404`.
     */
    @Test
    fun `the composed application produces PDFs from real orders`() = testApplication {
        environment { config = applicationConfig() }
        application { module() }
        startApplication()
        rows = dataSource("order-composition-pdf", SCHEMA)

        val admin = createClient { install(HttpCookies) }
        val orderId = seedOrder(guestToken = "guest-pdf", email = "kundin@example.com")
        admin.signInAsAdmin()

        val pdfs = admin.get("/api/admin/orders/$orderId/production-pdfs")
        assertEquals(HttpStatusCode.Conflict, pdfs.status)
        assertContains(pdfs.bodyAsText(), "PRODUCTION_PDF_INVALID_SOURCE")

        assertEquals(
            HttpStatusCode.NotFound,
            admin.get("/api/admin/orders/999999/production-pdfs").status,
            "an order the source does not know is a miss, never a server error",
        )
    }

    /**
     * The claim port really carries orders now, on both of its handles.
     *
     * The first order belongs to the visitor's browser and is claimed at registration; the second
     * one carries only their address — a different token, so nothing but the confirmed e-mail can
     * find it — and is claimed at the login that follows. That the cart branch ran in the same call
     * is visible in the print image the visitor uploaded before registering.
     */
    @Test
    fun `a signed-in visitor keeps the orders they placed as a guest`() = testApplication {
        environment { config = applicationConfig(CLAIM_SCHEMA) }
        application { module() }
        startApplication()
        rows = dataSource("order-composition-claim", CLAIM_SCHEMA)

        val visitor = createClient { install(HttpCookies) }
        val csrf = visitor.get("/api/antiforgery/token").bodyAsText().field("requestToken")
        val imageId = visitor.uploadPrintImage(csrf)
        val guestToken =
            checkNotNull(singleValue("SELECT guest_session_token FROM $CLAIM_SCHEMA.print_images"))

        val byToken = seedOrder(guestToken = guestToken, email = "someone-else@example.com")
        val byEmail = seedOrder(guestToken = "another-browser", email = "ERIKA@Example.com")

        assertEquals(
            HttpStatusCode.NoContent,
            visitor
                .post("/api/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"$CLAIM_EMAIL","password":"password-1"}""")
                }
                .status,
        )
        val userId = singleValue("SELECT id FROM $CLAIM_SCHEMA.users")

        assertEquals(userId, ownerOf(byToken), "registration claims the orders of the cookie")
        assertNull(
            ownerOf(byEmail),
            "an unconfirmed address must not claim anything at registration",
        )
        assertEquals(
            userId,
            singleValue("SELECT user_id FROM $CLAIM_SCHEMA.print_images WHERE id = $imageId"),
            "and the cart branch of the same claim ran too",
        )

        execute("UPDATE $CLAIM_SCHEMA.users SET email_confirmed = true WHERE id = $userId")
        assertEquals(
            HttpStatusCode.NoContent,
            visitor
                .post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"$CLAIM_EMAIL","password":"password-1"}""")
                }
                .status,
        )

        assertEquals(
            userId,
            ownerOf(byEmail),
            "the confirmed address claims the order placed in another browser",
        )
        assertEquals(
            "2",
            singleValue("SELECT count(*) FROM $CLAIM_SCHEMA.orders WHERE user_id = $userId"),
        )
    }

    /**
     * The cart's reorder route really reaches the order module's reader.
     *
     * The seeded lines carry no print image, so the reorder ends in the conflict that says exactly
     * what is wanted here: the cart *found* a real ordered line of this browser and made a decision
     * about it, which an unbound or wrongly bound reader could never produce. The foreign order
     * proves the other half — the ownership rule of the reader, not of the cart, decides — and it
     * also spares this wiring test a seeded catalog: what happens after the lookup is the cart
     * module's own business and is proven there.
     */
    @Test
    fun `the cart reorders ordered lines through the composed reader`() = testApplication {
        environment { config = applicationConfig(REORDER_SCHEMA) }
        application { module() }
        startApplication()
        rows = dataSource("order-composition-reorder", REORDER_SCHEMA)

        val visitor = createClient { install(HttpCookies) }
        val csrf = visitor.get("/api/antiforgery/token").bodyAsText().field("requestToken")
        visitor.uploadPrintImage(csrf)
        val guestToken =
            checkNotNull(
                singleValue("SELECT guest_session_token FROM $REORDER_SCHEMA.print_images")
            )

        val own = orderItemOf(seedOrder(guestToken = guestToken, email = "erika@example.com"))
        val foreign =
            orderItemOf(seedOrder(guestToken = "another-browser", email = "erika@example.com"))

        val reordered =
            visitor.post("/api/cart/order-items/$own") { header(AuthRouting.CSRF_HEADER, csrf) }
        assertEquals(HttpStatusCode.Conflict, reordered.status)
        assertContains(reordered.bodyAsText(), "ORDER_IMAGE_UNAVAILABLE")

        val strangers =
            visitor.post("/api/cart/order-items/$foreign") { header(AuthRouting.CSRF_HEADER, csrf) }
        assertEquals(
            HttpStatusCode.NotFound,
            strangers.status,
            "another browser's ordered line reads exactly like an unknown one",
        )
    }

    /**
     * An admin session of the composed application.
     *
     * The account is registered over HTTP so the password hash is the real one, and the role and
     * the confirmation are set in the database — how an admin is appointed is not this wiring's
     * question, and the role has to be there before the session that carries it is minted.
     */
    private suspend fun HttpClient.signInAsAdmin() {
        assertEquals(
            HttpStatusCode.NoContent,
            post("/api/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"$ADMIN_EMAIL","password":"password-1"}""")
                }
                .status,
        )
        val adminId = singleValue("SELECT id FROM $SCHEMA.users WHERE email = '$ADMIN_EMAIL'")
        execute(
            "UPDATE $SCHEMA.users SET email_confirmed = true WHERE id = $adminId",
            "INSERT INTO $SCHEMA.user_roles (user_id, role) VALUES ($adminId, 'ADMIN')",
        )
        assertEquals(
            HttpStatusCode.NoContent,
            post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"$ADMIN_EMAIL","password":"password-1"}""")
                }
                .status,
        )
    }

    private suspend fun HttpClient.uploadPrintImage(csrf: String): String {
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
        return uploaded.bodyAsText().field("id")
    }

    /** A paid single-line order, written directly: placing one has no HTTP route in this wave. */
    private fun seedOrder(
        guestToken: String,
        email: String,
    ): String {
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
                        "VALUES ($cartId, '$guestToken', 'PAID', 'Erika', 'Musterfrau', " +
                        "'Musterstraße', '1', '12345', 'Berlin', 'DE', 'Erika', 'Musterfrau', " +
                        "'Musterstraße', '1', '12345', 'Berlin', 'DE', '$email', " +
                        "1000, 490, 0, 1490) RETURNING id"
                )
            )
        execute(
            "INSERT INTO $schema.order_items (order_id, position, article_id, variant_id, " +
                "article_name, variant_name, quantity, price_cents, prompt_price_cents) " +
                "VALUES ($orderId, 1, 10, 20, 'Zaubertasse', 'Blau', 2, 500, 0)"
        )
        return orderId
    }

    private fun orderItemOf(orderId: String): String =
        checkNotNull(singleValue("SELECT id FROM $schema.order_items WHERE order_id = $orderId"))

    private fun ownerOf(orderId: String): String? =
        singleValue("SELECT user_id FROM $schema.orders WHERE id = $orderId")

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

    private fun applicationConfig(schema: String = SCHEMA): MapApplicationConfig =
        MapApplicationConfig().apply {
            this@OrderCompositionIntegrationTest.schema = schema
            put("Database.Host", postgres.host)
            put("Database.Port", postgres.firstMappedPort.toString())
            put("Database.Database", postgres.databaseName)
            put("Database.Username", postgres.username)
            put("Database.Password", postgres.password)
            put("Database.SearchPath", schema)
            put("Database.SslMode", "Disable")
            put("Database.MaximumPoolSize", "2")
            put("Auth.SessionSecret", "order-composition-test-session-secret")
            put("Account.FrontendBaseUrl", "http://localhost:5173")
            put("Generator.DummyMode", "true")
            put("Production.ArtifactRoot", imageRoot.resolve("production-artifacts").toString())
            put("Image.PublicRoot", imageRoot.resolve("public").toString())
            put("Image.PrivateRoot", imageRoot.resolve("private").toString())
            // The composed application is wired to Mollie but never calls it: no test here
            // starts a payment, and the webhook route only needs the secret to reject one.
            put("Mollie.ApiKey", "test_composition_mollie_key")
            put("Mollie.RedirectUrl", "http://localhost:5173/checkout/success")
            put("Mollie.WebhookUrl", "https://voenix.test/api/payments/webhook/secret")
            put("Mollie.WebhookSecret", "composition-test-webhook-secret")
            put("Image.CacheRoot", imageRoot.resolve("cache").toString())
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

    private companion object {
        const val SCHEMA = "order_composition_test"
        const val CLAIM_SCHEMA = "order_claim_composition_test"
        const val REORDER_SCHEMA = "order_reorder_composition_test"
        const val CLAIM_EMAIL = "erika@example.com"
        const val ADMIN_EMAIL = "admin@example.com"
    }
}
