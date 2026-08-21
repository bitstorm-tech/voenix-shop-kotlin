package shop.voenix

import io.ktor.http.ContentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.util.Collections
import javax.sql.DataSource
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleCatalog
import shop.voenix.article.ArticleVariantReference
import shop.voenix.article.CatalogVariant
import shop.voenix.article.PrintAspectRatio
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.installAuthModule
import shop.voenix.email.EmailSettings
import shop.voenix.http.FrontendBaseUrl
import shop.voenix.http.installHttpRuntime
import shop.voenix.image.ImageUpload
import shop.voenix.image.PrivateImageStorage
import shop.voenix.image.StoredPrivateImage
import shop.voenix.operation.OperationResult
import shop.voenix.order.installOrderModule
import shop.voenix.production.ProductionSettings
import shop.voenix.promotion.PromotionCodeResult
import shop.voenix.promotion.PromotionCodes
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The fourth binding of the Order migration, end to end: an enqueued order confirmation is resolved
 * by the order module and delivered by the e-mail worker.
 *
 * The wiring under test is the application's own — `installEmailRuntime`, then `installOrderModule`
 * against the returned outbox and PDF generator, then the two `bind` calls in that order. Only the
 * injection points differ from `Application.install`: settings that point the real Sweego adapter
 * at a local stub server (the send URL is deliberately not configurable), and stand-ins for the
 * three capabilities a confirmation mail never touches.
 */
internal class OrderConfirmationRuntimeIntegrationTest : PostgresIntegrationTest() {
    private val artifactRoot: Path = createTempDirectory("order-confirmation-runtime")

    @AfterTest
    fun cleanUp() {
        artifactRoot.toFile().deleteRecursively()
    }

    @Test
    fun `an enqueued order confirmation is delivered with the values the order stored`() {
        migratedDataSource("order-confirmation-runtime-test").use { dataSource ->
            seedPlacedOrder(dataSource)
            val sweego = SweegoStub()
            try {
                runComposedRuntime(dataSource, sweego)
            } finally {
                sweego.stop()
            }

            assertEquals(
                JobState(sent = true, attempts = 1, errorCode = null),
                jobState(dataSource),
            )
            val request = sweego.requests.single()
            assertContains(request, "kundin@example.com")
            assertContains(request, "Bestellbest")
            assertContains(request, "Zaubertasse")
            assertContains(
                request,
                "$FRONTEND_BASE_URL/order/$ACCESS_TOKEN",
                message = "the delivered mail carries the permanent link the order module built",
            )
        }
    }

    private fun runComposedRuntime(dataSource: DataSource, sweego: SweegoStub) = testApplication {
        application {
            installHttpRuntime()
            val authSettings = AuthSettings("order-confirmation-runtime-session-secret")
            installAuthModule(authSettings)
            val database = Database.connect(dataSource)
            val productionSource = LateBoundProductionSource()
            val emails =
                installEmailRuntime(
                    database,
                    emailSettings(sweego.url),
                    productionSettings(),
                    productionSource,
                )
            val order =
                installOrderModule(
                    database = database,
                    articles = NoArticles,
                    promotions = NoPromotions,
                    productionOutbox = emails.production.outbox,
                    emailOutbox = emails.emailOutbox,
                    frontendBaseUrl = FrontendBaseUrl(FRONTEND_BASE_URL),
                    printImages = NoPrintImages,
                    // Nothing in this journey reads an order over HTTP, so the status source is
                    // left unbound on purpose: a call would fail loudly rather than pass unnoticed.
                    payments = LateBoundPaymentStatus(),
                    productionPdfs = emails.production.pdfGenerator,
                    guestTokens = GuestTokens(authSettings),
                )
            productionSource.bind(order.productionSource)
            emails.bindOrderConfirmations(order.orderConfirmations)
        }
        startApplication()

        // A started attempt is not a finished one: the job is settled once it is sent or carries
        // the reason why it is not.
        var remainingPolls = 200
        while (!jobState(dataSource).settled && remainingPolls > 0) {
            delay(100)
            remainingPolls -= 1
        }
        assertTrue(jobState(dataSource).settled, "the email worker did not finish the job in time")
    }

    private fun emailSettings(sweegoUrl: String): EmailSettings =
        EmailSettings(
            enabled = true,
            pollIntervalMinutes = 1,
            apiKey = "test-key",
            fromEmail = "mail@voenix.shop",
            sendUrl = sweegoUrl,
        )

    private fun productionSettings(): ProductionSettings =
        ProductionSettings.from(
            MapApplicationConfig("production.artifactRoot" to artifactRoot.toString())
        )

