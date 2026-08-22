package shop.voenix.order

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.FrontendBaseUrl
import shop.voenix.http.installHttpRuntime
import shop.voenix.production.ProductionPdfDocument
import shop.voenix.production.ProductionPdfGenerator
import shop.voenix.production.ProductionPdfResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The order module as a browser sees it: whole journeys over HTTP against real PostgreSQL, through
 * the same `installOrderModule` the composition root uses.
 *
 * What this test is here for is the wire and the authorization rule behind it. The wire, because a
 * frontend branches on exact field names and statuses. The rule, because "who may read this order"
 * is the one thing the legacy application got wrong in both directions: it authorized a single
 * lookup by guest token alone, and it served the production PDF to anybody at all.
 *
 * Only the production-PDF generator is faked here, and deliberately so: what it renders is proven
 * in the production module, and the order module's own production source is bound in a later
 * ticket. What is proven here is that the admin download reaches it at all and streams what it
 * returns.
 */
internal class OrderFlowIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the history answers newest first and carries the whole order in one shape`() =
        withOrders("history") { fixture ->
            val guest = fixture.guestClient()
            val older = fixture.place(cartId = 1, guestToken = guest.token)
            val newer = fixture.place(cartId = 2, guestToken = guest.token)
            // The ids ascend with the placement, so the fixture makes the creation order oppose
            // them: without an explicit ORDER BY on created_at this ordering cannot be right by
            // accident.
            fixture.backdate(newer, days = 2)

            val listed = guest.client.get(ORDERS)
            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals("no-store", listed.headers[HttpHeaders.CacheControl])

            val orders = Json.parseToJsonElement(listed.bodyAsText()).jsonArray
            assertEquals(
                listOf(older, newer),
                orders.map { element -> element.jsonObject.getValue("orderId").long() },
                "The newest order comes first, whatever its id says",
            )

            val order = orders.first().jsonObject
            assertEquals("PENDING", order.getValue("status").jsonPrimitive.content)
            assertEquals(3_980, order.getValue("subtotal").int())
            assertEquals(490, order.getValue("shippingCost").int())
            assertEquals(0, order.getValue("discountAmount").int())
            assertEquals(4_470, order.getValue("total").int())
            assertTrue(
                Instant.parse(order.getValue("createdAt").jsonPrimitive.content) < Instant.now(),
                "createdAt is serialized as an ISO-8601 instant",
            )

            val line = order.getValue("items").jsonArray.single().jsonObject
            assertEquals(OrderTestSupport.ARTICLE_ID, line.getValue("articleId").long())
            assertEquals(OrderTestSupport.VARIANT_ID, line.getValue("variantId").long())
            assertEquals(
                "MUG",
                line.getValue("articleType").jsonPrimitive.content,
                "the snapshotted discriminator a client renders the line by",
            )
            assertEquals("Classic mug", line.getValue("articleName").jsonPrimitive.content)
            assertEquals("White", line.getValue("variantName").jsonPrimitive.content)
            assertEquals(2, line.getValue("quantity").int())
            assertEquals(1_490, line.getValue("price").int())
            assertEquals(500, line.getValue("promptPrice").int())
            assertEquals(OrderTestSupport.PRINT_IMAGE_ID, line.getValue("imageId").long())

            val single = guest.client.get("$ORDERS/$older")
            assertEquals(HttpStatusCode.OK, single.status)
            assertEquals(order, single.body(), "List and detail are one representation")
        }

    /**
     * The `paymentStatus` field on the wire, and the two reads that fill it.
     *
     * The status source is faked here on purpose: what the payment module *answers* is proven
     * against a real database and a real provider stub in `PaymentStatusIntegrationTest`. What is
     * proven here is the order module's half of the contract — the field is serialized uppercase,
     * an order without a payment carries an explicit `null` rather than no field at all, and the
     * history asks for every one of its orders in a single batch call while only the single read
     * refreshes.
     */
    @Test
    fun `every order answer carries a payment status, and null means no payment`() =
        withOrders("payment-status") { fixture ->
            val guest = fixture.guestClient()
            val paid = fixture.place(cartId = 1, guestToken = guest.token)
            val unpaid = fixture.place(cartId = 2, guestToken = guest.token)
            fixture.paymentStatuses.statuses = mapOf(paid to OrderPaymentStatus.AUTHORIZED)

            val listed = guest.client.get(ORDERS)
            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals(
                mapOf(paid to "AUTHORIZED", unpaid to null),
                Json.parseToJsonElement(listed.bodyAsText()).jsonArray.associate { element ->
                    val order = element.jsonObject
                    order.getValue("orderId").long() to
                        order.getValue("paymentStatus").jsonPrimitive.contentOrNull
                },
                "Every listed order carries its status, uppercase, and null without a payment",
            )
            assertEquals(
                listOf(setOf(paid, unpaid)),
                fixture.paymentStatuses.storedCalls,
                "A history of two orders costs exactly one batch read",
            )
            assertEquals(
                emptyList(),
                fixture.paymentStatuses.refreshedCalls,
                "and never a refresh, whatever the statuses are",
            )

            val single = guest.client.get("$ORDERS/$paid").body()
            assertEquals(
                "AUTHORIZED",
                single.getValue("paymentStatus").jsonPrimitive.content,
                "The single read answers the refreshed status",
            )
            assertEquals(listOf(paid), fixture.paymentStatuses.refreshedCalls)

            val without = guest.client.get("$ORDERS/$unpaid").body()
            assertEquals(
                JsonNull,
                without.getValue("paymentStatus"),
                "An order without a payment answers an explicit null, not a missing field",
            )
        }

    @Test
    fun `a visitor without any identity has no history and no order`() =
        withOrders("anonymous") { fixture ->
            val guest = fixture.guestClient()
            val orderId = fixture.place(cartId = 1, guestToken = guest.token)

            val history = client.get(ORDERS)
            assertEquals(HttpStatusCode.OK, history.status)
            assertEquals("[]", history.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, client.get("$ORDERS/$orderId").status)
        }

    @Test
    fun `a guest token opens the order it placed, and never a foreign one`() =
        withOrders("ownership") { fixture ->
            val guest = fixture.guestClient()
            val stranger = fixture.guestClient()
            val orderId = fixture.place(cartId = 1, guestToken = guest.token)

            assertEquals(HttpStatusCode.OK, guest.client.get("$ORDERS/$orderId").status)
            val foreign = stranger.client.get("$ORDERS/$orderId")
            assertEquals(
                HttpStatusCode.NotFound,
                foreign.status,
                "A foreign order reads exactly like one that never existed",
            )
            assertEquals("Order not found", foreign.message())
            assertEquals("[]", stranger.client.get(ORDERS).bodyAsText())
            assertEquals(HttpStatusCode.NotFound, guest.client.get("$ORDERS/404").status)

            // The signed-in owner carries no guest cookie at all, and still reads their order.
            val accountOrderId =
                fixture.place(cartId = 2, guestToken = null, userId = OrderTestSupport.USER_ID)
            val customer = fixture.signedInClient(OrderTestSupport.USER_ID, "CUSTOMER")
            assertEquals(HttpStatusCode.OK, customer.get("$ORDERS/$accountOrderId").status)
            assertEquals(
                1,
                Json.parseToJsonElement(customer.get(ORDERS).bodyAsText()).jsonArray.size,
            )

            val otherCustomer = fixture.signedInClient(OrderTestSupport.OTHER_USER_ID, "CUSTOMER")
            assertEquals(
                HttpStatusCode.NotFound,
                otherCustomer.get("$ORDERS/$accountOrderId").status,
            )
            assertEquals("[]", otherCustomer.get(ORDERS).bodyAsText())
        }

    @Test
    fun `a guest cookie does not open the order its browser placed while signed in`() =
        withOrders("signed-in-with-cookie") { fixture ->
            // The browser has a guest cookie *and* a session during checkout, so the order carries
            // both handles. Signing out leaves the cookie in place — and it must not become a key
            // to the account's order.
            val guest = fixture.guestClient()
            val orderId =
                fixture.place(
                    cartId = 1,
                    guestToken = guest.token,
                    userId = OrderTestSupport.USER_ID,
                )

            val afterLogout = guest.client.get("$ORDERS/$orderId")
            assertEquals(
                HttpStatusCode.NotFound,
                afterLogout.status,
                "The guest cookie must not open an order that belongs to an account",
            )
            assertEquals("Order not found", afterLogout.message())
            assertEquals("[]", guest.client.get(ORDERS).bodyAsText())

            val customer = fixture.signedInClient(OrderTestSupport.USER_ID, "CUSTOMER")
            assertEquals(HttpStatusCode.OK, customer.get("$ORDERS/$orderId").status)
        }

    /**
     * The permanent link from the confirmation mail, over the wire: a browser with no session and
     * no guest cookie reads one whole order by its token, and nothing else.
     *
     * Three rules are pinned here at once. The route needs no identity, because the token is the
     * credential. It never refreshes a payment — an anonymous request must not be able to make the
     * shop call Mollie — which the counting fake proves. And every miss is one answer.
     */
    @Test
    fun `the mail link opens one order without any identity and never refreshes its payment`() =
        withOrders("lookup") { fixture ->
            val guest = fixture.guestClient()
            val orderId = fixture.place(cartId = 1, guestToken = guest.token)
            val token = fixture.accessToken(orderId)
            fixture.paymentStatuses.statuses = mapOf(orderId to OrderPaymentStatus.PAID)

            // `client` has no cookie jar at all: no session, no guest cookie, nothing to send.
            val looked = client.get("$LOOKUP/$token")
            assertEquals(HttpStatusCode.OK, looked.status)
            assertEquals("no-store", looked.headers[HttpHeaders.CacheControl])
            assertEquals(
                null,
                looked.headers[HttpHeaders.SetCookie],
                "A mail link must not turn its reader into a tracked visitor",
            )

            val order = looked.body()
            assertEquals(orderId, order.getValue("orderId").long())
            assertEquals("PENDING", order.getValue("status").jsonPrimitive.content)
            assertEquals(
                "PAID",
                order.getValue("paymentStatus").jsonPrimitive.content,
                "The stored status is answered, uppercase, like everywhere else",
            )
            assertEquals(4_470, order.getValue("total").int())
            assertEquals(
                1,
                order.getValue("items").jsonArray.size,
                "The whole order, lines and all",
            )

            assertEquals(
                listOf(setOf(orderId)),
                fixture.paymentStatuses.storedCalls,
                "The status comes from one stored read",
            )
            assertEquals(
                emptyList(),
                fixture.paymentStatuses.refreshedCalls,
                "An anonymous read must never drive an outbound provider call",
            )

            val misses =
                listOf(
                    client.get("$LOOKUP/${token}A"), // malformed: 44 characters
                    client.get("$LOOKUP/not-a-token"), // malformed: wrong alphabet and length
                    client.get("$LOOKUP/${"A".repeat(43)}"), // well formed, names no order
                    client.get(LOOKUP), // no token at all
                )
            misses.forEach { response ->
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals("Order not found", response.message())
            }
            assertEquals(emptyList(), fixture.paymentStatuses.refreshedCalls)
        }

    /**
     * Risk R5 of issue #110: the token is a bearer credential, so it must never travel back in an
     * API answer — not in the history, not in the order's own detail read, and not even in the
     * answer the token itself unlocked.
     */
    @Test
    fun `no order answer ever carries the access token`() =
        withOrders("token-leak") { fixture ->
            val guest = fixture.guestClient()
            val orderId = fixture.place(cartId = 1, guestToken = guest.token)
            val token = fixture.accessToken(orderId)

            val bodies =
                listOf(
                    guest.client.get(ORDERS).bodyAsText(),
                    guest.client.get("$ORDERS/$orderId").bodyAsText(),
                    client.get("$LOOKUP/$token").bodyAsText(),
                )

            bodies.forEach { body ->
                assertTrue(token.isNotEmpty() && !body.contains(token), "The token leaked: $body")
                assertTrue(
                    !body.contains("accessToken", ignoreCase = true) &&
                        !body.contains("access_token"),
                    "Not even a field name for it may exist: $body",
                )
            }
        }

    @Test
    fun `the production download answers an admin and rejects an anonymous caller`() =
        withOrders("production-pdfs") { fixture ->
            val guest = fixture.guestClient()
            val orderId = fixture.place(cartId = 1, guestToken = guest.token)
            val pdfs = "/api/admin/orders/$orderId/production-pdfs"

            assertEquals(HttpStatusCode.Unauthorized, guest.client.get(pdfs).status)

            val admin = fixture.signedInClient(OrderTestSupport.USER_ID, "ADMIN")
            val listed = admin.get(pdfs)
            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals("no-store", listed.headers[HttpHeaders.CacheControl])
            val entry = Json.parseToJsonElement(listed.bodyAsText()).jsonArray.single().jsonObject
            assertEquals(SUPPLIER_ID, entry.getValue("supplierId").long())
            assertEquals("ORD-$orderId.pdf", entry.getValue("fileName").jsonPrimitive.content)

            val fetched = admin.get("$pdfs/$SUPPLIER_ID")
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertEquals(ContentType.Application.Pdf, fetched.contentType()?.withoutParameters())
            assertEquals(
                "attachment; filename=\"ORD-$orderId.pdf\"",
                fetched.headers[HttpHeaders.ContentDisposition],
            )
            assertContentEquals(PDF_BYTES, fetched.bodyAsBytes())

            fixture.pdfs.result = ProductionPdfResult.OrderNotFound
            assertEquals(HttpStatusCode.NotFound, admin.get(pdfs).status)
        }

    private fun withOrders(
        name: String,
        test: suspend ApplicationTestBuilder.(Fixture) -> Unit,
    ) {
        migratedDataSource("order-flow-$name").use { dataSource ->
            OrderTestSupport.seed(dataSource)
            val database = Database.connect(dataSource)
            val articles =
                OrderTestSupport.FakeArticles(
                    mapOf(OrderTestSupport.REFERENCE to OrderTestSupport.variant())
                )
            val pdfs = StubProductionPdfs()
            val paymentStatuses = OrderTestSupport.FakePaymentStatuses()
            val authSettings = AuthSettings(SESSION_SECRET)
            val guestTokens = GuestTokens(authSettings)
            testApplication {
                application {
                    installHttpRuntime()
                    installAuthModule(authSettings)
                    installOrderModule(
                        database = database,
                        frontendBaseUrl = FrontendBaseUrl(OrderTestSupport.FRONTEND_BASE_URL),
                        articles = articles,
                        promotions = OrderTestSupport.FakePromotions(),
                        productionOutbox = OrderTestSupport.FakeProductionOutbox(),
                        emailOutbox = OrderTestSupport.FakeEmailOutbox(),
                        printImages = OrderTestSupport.FakePrintImages(),
                        payments = paymentStatuses,
                        productionPdfs = pdfs,
                        guestTokens = guestTokens,
                    )
                    installTestIdentityRoutes(guestTokens)
                }
                // The placement has no HTTP route in this wave, so orders are placed through the
                // service on the very database the installed module reads from.
                val service =
                    OrderService(
                        repository = OrderRepository(database),
                        articles = articles,
                        promotions = OrderTestSupport.FakePromotions(),
                        productionOutbox = OrderTestSupport.FakeProductionOutbox(),
                        emailOutbox = OrderTestSupport.FakeEmailOutbox(),
                        printImages = OrderTestSupport.FakePrintImages(),
                        paymentStatuses = paymentStatuses,
                        links = OrderTestSupport.LINKS,
                    )
                // Touching the application forces its installation before the fixture places its
                // first order.
                check(client.get(ORDERS).status == HttpStatusCode.OK)
                test(Fixture(this, dataSource, service, pdfs, paymentStatuses))
            }
        }
    }

    /**
     * The two identities the routes read, minted the way the rest of the application mints them.
     * The guest route answers with the plaintext token so the fixture can place an order under it.
     */
    private fun Application.installTestIdentityRoutes(guestTokens: GuestTokens) {
        routing {
            post("/test/sign-in/{userId}/{role}") {
                call.sessions.set(
                    UserSession(
                        userId = checkNotNull(call.parameters["userId"]),
                        roles = setOf(checkNotNull(call.parameters["role"])),
                    )
                )
                call.respond(HttpStatusCode.OK)
            }
            get("/test/guest") { call.respond(guestTokens.getOrCreate(call)) }
        }
    }

    private class Fixture(
        private val builder: ApplicationTestBuilder,
        private val dataSource: HikariDataSource,
        private val service: OrderService,
        val pdfs: StubProductionPdfs,
        val paymentStatuses: OrderTestSupport.FakePaymentStatuses,
    ) {
        suspend fun place(
            cartId: Long,
            guestToken: String?,
            userId: Long? = null,
        ): Long =
            when (
                val result =
                    service.place(
                        OrderTestSupport.placeOrderInput(
                            cartId = cartId,
                            userId = userId,
                            guestToken = guestToken,
                        )
                    )
            ) {
                is OrderPlacementResult.Placed -> result.order.orderId
                else -> fail("Expected a stored order but got $result")
            }

        /** The access token the placement generated — the thing the mail link carries. */
        fun accessToken(orderId: Long): String =
            checkNotNull(
                OrderTestSupport.singleString(
                    dataSource,
                    "SELECT access_token FROM voenix.orders WHERE id = $orderId",
                )
            )

        /**
         * Moves an order's creation into the past, so the history ordering has something to say.
         */
        fun backdate(
            orderId: Long,
            days: Int,
        ) {
            OrderTestSupport.execute(
                dataSource,
                "UPDATE voenix.orders SET created_at = created_at - interval '$days days' " +
                    "WHERE id = $orderId",
            )
        }

        suspend fun guestClient(): GuestClient {
            val client = builder.createClient { install(HttpCookies) }
            val response = client.get("/test/guest")
            check(response.status == HttpStatusCode.OK)
            return GuestClient(client, response.bodyAsText())
        }

        suspend fun signedInClient(
            userId: Long,
            role: String,
        ): HttpClient =
            builder
                .createClient { install(HttpCookies) }
                .also { client ->
                    check(client.post("/test/sign-in/$userId/$role").status == HttpStatusCode.OK)
                }
    }

    /** A visitor's browser: its cookie jar carries the guest cookie, and it knows its token. */
    private class GuestClient(
        val client: HttpClient,
        val token: String,
    )

    /** Stands in for the production module's generator until the order source is bound. */
    private class StubProductionPdfs : ProductionPdfGenerator {
        var result: ProductionPdfResult? = null

        override suspend fun generate(orderId: Long): ProductionPdfResult =
            result
                ?: ProductionPdfResult.Generated(
                    listOf(
                        ProductionPdfDocument(
                            supplierId = SUPPLIER_ID,
                            fileName = "ORD-$orderId.pdf",
                            mediaType = "application/pdf",
                            bytes = PDF_BYTES,
                            sha256 = "0".repeat(64),
                        )
                    )
                )
    }

    private companion object {
        const val SESSION_SECRET = "order-flow-integration-session-secret"
        const val ORDERS = "/api/orders"
        const val LOOKUP = "/api/order-lookup"
        const val SUPPLIER_ID = 42L

        val PDF_BYTES: ByteArray = "%PDF-1.7".toByteArray()

        suspend fun HttpResponse.body(): JsonObject =
            Json.parseToJsonElement(bodyAsText()).jsonObject

        suspend fun HttpResponse.message(): String? = body()["message"]?.jsonPrimitive?.content

        fun JsonElement.long(): Long = jsonPrimitive.content.toLong()

        fun JsonElement.int(): Int = jsonPrimitive.content.toInt()
    }
}
