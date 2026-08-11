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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application as KtorApplication
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import shop.voenix.auth.AuthRouting
import shop.voenix.payment.MollieSettings
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The composition test seam: the whole application, with the payment module pointed at [mollie]
 * instead of at the configured provider.
 *
 * `MollieSettings.apiUrl` is deliberately not a configuration key (deviation D24: a deployment must
 * never be able to send payments somewhere else), so a test that wants the composed application to
 * talk to a local Mollie stub has no way in through the config — and proving that the webhook, the
 * order confirm, and the late-bound status source really are wired together needs exactly that.
 * This function is that one way in.
 *
 * It lives in the test sources on purpose: as a second top-level `module` function in
 * `Application.kt` it once broke the real server start, because Ktor's `EngineMain` resolves the
 * configured `shop.voenix.ApplicationKt.module` by name only and can pick the parameterized
 * candidate. From over here it can never collide — test sources do not reach the production
 * classpath.
 */
internal fun KtorApplication.module(mollie: MollieSettings): Unit =
    Application.install(this, mollie)

/**
 * Everything the three cross-module checkout suites share: the composed application pointed at a
 * Mollie stub, a browser that owns a cart, and the rows to look at afterwards.
 *
 * A checkout is the one journey of this backend that spans five modules — cart, promotion, order,
 * payment, and the checkout itself — and none of the module suites can see whether the composition
 * root wired them into that journey. That is what these tests are for, and it is why they run
 * against the real `module(...)`, over HTTP, on PostgreSQL.
 *
 * The master data is seeded with SQL rather than through the admin routes: an order needs an
 * article variant the catalog knows, and how a mug is created is the article module's own contract.
 * The catalog rows are therefore the smallest ones that resolve — an inactive mug with one variant,
 * so the placement finds its snapshot without a price, a supplier, or a category being part of this
 * test's story.
 */