    /**
     * One placed order with one line, plus the confirmation job its placement enqueued.
     *
     * The order is `PENDING`, which is the point of the new trigger (issue #110, Joe decision 3):
     * the mail goes out when the order is placed, and nothing here waits for a payment.
     */
    private fun seedPlacedOrder(dataSource: DataSource) {
        execute(
            dataSource,
            "TRUNCATE voenix.email_jobs, voenix.order_items, voenix.orders, voenix.carts " +
                "RESTART IDENTITY CASCADE",
            "INSERT INTO voenix.carts (id, guest_session_token, status) " +
                "VALUES (42, 'guest-42', 'CHECKED_OUT')",
            "INSERT INTO voenix.orders " +
                "(id, cart_id, guest_session_token, access_token, status, shipping_first_name, " +
                "shipping_last_name, shipping_street, shipping_house_number, " +
                "shipping_postal_code, shipping_city, shipping_country, billing_first_name, " +
                "billing_last_name, billing_street, billing_house_number, billing_postal_code, " +
                "billing_city, billing_country, email, subtotal_cents, shipping_cost_cents, " +
                "discount_cents, total_cents) " +
                "VALUES (42, 42, 'guest-42', " +
                "'$ACCESS_TOKEN', " +
                "'PENDING', " +
                "'Erika', 'Musterfrau', 'Musterstraße', '1', " +
                "'12345', 'Berlin', 'DE', 'Erika', 'Musterfrau', 'Musterstraße', '1', '12345', " +
                "'Berlin', 'DE', 'kundin@example.com', 1000, 490, 0, 1490)",
            "INSERT INTO voenix.order_items (order_id, position, article_id, variant_id, " +
                "article_name, variant_name, quantity, price_cents, prompt_price_cents) " +
                "VALUES (42, 1, 10, 20, 'Zaubertasse', 'Blau', 2, 500, 0)",
            "INSERT INTO voenix.email_jobs (email_kind, source_id) VALUES ('ORDER_CONFIRMATION', 42)",
        )
    }

    private fun execute(dataSource: DataSource, vararg statements: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach(statement::executeUpdate)
            }
        }
    }

    private fun jobState(dataSource: DataSource): JobState =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT sent_at IS NOT NULL, attempt_count, last_error_code " +
                        "FROM voenix.email_jobs WHERE email_kind = 'ORDER_CONFIRMATION'"
                )
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "No order confirmation job was found" }
                        JobState(
                            sent = rows.getBoolean(1),
                            attempts = rows.getInt("attempt_count"),
                            errorCode = rows.getString("last_error_code"),
                        )
                    }
                }
        }

    private data class JobState(val sent: Boolean, val attempts: Int, val errorCode: String?) {
        val settled: Boolean
            get() = sent || errorCode != null
    }

    private companion object {
        const val FRONTEND_BASE_URL = "https://shop.example"
        const val ACCESS_TOKEN = "access-token-42xxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    }

    /** A confirmation mail asks none of the three: it is built from stored values alone. */
    private object NoArticles : ArticleCatalog {
        override suspend fun find(
            references: Set<ArticleVariantReference>
        ): Map<ArticleVariantReference, CatalogVariant> = emptyMap()

        override suspend fun printFormats(articleIds: Set<Long>): Map<Long, PrintAspectRatio> =
            error("A confirmation mail never asks for a print format")
    }

    private object NoPromotions : PromotionCodes {
        override suspend fun validate(
            code: String,
            userId: Long?,
            reservationKey: Long?,
        ): PromotionCodeResult = error("A confirmation mail never validates a coupon code")

        override suspend fun reserve(
            promotionId: Long,
            cartId: Long,
            userId: Long?,
        ): PromotionCodeResult = error("A confirmation mail never reserves a promotion")

        override suspend fun release(cartId: Long): Unit =
            error("A confirmation mail never releases a reservation")

        override suspend fun releaseAbandoned(cartId: Long): Unit =
            error("A confirmation mail never releases a reservation")

        override suspend fun redeem(
            promotionId: Long,
            orderId: Long,
            cartId: Long,
            userId: Long?,
        ): PromotionCodeResult = error("A confirmation mail never redeems a promotion")

        override suspend fun find(
            promotionIds: Set<Long>
        ): Map<Long, PromotionCodeResult.Applicable> =
            error("A confirmation mail renders no coupon")
    }

    private object NoPrintImages : PrivateImageStorage {
        override suspend fun store(upload: ImageUpload): OperationResult<StoredPrivateImage> =
            error("A confirmation mail stores no image")

        override suspend fun exists(filename: String): OperationResult<Boolean> =
            error("A confirmation mail checks no image")

        override suspend fun delete(filename: String): OperationResult<Unit> =
            error("A confirmation mail deletes no image")

        override suspend fun originalPaths(
            filenames: Set<String>
        ): OperationResult<Map<String, Path>> = OperationResult.Success(emptyMap())
    }

    /** Records every request body posted to `/send` and answers like an accepting Sweego. */
    private class SweegoStub {
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private val server =
            embeddedServer(Netty, port = 0) {
                    routing {
                        post("/send") {
                            requests += call.receiveText()
                            call.respondText("{}", ContentType.Application.Json)
                        }
                    }
                }
                .start(wait = false)

        val url: String = "http://localhost:${resolvedPort()}/send"

        fun stop() {
            server.stop()
        }

        private fun resolvedPort(): Int = runBlocking {
            server.engine.resolvedConnectors().first().port
        }
    }
}
