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

    /** Reads the rows the sign-in journey asserts on; the application owns its own connections. */
    private var signInRows: HikariDataSource? = null

    @AfterTest
    fun closeSignInRows() {
        signInRows?.close()
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
     * The one client obligation this composition creates. A CSRF token minted while anonymous stops
     * validating the moment the caller has a user session: the token is bound to the user of the
     * session it belongs to, and logging in deliberately does not re-mint the CSRF session. That is
     * token rotation across the authentication boundary and it is correct, so a client has to fetch
     * `/api/antiforgery/token` again after login, before its first mutation. The test pins both
     * halves — the stale token is refused, the re-fetched one works.
     *
     * The e-mail confirmation is set directly in the database instead of over the confirmation
     * link: the composed application has e-mail delivery disabled, and how a link confirms an
     * address is the account module's own contract, not this wiring's.
     */
    @Test
    fun `a customer who signs in re-fetches the CSRF token before mutating their cart`() =
        testApplication {
            environment { config = applicationConfig(SIGN_IN_SCHEMA) }
            application { module() }
            startApplication()
            signInRows = dataSource("cart-sign-in-composition-test", SIGN_IN_SCHEMA)

            val visitor = createClient { install(HttpCookies) }
            val csrf = visitor.get("/api/antiforgery/token").bodyAsText().field("requestToken")
            visitor.uploadPrintImage(csrf)

            assertEquals(
                HttpStatusCode.NoContent,
                visitor
                    .post("/api/auth/register") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"$SIGN_IN_EMAIL","password":"password-1"}""")
                    }
                    .status,
            )
            val userId = singleValue("SELECT id FROM $SIGN_IN_SCHEMA.users").toLong()
            execute("UPDATE $SIGN_IN_SCHEMA.users SET email_confirmed = true WHERE id = $userId")

            val tokenBeforeLogin = visitor.guestToken()
            assertEquals(
                HttpStatusCode.NoContent,
                visitor
                    .post("/api/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"$SIGN_IN_EMAIL","password":"password-1"}""")
                    }
                    .status,
            )
            assertEquals(
                tokenBeforeLogin,
                visitor.guestToken(),
                "the login leaves the guest cookie of the browser exactly as it found it",
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
            assertEquals(
                HttpStatusCode.NotFound,
                visitor
                    .delete("/api/cart/promotion") { header(AuthRouting.CSRF_HEADER, signedInCsrf) }
                    .status,
                "with a token of its own session the request passes CSRF and reaches the cart " +
                    "route, which answers that this customer has no cart to remove one from",
            )

            assertEquals(
                HttpStatusCode.NoContent,
                visitor
                    .post("/api/auth/logout") { header(AuthRouting.CSRF_HEADER, signedInCsrf) }
                    .status,
            )
            assertEquals(
                tokenBeforeLogin,
                visitor.guestToken(),
                "signing out keeps the guest cookie: anonymous continuity is deliberate",
            )
        }

    /**
     * A login changes no cart row (issue #110).
     *
     * The guest cart is written directly, because what fills it is not what this test is about: the
     * point is that registering and signing in — the two moments that used to run the claim — leave
     * the row, its line, and its `updated_at` exactly as they were, and that the browser's
     * unchanged cookie still opens the very same cart afterwards. In between, the signed-in
     * customer has no cart at all, which is the other half of the rule: guest identity and account
     * identity stay separate, and nothing is adopted on the fly either.
     */
    @Test
    fun `signing in leaves the guest cart untouched and reachable under its cookie`() =
        testApplication {
            environment { config = applicationConfig(GUEST_CART_SCHEMA) }
            application { module() }
            startApplication()
            signInRows = dataSource("cart-guest-cart-composition-test", GUEST_CART_SCHEMA)

            val visitor = createClient { install(HttpCookies) }
            val csrf = visitor.get("/api/antiforgery/token").bodyAsText().field("requestToken")
            visitor.uploadPrintImage(csrf)
            val cookie = visitor.guestToken()
            // The cookie is encrypted; the plain token the cart is stored under is the one the
            // upload just wrote next to its own row.
            val guestToken =
                singleValue("SELECT guest_session_token FROM $GUEST_CART_SCHEMA.print_images")
            val cartId = seedGuestCart(guestToken)
            val before = cartRow()

            assertEquals(
                HttpStatusCode.NoContent,
                visitor
                    .post("/api/auth/register") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"$SIGN_IN_EMAIL","password":"password-1"}""")
                    }
                    .status,
            )
            val userId = singleValue("SELECT id FROM $GUEST_CART_SCHEMA.users").toLong()
            execute("UPDATE $GUEST_CART_SCHEMA.users SET email_confirmed = true WHERE id = $userId")
            assertEquals(
                HttpStatusCode.NoContent,
                visitor
                    .post("/api/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"$SIGN_IN_EMAIL","password":"password-1"}""")
                    }
                    .status,
            )

            assertEquals(cookie, visitor.guestToken(), "the cookie survives the login")
            assertEquals(before, cartRow(), "and so does the cart row, down to its updated_at")
            assertEquals(
                "1",
                singleValue("SELECT count(*) FROM $GUEST_CART_SCHEMA.cart_items"),
                "the line stays where the guest put it",
            )

            val signedIn = visitor.get("/api/cart")
            assertEquals(HttpStatusCode.OK, signedIn.status)
            assertTrue(
                signedIn.bodyAsText().contains("\"id\":null"),
                "the customer's own cart is empty: nothing was adopted",
            )

            val signedInCsrf =
                visitor.get("/api/antiforgery/token").bodyAsText().field("requestToken")
            assertEquals(
                HttpStatusCode.NoContent,
                visitor
                    .post("/api/auth/logout") { header(AuthRouting.CSRF_HEADER, signedInCsrf) }
                    .status,
            )
            assertEquals(
                cartId.toString(),
                visitor.get("/api/cart").bodyAsText().field("id"),
                "and the browser reaches its guest cart again through the cookie it kept",
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
     * One active guest cart of [guestToken] with a single line, written straight into the schema.
     *
     * The two article identity rows are all the line's composite foreign key needs; that the
     * catalog does not know the variant only makes the rendered line unavailable, which this test
     * never asserts on.
     */
    private fun seedGuestCart(guestToken: String): Long {
        execute(
            "INSERT INTO $GUEST_CART_SCHEMA.article_identities (id, article_type) " +
                "VALUES ($ARTICLE_ID, 'MUG')"
        )
        execute(
            "INSERT INTO $GUEST_CART_SCHEMA.article_variant_identities " +
                "(id, article_id, article_type) VALUES ($VARIANT_ID, $ARTICLE_ID, 'MUG')"
        )
        execute(
            "INSERT INTO $GUEST_CART_SCHEMA.carts (guest_session_token, status) " +
                "VALUES ('$guestToken', 'ACTIVE')"
        )
        val cartId = singleValue("SELECT id FROM $GUEST_CART_SCHEMA.carts").toLong()
        execute(
            "INSERT INTO $GUEST_CART_SCHEMA.cart_items " +
                "(cart_id, article_id, variant_id, quantity, price_cents, position) " +
                "VALUES ($cartId, $ARTICLE_ID, $VARIANT_ID, 2, 1490, 1)"
        )
        return cartId
    }

    /** The whole cart row as one string, so a single comparison covers every column of it. */
    private fun cartRow(): String =
        singleValue(
            "SELECT id || '|' || coalesce(guest_session_token, '-') || '|' || " +
                "coalesce(user_id::text, '-') || '|' || status || '|' || " +
                "coalesce(promotion_id::text, '-') || '|' || created_at || '|' || updated_at " +
                "FROM $GUEST_CART_SCHEMA.carts"
        )

    private fun singleValue(sql: String): String =
        checkNotNull(signInRows).connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next()) { "No row for $sql" }
                    rows.getString(1)
                }
            }
        }

    private fun execute(sql: String) {
        checkNotNull(signInRows).connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    private fun applicationConfig(schema: String = "cart_composition_test"): MapApplicationConfig =
        MapApplicationConfig().apply {
            put("database.host", postgres.host)
            put("database.port", postgres.firstMappedPort.toString())
            put("database.database", postgres.databaseName)
            put("database.username", postgres.username)
            put("database.password", postgres.password)
            put("database.searchPath", schema)
            put("database.sslMode", "Disable")
            put("database.maximumPoolSize", "2")
            put("auth.sessionSecret", "cart-composition-test-session-secret")
            put("frontend.baseUrl", "http://localhost:5173")
            // Dummy mode is what keeps the composed application away from the image
            // provider; the generator has a composition test of its own.
            put("generator.dummyMode", "true")
            put("production.artifactRoot", imageRoot.resolve("production-artifacts").toString())
            put("image.publicRoot", imageRoot.resolve("public").toString())
            put("image.privateRoot", imageRoot.resolve("private").toString())
            // The composed application is wired to Mollie but never calls it: no test here
            // starts a payment, and the webhook route only needs the secret to reject one.
            put("mollie.apiKey", "test_composition_mollie_key")
            put("mollie.redirectUrl", "http://localhost:5173/checkout/success")
            put(
                "mollie.webhookUrl",
                "https://voenix.test/api/payments/webhook/composition-test-webhook-secret",
            )
            put("mollie.webhookSecret", "composition-test-webhook-secret")
            put("image.cacheRoot", imageRoot.resolve("cache").toString())
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
        /** Every journey owns a schema of its own, so it never sees another test's rows. */
        const val SIGN_IN_SCHEMA = "cart_sign_in_composition_test"
        const val GUEST_CART_SCHEMA = "cart_guest_cart_composition_test"
        const val SIGN_IN_EMAIL = "erika@example.com"
        const val ARTICLE_ID = 10L
        const val VARIANT_ID = 20L
    }
}
