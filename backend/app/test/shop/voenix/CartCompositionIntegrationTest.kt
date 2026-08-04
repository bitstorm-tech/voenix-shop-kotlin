package shop.voenix

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.delete
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
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import shop.voenix.auth.AuthRouting
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Proves the cart wiring of the real composition root: the cart routes are installed, the
 * guest-capable CSRF protection guards their mutations, the private image storage is bound, and the
 * image module's guest delivery route resolves through the cart's own ownership records.
 *
 * The three catalog capabilities Article, Prompt, and Promotion return are bound here for the first
 * time; that they *compile* into `installCartModule` is what proves it, and this test is what
 * proves the resulting application starts and answers.
 */
internal class CartCompositionIntegrationTest : PostgresIntegrationTest() {
    private val imageRoot: Path = createTempDirectory("cart-composition-test")

    /** Reads the rows the claim journey asserts on; the application owns its own connections. */
    private var claimRows: HikariDataSource? = null

    @AfterTest
    fun closeClaimRows() {
        claimRows?.close()
    }

    @Test
    fun `the composed application serves a cart and the print image uploaded into it`() =
        testApplication {
            environment { config = applicationConfig() }
            application { module() }
            startApplication()

            val guest = createClient { install(HttpCookies) }

            val empty = guest.get("/api/cart")
            assertEquals(HttpStatusCode.OK, empty.status)
            assertEquals("no-store", empty.headers[HttpHeaders.CacheControl])
            assertTrue(empty.bodyAsText().contains("\"id\":null"))
            assertNull(empty.headers[HttpHeaders.SetCookie], "A read must not create a guest")

            // The guest-capable CSRF protection really guards the composed subtree.
            assertEquals(HttpStatusCode.BadRequest, guest.post("/api/cart/images").status)

            val token = guest.get("/api/antiforgery/token").bodyAsText().field("requestToken")

            val uploaded =
                guest.post("/api/cart/images") {
                    header(AuthRouting.CSRF_HEADER, token)
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    "file",
                                    pngBytes(),
                                    Headers.build {
                                        append(HttpHeaders.ContentType, "image/png")
                                        append(
                                            HttpHeaders.ContentDisposition,
                                            "filename=\"print.png\"",
                                        )
                                    },
                                )
                            }
                        )
                    )
                }
            assertEquals(HttpStatusCode.Created, uploaded.status)
            val imageId = uploaded.bodyAsText().field("id")

            val served = guest.get("/api/images/guest/120/$imageId")
            assertEquals(
                HttpStatusCode.OK,
                served.status,
                "The guest route must resolve through the composed cart resolver",
            )

            val stranger = createClient { install(HttpCookies) }
            assertEquals(
                HttpStatusCode.NotFound,
                stranger.get("/api/images/guest/120/$imageId").status,
            )
        }

    /**
     * The claim port really is bound: what a visitor collected before they had an account belongs
     * to the account afterwards — over HTTP, through the real composition, down to the rows — and
     * the signed-in customer can then go on mutating that cart.
     *
     * The mutation carries the one client obligation this composition creates. A CSRF token minted
     * while anonymous stops validating the moment the caller has a user session: the token is bound
     * to the user of the session it belongs to, and logging in deliberately does not re-mint the
     * CSRF session. That is token rotation across the authentication boundary and it is correct, so
     * a client has to fetch `/api/antiforgery/token` again after login, before its first mutation.
     * The test pins both halves — the stale token is refused, the re-fetched one works.
     *
     * The e-mail confirmation is set directly in the database instead of over the confirmation
     * link: the composed application has e-mail delivery disabled, and how a link confirms an
     * address is the account module's own contract, not this wiring's.
     */
    @Test
    fun `the visitor's guest data is claimed and the signed-in customer mutates their cart`() =
        testApplication {
            environment { config = applicationConfig(CLAIM_SCHEMA) }
            application { module() }
            startApplication()
            claimRows = dataSource("cart-claim-composition-test", CLAIM_SCHEMA)

            val visitor = createClient { install(HttpCookies) }
            val csrf = visitor.get("/api/antiforgery/token").bodyAsText().field("requestToken")
            val imageId = visitor.uploadPrintImage(csrf)
            val guestToken =
                singleValue("SELECT guest_session_token FROM $CLAIM_SCHEMA.print_images")
            val cartId = insertGuestCart(guestToken)

            assertEquals(
                HttpStatusCode.NoContent,
                visitor
                    .post("/api/auth/register") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"$CLAIM_EMAIL","password":"password-1"}""")
                    }
                    .status,
            )

            val userId = singleValue("SELECT id FROM $CLAIM_SCHEMA.users").toLong()
            assertEquals(
                userId.toString(),
                singleValue("SELECT user_id FROM $CLAIM_SCHEMA.carts WHERE id = $cartId"),
                "registration claims the cart of the guest token",
            )
            assertEquals(
                userId.toString(),
                singleValue("SELECT user_id FROM $CLAIM_SCHEMA.print_images WHERE id = $imageId"),
                "registration claims the print images of the guest token",
            )

            execute("UPDATE $CLAIM_SCHEMA.users SET email_confirmed = true WHERE id = $userId")
            val tokenBeforeLogin = visitor.guestToken()
            repeat(2) {
                assertEquals(
                    HttpStatusCode.NoContent,
                    visitor
                        .post("/api/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"$CLAIM_EMAIL","password":"password-1"}""")
                        }
                        .status,
                    "a repeated login claims again and stays harmless",
                )
            }
            assertEquals(
                "1",
                singleValue("SELECT count(*) FROM $CLAIM_SCHEMA.carts"),
                "claiming twice never creates a second cart",
            )
            val tokenAfterLogin = visitor.guestToken()
            assertNotEquals(
                tokenBeforeLogin,
                tokenAfterLogin,
                "the login replaces the guest cookie of the browser (issue #77)",
            )

            val cart = visitor.get("/api/cart")
            assertEquals(HttpStatusCode.OK, cart.status)
            assertEquals(
                cartId.toString(),
                cart.bodyAsText().field("id"),
                "the signed-in customer sees the cart they filled as a guest — by user id, " +
                    "although the token that filled it is gone",
            )

            assertEquals(
                HttpStatusCode.BadRequest,
                visitor
                    .delete("/api/cart/promotion") { header(AuthRouting.CSRF_HEADER, csrf) }
                    .status,
                "the token minted before the login no longer belongs to this caller",
            )

            val signedInCsrf =
                visitor.get("/api/antiforgery/token").bodyAsText().field("requestToken")
            val mutated =
                visitor.delete("/api/cart/promotion") {
                    header(AuthRouting.CSRF_HEADER, signedInCsrf)
                }
            assertEquals(
                HttpStatusCode.OK,
                mutated.status,
                "with a token of its own session the signed-in customer may mutate the cart",
            )
            assertEquals(
                cartId.toString(),
                mutated.bodyAsText().field("id"),
                "and the mutation answers with that same cart",
            )

            assertEquals(
                HttpStatusCode.NoContent,
                visitor
                    .post("/api/auth/logout") { header(AuthRouting.CSRF_HEADER, signedInCsrf) }
                    .status,
            )
            assertEquals(
                tokenAfterLogin,
                visitor.guestToken(),
                "signing out keeps the guest cookie: anonymous continuity is deliberate",
            )
            val afterLogout = visitor.get("/api/cart")
            assertEquals(HttpStatusCode.OK, afterLogout.status)
            assertTrue(
                afterLogout.bodyAsText().contains("\"id\":null"),
                "and the browser it leaves behind reaches the customer's cart through nothing",
            )
        }

    /** The plain guest token of this client's `voenix.guest` cookie, as the browser holds it. */
    private suspend fun HttpClient.guestToken(): String =
        cookies("http://localhost/api/cart")
            .single { cookie -> cookie.name == "voenix.guest" }
            .value

    private suspend fun HttpClient.uploadPrintImage(csrf: String): Long {
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
        return uploaded.bodyAsText().field("id").toLong()
    }

    /**
     * An active cart of [guestToken] with no user, written directly: filling it over HTTP would
     * need a seeded article and variant, while the claim only ever looks at the two owner columns.
     */
    private fun insertGuestCart(guestToken: String): Long =
        singleValue(
                "INSERT INTO $CLAIM_SCHEMA.carts (guest_session_token, status) " +
                    "VALUES ('$guestToken', 'ACTIVE') RETURNING id"
            )
            .toLong()

    private fun singleValue(sql: String): String =
        checkNotNull(claimRows).connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next()) { "No row for $sql" }
                    rows.getString(1)
                }
            }
        }

    private fun execute(sql: String) {
        checkNotNull(claimRows).connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    private fun applicationConfig(schema: String = "cart_composition_test"): MapApplicationConfig =
        MapApplicationConfig().apply {
            put("Database.Host", postgres.host)
            put("Database.Port", postgres.firstMappedPort.toString())
            put("Database.Database", postgres.databaseName)
            put("Database.Username", postgres.username)
            put("Database.Password", postgres.password)
            put("Database.SearchPath", schema)
            put("Database.SslMode", "Disable")
            put("Database.MaximumPoolSize", "2")
            put("Auth.SessionSecret", "cart-composition-test-session-secret")
            put("Account.FrontendBaseUrl", "http://localhost:5173")
            // Dummy mode is what keeps the composed application away from the image
            // provider; the generator has a composition test of its own.
            put("Generator.DummyMode", "true")
            put("Production.ArtifactRoot", imageRoot.resolve("production-artifacts").toString())
            put("Image.PublicRoot", imageRoot.resolve("public").toString())
            put("Image.PrivateRoot", imageRoot.resolve("private").toString())
            // The composed application is wired to Mollie but never calls it: no test here
            // starts a payment, and the webhook route only needs the secret to reject one.
            put("Mollie.ApiKey", "test_composition_mollie_key")
            put("Mollie.RedirectUrl", "http://localhost:5173/checkout/success")
            put(
                "Mollie.WebhookUrl",
                "https://voenix.test/api/payments/webhook/composition-test-webhook-secret",
            )
            put("Mollie.WebhookSecret", "composition-test-webhook-secret")
            put("Image.CacheRoot", imageRoot.resolve("cache").toString())
        }

    /**
     * The value of one flat JSON field. The app module deliberately has no JSON parser on its test
     * classpath — composition is what it tests, not payloads — so the two values this test needs
     * are read with one expression instead of pulling in a dependency.
     */
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
        /** The claim journey owns a schema of its own, so it never sees the other test's rows. */
        const val CLAIM_SCHEMA = "cart_claim_composition_test"
        const val CLAIM_EMAIL = "erika@example.com"
    }
}
