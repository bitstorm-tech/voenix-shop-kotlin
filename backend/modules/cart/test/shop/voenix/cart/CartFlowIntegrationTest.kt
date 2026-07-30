package shop.voenix.cart

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.promotion.PromotionCodeResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The cart as a customer's browser sees it: whole journeys over HTTP against real PostgreSQL.
 *
 * The four capabilities the cart consumes are faked, the cart's own storage is not. What this test
 * is here for is the wire: the exact response shape, the statuses, and the `PROMOTION_*` codes a
 * frontend branches on.
 */
internal class CartFlowIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `upload, add, read, update, and remove take a cart through its whole life`() =
        withCart("journey") { fixture ->
            fixture.prompts.prices = mapOf(CartTestSupport.PROMPT_ID to 500)
            val guest = fixture.guestClient()

            val empty = guest.get("/api/cart")
            assertEquals(HttpStatusCode.OK, empty.status)
            assertEquals("no-store", empty.headers[HttpHeaders.CacheControl])
            assertTrue(empty.bodyAsText().contains("\"id\":null"))

            val uploaded = fixture.upload(guest)
            assertEquals(HttpStatusCode.Created, uploaded.status)
            val imageId = uploaded.body().getValue("id").jsonPrimitive.long()

            val added =
                guest.post("/api/cart/items") {
                    header(AuthRouting.CSRF_HEADER, fixture.token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"articleId":${CartTestSupport.ARTICLE_ID},
                         "variantId":${CartTestSupport.VARIANT_ID},
                         "quantity":2,
                         "promptId":${CartTestSupport.PROMPT_ID},
                         "imageId":$imageId}
                        """
                            .trimIndent()
                    )
                }
            assertEquals(HttpStatusCode.OK, added.status)

            val cart = added.body()
            val line = cart.getValue("items").jsonArray.single().jsonObject
            assertEquals(
                CartTestSupport.ARTICLE_ID,
                line.getValue("articleId").jsonPrimitive.long(),
            )
            assertEquals("Classic mug", line.getValue("articleName").jsonPrimitive.content)
            assertEquals("White", line.getValue("variantName").jsonPrimitive.content)
            assertEquals("#ffffff", line.getValue("outsideColorCode").jsonPrimitive.content)
            assertEquals("#ff0000", line.getValue("insideColorCode").jsonPrimitive.content)
            assertEquals(true, line.getValue("available").jsonPrimitive.content.toBoolean())
            assertEquals(1_490, line.getValue("price").jsonPrimitive.int())
            assertEquals(500, line.getValue("promptPrice").jsonPrimitive.int())
            assertEquals(imageId, line.getValue("imageId").jsonPrimitive.long())
            // 2 * (1490 + 500) = 3980, below the free-shipping threshold.
            assertEquals(3_980, cart.getValue("subtotal").jsonPrimitive.int())
            assertEquals(490, cart.getValue("shippingCost").jsonPrimitive.int())
            assertEquals(0, cart.getValue("discountAmount").jsonPrimitive.int())
            assertEquals(4_470, cart.getValue("total").jsonPrimitive.int())
            assertEquals(2, cart.getValue("totalItems").jsonPrimitive.int())

            val itemId = line.getValue("id").jsonPrimitive.long()
            val updated =
                guest.patch("/api/cart/items/$itemId") {
                    header(AuthRouting.CSRF_HEADER, fixture.token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"quantity":1}""")
                }
            assertEquals(HttpStatusCode.OK, updated.status)
            assertEquals(1_990, updated.body().getValue("subtotal").jsonPrimitive.int())

            val removed =
                guest.delete("/api/cart/items/$itemId") {
                    header(AuthRouting.CSRF_HEADER, fixture.token)
                }
            assertEquals(HttpStatusCode.OK, removed.status)
            assertTrue(removed.body().getValue("items").jsonArray.isEmpty())
            assertEquals(0, removed.body().getValue("total").jsonPrimitive.int())

            // The cart itself survives its last line, so the customer keeps their promotion.
            assertEquals(
                1,
                CartTestSupport.count(fixture.dataSource, "SELECT count(*) FROM voenix.carts"),
            )
        }

    @Test
    fun `an unknown line is not found on update and on remove`() =
        withCart("not-found") { fixture ->
            val guest = fixture.guestClient()
            fixture.addOneItem(guest)

            val update =
                guest.patch("/api/cart/items/999999") {
                    header(AuthRouting.CSRF_HEADER, fixture.token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"quantity":1}""")
                }
            assertEquals(HttpStatusCode.NotFound, update.status)

            val remove =
                guest.delete("/api/cart/items/999999") {
                    header(AuthRouting.CSRF_HEADER, fixture.token)
                }
            assertEquals(HttpStatusCode.NotFound, remove.status)
        }

    @Test
    fun `a promotion is applied, rendered, and removed again`() =
        withCart("promotion") { fixture ->
            CartTestSupport.seedPromotion(fixture.dataSource, id = 3, code = "SAVE10")
            fixture.promotions.validations = mapOf("SAVE10" to CartTestSupport.applicable(3))
            fixture.promotions.applicables = mapOf(3L to CartTestSupport.applicable(3))
            val guest = fixture.guestClient()
            fixture.addOneItem(guest)

            val applied = fixture.applyPromotion(guest, "SAVE10")

            assertEquals(HttpStatusCode.OK, applied.status)
            val promotion = applied.body().getValue("appliedPromotion").jsonObject
            assertEquals(3, promotion.getValue("id").jsonPrimitive.int())
            assertEquals("Summer", promotion.getValue("name").jsonPrimitive.content)
            assertEquals("SAVE10", promotion.getValue("promotionCode").jsonPrimitive.content)
            assertEquals("PERCENTAGE", promotion.getValue("discountType").jsonPrimitive.content)
            assertEquals(10, promotion.getValue("discountValue").jsonPrimitive.int())
            // 1490 + 490 shipping = 1980; ten percent of that is 198.
            assertEquals(198, applied.body().getValue("discountAmount").jsonPrimitive.int())
            assertEquals(1_782, applied.body().getValue("total").jsonPrimitive.int())

            val removed =
                guest.delete("/api/cart/promotion") {
                    header(AuthRouting.CSRF_HEADER, fixture.token)
                }
            assertEquals(HttpStatusCode.OK, removed.status)
            assertTrue(removed.bodyAsText().contains("\"appliedPromotion\":null"))
            assertEquals(0, removed.body().getValue("discountAmount").jsonPrimitive.int())
        }

    @Test
    fun `every rejection reason answers with its own status and stable code`() =
        withCart("promotion-codes") { fixture ->
            fixture.promotions.validations =
                mapOf(
                    "UNKNOWN" to PromotionCodeResult.InvalidCode,
                    "INACTIVE" to PromotionCodeResult.Inactive,
                    "EARLY" to PromotionCodeResult.NotStarted,
                    "EXPIRED" to PromotionCodeResult.Expired,
                    "LOGIN" to PromotionCodeResult.LoginRequired,
                    "TOTAL" to PromotionCodeResult.TotalExhausted,
                    "PERUSER" to PromotionCodeResult.PerUserExhausted,
                )
            val guest = fixture.guestClient()
            fixture.addOneItem(guest)

            val expected =
                listOf(
                    Triple("UNKNOWN", HttpStatusCode.BadRequest, "PROMOTION_INVALID_CODE"),
                    Triple("INACTIVE", HttpStatusCode.BadRequest, "PROMOTION_INACTIVE"),
                    Triple("EARLY", HttpStatusCode.BadRequest, "PROMOTION_NOT_STARTED"),
                    Triple("EXPIRED", HttpStatusCode.BadRequest, "PROMOTION_EXPIRED"),
                    Triple("LOGIN", HttpStatusCode.Forbidden, "PROMOTION_LOGIN_REQUIRED"),
                    Triple("TOTAL", HttpStatusCode.Conflict, "PROMOTION_TOTAL_EXHAUSTED"),
                    Triple("PERUSER", HttpStatusCode.Conflict, "PROMOTION_PER_USER_EXHAUSTED"),
                )

            expected.forEach { (code, status, expectedCode) ->
                val response = fixture.applyPromotion(guest, code)
                assertEquals(status, response.status, code)
                val body = response.body()
                assertEquals(expectedCode, body.getValue("code").jsonPrimitive.content, code)
                assertTrue(
                    body.getValue("message").jsonPrimitive.content.isNotBlank(),
                    "Every rejection carries a human-readable message as well",
                )
            }
        }

    @Test
    fun `a promotion on a cart that does not exist is a not found without a code`() =
        withCart("promotion-no-cart") { fixture ->
            val guest = fixture.guestClient()

            val response = fixture.applyPromotion(guest, "SAVE10")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Cart not found"))
        }

    private fun withCart(
        name: String,
        test: suspend ApplicationTestBuilder.(Fixture) -> Unit,
    ) {
        migratedDataSource("cart-flow-$name").use { dataSource ->
            CartTestSupport.seed(dataSource)
            val articles =
                CartTestSupport.FakeArticles(
                    mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant())
                )
            val prompts = CartTestSupport.FakePrompts()
            val promotions = CartTestSupport.FakePromotions()
            val storage = CartTestSupport.FakeImageStorage()
            testApplication {
                application {
                    val authSettings = AuthSettings(SESSION_SECRET)
                    installHttpRuntime()
                    install(RequestValidation) { validateCartRequests() }
                    installAuthModule(authSettings)
                    installCartModule(
                        Database.connect(dataSource),
                        articles,
                        prompts,
                        promotions,
                        storage,
                        GuestTokens(authSettings),
                    )
                }
                val fixture =
                    Fixture(
                        builder = this,
                        dataSource = dataSource,
                        articles = articles,
                        prompts = prompts,
                        promotions = promotions,
                    )
                test(fixture)
            }
        }
    }

    private class Fixture(
        private val builder: ApplicationTestBuilder,
        val dataSource: HikariDataSource,
        val articles: CartTestSupport.FakeArticles,
        val prompts: CartTestSupport.FakePrompts,
        val promotions: CartTestSupport.FakePromotions,
    ) {
        lateinit var token: String
            private set

        suspend fun guestClient(): HttpClient {
            val client = builder.createClient { install(HttpCookies) }
            val response = client.get("/api/antiforgery/token")
            token =
                Json.parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("requestToken")
                    .jsonPrimitive
                    .content
            return client
        }

        suspend fun upload(client: HttpClient): HttpResponse =
            client.post("/api/cart/images") {
                header(AuthRouting.CSRF_HEADER, token)
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                ByteArray(16),
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

        suspend fun addOneItem(client: HttpClient) {
            val response =
                client.post("/api/cart/items") {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"articleId":${CartTestSupport.ARTICLE_ID},""" +
                            """"variantId":${CartTestSupport.VARIANT_ID},"quantity":1}"""
                    )
                }
            check(response.status == HttpStatusCode.OK) {
                "Adding an item failed: ${response.status}"
            }
        }

        suspend fun applyPromotion(
            client: HttpClient,
            code: String,
        ): HttpResponse =
            client.post("/api/cart/promotion") {
                header(AuthRouting.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody("""{"promotionCode":"$code"}""")
            }
    }

    private companion object {
        const val SESSION_SECRET = "cart-flow-integration-session-secret"

        suspend fun HttpResponse.body(): JsonObject =
            Json.parseToJsonElement(bodyAsText()).jsonObject

        fun JsonPrimitive.long(): Long = content.toLong()

        fun JsonPrimitive.int(): Int = content.toInt()
    }
}