internal abstract class CheckoutCompositionTestBase(private val schema: String) :
    PostgresIntegrationTest() {
    protected val mollie: CheckoutMollieStub = CheckoutMollieStub()

    private val imageRoot: Path = createTempDirectory("checkout-composition-test")

    private var connections: HikariDataSource? = null

    @AfterTest
    fun cleanUp() {
        mollie.close()
        connections?.close()
        imageRoot.toFile().deleteRecursively()
    }

    /** The configuration a deployment would carry, with this suite's schema and image roots. */
    protected fun applicationConfig(): MapApplicationConfig =
        MapApplicationConfig().apply {
            put("database.host", postgres.host)
            put("database.port", postgres.firstMappedPort.toString())
            put("database.database", postgres.databaseName)
            put("database.username", postgres.username)
            put("database.password", postgres.password)
            put("database.searchPath", schema)
            put("database.sslMode", "Disable")
            // Room for the requests a concurrency journey runs at once: a pool smaller than the
            // callers turns a race into an acquisition timeout that looks like a database failure.
            put("database.maximumPoolSize", "8")
            put("auth.sessionSecret", "checkout-composition-test-session-secret")
            put("frontend.baseUrl", "http://localhost:5173")
            put("generator.dummyMode", "true")
            put("production.artifactRoot", imageRoot.resolve("production-artifacts").toString())
            put("image.publicRoot", imageRoot.resolve("public").toString())
            put("image.privateRoot", imageRoot.resolve("private").toString())
            put("image.cacheRoot", imageRoot.resolve("cache").toString())
            // Read and then overridden by the settings the `module(mollie)` seam hands in, so the
            // application is configured exactly as a deployment would be.
            put("mollie.apiKey", "test_checkout_mollie_key")
            put("mollie.redirectUrl", "http://localhost:5173/checkout/success")
            put("mollie.webhookUrl", "https://voenix.test/api/payments/webhook/$WEBHOOK_SECRET")
            put("mollie.webhookSecret", WEBHOOK_SECRET)
        }

    /** The one value of the first row of [sql]; the row has to exist. */
    protected fun singleValue(sql: String): String? =
        checkNotNull(connections()).connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next()) { "No row for $sql" }
                    result.getString(1)
                }
            }
        }

    protected fun execute(vararg statements: String) {
        checkNotNull(connections()).connection.use { connection ->
            connection.createStatement().use { statement -> statements.forEach(statement::execute) }
        }
    }

    /**
     * The catalog an order can be placed from: one mug identity with one variant identity, and the
     * mug rows behind them.
     *
     * The mug is inactive and priceless on purpose. `ArticleCatalog.find` answers a snapshot for it
     * either way — which is all a placement needs — while an active one would have to carry a
     * price, a category, and its full measurements, none of which any assertion here reads.
     */
    protected fun seedCatalog() {
        execute(
            "INSERT INTO $schema.article_identities (id, article_type) SELECT $ARTICLE_ID, 'MUG' " +
                "WHERE NOT EXISTS (SELECT 1 FROM $schema.article_identities WHERE id = $ARTICLE_ID)",
            "INSERT INTO $schema.article_variant_identities (id, article_id, article_type) " +
                "SELECT $VARIANT_ID, $ARTICLE_ID, 'MUG' WHERE NOT EXISTS " +
                "(SELECT 1 FROM $schema.article_variant_identities WHERE id = $VARIANT_ID)",
            "INSERT INTO $schema.article_mugs (id, position, name, description_short, " +
                "description_long, active) SELECT $ARTICLE_ID, 1, 'Zaubertasse', 'Kurz', " +
                "'Lang', FALSE WHERE NOT EXISTS " +
                "(SELECT 1 FROM $schema.article_mugs WHERE id = $ARTICLE_ID)",
            "INSERT INTO $schema.article_mug_variants (id, article_id, inside_color_code, " +
                "outside_color_code, name, is_default, active) SELECT $VARIANT_ID, $ARTICLE_ID, " +
                "'#FFFFFF', '#0000FF', 'Blau', TRUE, FALSE WHERE NOT EXISTS " +
                "(SELECT 1 FROM $schema.article_mug_variants WHERE id = $VARIANT_ID)",
        )
    }

    /**
     * The identities of a variant the catalog cannot answer for — an article that was deleted while
     * it sat in a cart.
     *
     * Only the two identity rows are written, never the mug behind them: a cart line may reference
     * the identity (its foreign key insists on it), while `ArticleCatalog.find` resolves mugs and
     * therefore answers nothing. That is what makes a placement refuse with
     * `CART_ITEM_UNAVAILABLE`, deterministically and on every retry.
     */
    protected fun seedUnproducibleVariant() {
        execute(
            "INSERT INTO $schema.article_identities (id, article_type) " +
                "SELECT $GHOST_ARTICLE_ID, 'MUG' WHERE NOT EXISTS " +
                "(SELECT 1 FROM $schema.article_identities WHERE id = $GHOST_ARTICLE_ID)",
            "INSERT INTO $schema.article_variant_identities (id, article_id, article_type) " +
                "SELECT $GHOST_VARIANT_ID, $GHOST_ARTICLE_ID, 'MUG' WHERE NOT EXISTS " +
                "(SELECT 1 FROM $schema.article_variant_identities WHERE id = $GHOST_VARIANT_ID)",
        )
    }

    /**
     * A coupon of its own for one journey, so no two tests of a schema can exhaust each other's.
     */
    protected fun seedPromotion(
        code: String,
        percentage: Int = 10,
        usageLimitTotal: Int? = null,
        endsAt: String = "CURRENT_TIMESTAMP + interval '1 day'",
    ): Long =
        checkNotNull(
                singleValue(
                    "INSERT INTO $schema.promotions (name, discount_type, discount_value, " +
                        "coupon_code, coupon_code_normalized, is_active, usage_limit_total, " +
                        "ends_at) VALUES ('$code', 'PERCENTAGE', $percentage, '$code', '$code', " +
                        "TRUE, ${usageLimitTotal ?: "NULL"}, $endsAt) RETURNING id"
                )
            )
            .toLong()

    /**
     * The active cart of [guest], with one line of the seeded variant carrying their print image.
     *
     * Written directly for the same reason the catalog is: what the cart routes do with an added
     * line is the cart module's contract, while every assertion here starts from a cart that is
     * simply *there*.
     */
    protected fun seedCart(
        guest: Guest,
        priceCents: Int = LINE_PRICE_CENTS,
        quantity: Int = 1,
        promotionId: Long? = null,
        articleId: Long = ARTICLE_ID,
        variantId: Long = VARIANT_ID,
    ): Long {
        val cartId =
            checkNotNull(
                    singleValue(
                        "INSERT INTO $schema.carts (guest_session_token, status, promotion_id) " +
                            "VALUES ('${guest.token}', 'ACTIVE', ${promotionId ?: "NULL"}) " +
                            "RETURNING id"
                    )
                )
                .toLong()
        execute(
            "INSERT INTO $schema.cart_items (cart_id, article_id, variant_id, quantity, " +
                "price_cents, prompt_price_cents, print_image_id, position) VALUES " +
                "($cartId, $articleId, $variantId, $quantity, $priceCents, 0, " +
                "${guest.imageId}, 1)"
        )
        return cartId
    }

    /**
     * A browser with a guest cookie, a CSRF token of its own, and one uploaded print image.
     *
     * The upload is what mints the guest token — no read route ever does — and it is also the image
     * the seeded cart line points at, so a placement finds the image the order needs.
     */
    protected suspend fun ApplicationTestBuilder.newGuest(): Guest {
        val client = createClient { install(HttpCookies) }
        val csrf = client.get("/api/antiforgery/token").bodyAsText().field("requestToken")
        val uploaded =
            client.post("/api/cart/images") {
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
        val imageId = uploaded.bodyAsText().field("id").toLong()
        val token =
            checkNotNull(
                singleValue(
                    "SELECT guest_session_token FROM $schema.print_images WHERE id = $imageId"
                )
            )
        return Guest(client, csrf, token, imageId)
    }

    /**
     * The checkout this browser submits: the exact shape the Vue store sends today.
     *
     * [token] is the CSRF token to submit it with, and it is a parameter for one journey only: a
     * login binds the CSRF session to the user, so a browser that signed in between its cart and
     * its checkout has to fetch a token of its own session first — see [currentCsrf].
     */
    protected suspend fun Guest.checkout(
        email: String = "erika@example.com",
        token: String = csrf,
    ): HttpResponse =
        client.post("/api/checkout") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(checkoutBody(email))
        }

    /** A CSRF token of this browser's *current* session, anonymous or signed in. */
    protected suspend fun Guest.currentCsrf(): String =
        client.get("/api/antiforgery/token").bodyAsText().field("requestToken")

    /** The retry of an order's payment (deviation D16); it has no body at all. */
    protected suspend fun Guest.retryPayment(
        orderId: String,
        token: String = csrf,
    ): HttpResponse =
        client.post("/api/checkout/orders/$orderId/payment") {
            header(AuthRouting.CSRF_HEADER, token)
        }

    /** Entering a coupon into this browser's cart — the apply path deviation D5 is about. */
    protected suspend fun Guest.applyPromotion(code: String): HttpResponse =
        client.post("/api/cart/promotion") {
            header(AuthRouting.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody("""{"promotionCode":"$code"}""")
        }

    /** One webhook delivery, exactly as Mollie sends it. */
    protected suspend fun Guest.deliverWebhook(molliePaymentId: String): HttpResponse =
        client.post("/api/payments/webhook/$WEBHOOK_SECRET") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("id=$molliePaymentId")
        }

    private fun connections(): HikariDataSource =
        connections ?: dataSource("checkout-composition-$schema", schema).also { connections = it }

    /** One browser of the composed application, with everything a checkout of it needs. */
    protected class Guest(
        val client: HttpClient,
        val csrf: String,
        val token: String,
        val imageId: Long,
    )

    protected companion object {
        const val WEBHOOK_SECRET: String = "checkout-composition-webhook-secret"
        const val ARTICLE_ID: Long = 4711
        const val VARIANT_ID: Long = 8150

        /** The article and variant [seedUnproducibleVariant] leaves without a mug. */
        const val GHOST_ARTICLE_ID: Long = 4712
        const val GHOST_VARIANT_ID: Long = 8151

        /** One line of this price plus the 490 shipping is the 14.90 EUR every journey pays. */
        const val LINE_PRICE_CENTS: Int = 1_000
        const val SHIPPING_CENTS: Int = 490
        const val TOTAL_CENTS: Int = LINE_PRICE_CENTS + SHIPPING_CENTS
    }
}

/**
 * The value of one flat JSON field. The app module deliberately has no JSON parser on its test
 * classpath — composition is what it tests, not payloads — so the few values these tests read are
 * taken with one expression instead of pulling in a dependency.
 */
internal fun String.field(name: String): String =
    checkNotNull(Regex("\"$name\"\\s*:\\s*\"?([^\",}]+)\"?").find(this)) {
            "No field $name in $this"
        }
        .groupValues[1]

/** The request body the Vue store posts today, `phone: ""` and all (deviations D11 and D12). */
private fun checkoutBody(email: String): String =
    """
    {
      "shippingAddress": {
        "firstName": "Ada", "lastName": "Lovelace",
        "street": "Musterweg", "houseNumber": "12a",
        "postalCode": "80331", "city": "München", "country": "DE",
        "email": "$email", "phone": ""
      },
      "billingAddress": null
    }
    """
        .trimIndent()

private fun pngBytes(): ByteArray {
    val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
    val bytes = ByteArrayOutputStream()
    ImageIO.write(image, "png", bytes)
    return bytes.toByteArray()
}
