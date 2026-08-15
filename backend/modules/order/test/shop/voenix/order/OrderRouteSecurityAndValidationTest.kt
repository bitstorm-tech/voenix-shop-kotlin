package shop.voenix.order

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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.operation.OperationResult
import shop.voenix.production.ProductionPdfDocument
import shop.voenix.production.ProductionPdfError
import shop.voenix.production.ProductionPdfGenerator
import shop.voenix.production.ProductionPdfResult

/**
 * What the order routes decide before any order logic runs: who reaches the admin downloads, which
 * identity a customer read is answered for, and which ids are not worth asking an operation about.
 *
 * Both collaborators are stubs that record their calls, so "rejected before the operation" is a
 * statement this test can actually make. What the operations then do with an identity — which order
 * a guest token still opens and which it no longer does — is proven against the real database in
 * [OrderFlowIntegrationTest]; what is proven here is that the routes hand them the right identity
 * in the first place.
 */
internal class OrderRouteSecurityAndValidationTest {
    @Test
    fun `the admin downloads are closed to everyone but an admin, before any generation`() =
        testApplication {
            val fixture = installOrderTestApplication()

            listOf(
                    client.get(ADMIN_PDFS),
                    client.get("$ADMIN_PDFS/$SUPPLIER_ID"),
                )
                .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }

            val customer = signedInClient("CUSTOMER")
            listOf(
                    customer.get(ADMIN_PDFS),
                    customer.get("$ADMIN_PDFS/$SUPPLIER_ID"),
                )
                .forEach { response -> assertEquals(HttpStatusCode.Forbidden, response.status) }
            assertEquals(0, fixture.pdfs.calls, "No unauthorized request may reach the generator")

            val admin = signedInClient("ADMIN")
            assertEquals(HttpStatusCode.OK, admin.get(ADMIN_PDFS).status)
            assertEquals(HttpStatusCode.OK, admin.get("$ADMIN_PDFS/$SUPPLIER_ID").status)
            assertEquals(2, fixture.pdfs.calls)
        }

    @Test
    fun `an id that is not a number is not found, and no operation is asked about it`() =
        testApplication {
            val fixture = installOrderTestApplication()
            val admin = signedInClient("ADMIN")

            listOf(
                    client.get("/api/orders/not-a-number"),
                    admin.get("/api/admin/orders/not-a-number/production-pdfs"),
                    admin.get("$ADMIN_PDFS/not-a-number"),
                )
                .forEach { response ->
                    assertEquals(HttpStatusCode.NotFound, response.status)
                    assertEquals("Order not found", response.message())
                }
            assertEquals(emptyList(), fixture.orders.calls)
            assertEquals(0, fixture.pdfs.calls)
        }

    @Test
    fun `a read is answered for the caller's identity and never mints a guest cookie`() =
        testApplication {
            val fixture = installOrderTestApplication()

            val anonymous = client.get(ORDERS)
            assertEquals(HttpStatusCode.OK, anonymous.status)
            assertEquals("[]", anonymous.bodyAsText())
            assertEquals(null to null, fixture.orders.lastIdentity)
            assertNull(
                anonymous.headers[HttpHeaders.SetCookie],
                "Looking at an order history must not turn a visitor into a tracked one",
            )

            val guest = createClient { install(HttpCookies) }
            val token = guest.mintGuestToken()
            assertEquals(HttpStatusCode.OK, guest.get(ORDERS).status)
            assertEquals(null to token, fixture.orders.lastIdentity)

            val customer = signedInClient("CUSTOMER")
            assertEquals(HttpStatusCode.OK, customer.get(ORDERS).status)
            assertEquals(
                USER_ID to null,
                fixture.orders.lastIdentity,
                "A signed-in customer reads their own orders without any guest cookie",
            )

            val both = signedInClient("CUSTOMER")
            val bothToken = both.mintGuestToken()
            assertEquals(HttpStatusCode.OK, both.get("$ORDERS/7").status)
            assertEquals(USER_ID to bothToken, fixture.orders.lastIdentity)
            assertEquals(7L, fixture.orders.lastOrderId)
        }

    /**
     * The lookup route's whole security model: the token in the path is the only credential, and
     * every way of not naming an order is one answer.
     *
     * The missing-token case is the one worth spelling out. `/api/order-lookup` without a token
     * would otherwise fall through to Ktor's bare `404` with an empty body, and a client — or an
     * attacker — could tell the two misses apart by the body alone.
     */
    @Test
    fun `the lookup route needs no identity and answers every miss the same way`() =
        testApplication {
            val fixture = installOrderTestApplication()

            val found = client.get("$LOOKUP/$TOKEN")
            assertEquals(HttpStatusCode.OK, found.status)
            assertEquals("no-store", found.headers[HttpHeaders.CacheControl])
            assertEquals(listOf(TOKEN), fixture.orders.tokens)
            assertNull(
                found.headers[HttpHeaders.SetCookie],
                "Following a mail link must not turn the reader into a tracked visitor",
            )

            fixture.orders.tokenResult = OperationResult.NotFound
            val misses =
                listOf(
                    client.get("$LOOKUP/$TOKEN"),
                    client.get("$LOOKUP/not-a-token"),
                    client.get(LOOKUP),
                )
            misses.forEach { response ->
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals("Order not found", response.message())
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            }

            fixture.orders.tokenResult = OperationResult.UnexpectedFailure
            val broken = client.get("$LOOKUP/$TOKEN")
            assertEquals(HttpStatusCode.InternalServerError, broken.status)
            assertEquals("Internal server error", broken.message())
        }

    @Test
    fun `every order answer forbids caching`() = testApplication {
        val fixture = installOrderTestApplication()
        val admin = signedInClient("ADMIN")
        fixture.orders.orderResult = OperationResult.NotFound

        listOf(
                client.get(ORDERS),
                client.get("$ORDERS/1"),
                admin.get(ADMIN_PDFS),
                admin.get("$ADMIN_PDFS/$SUPPLIER_ID"),
                admin.get("$ADMIN_PDFS/999"),
            )
            .forEach { response ->
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            }
    }

    @Test
    fun `a read that misses or fails maps to the contracted status`() = testApplication {
        val fixture = installOrderTestApplication()

        fixture.orders.orderResult = OperationResult.NotFound
        val missing = client.get("$ORDERS/404")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("Order not found", missing.message())

        fixture.orders.orderResult = OperationResult.UnexpectedFailure
        val broken = client.get("$ORDERS/1")
        assertEquals(HttpStatusCode.InternalServerError, broken.status)
        assertEquals("Internal server error", broken.message())

        fixture.orders.historyResult = OperationResult.UnexpectedFailure
        assertEquals(HttpStatusCode.InternalServerError, client.get(ORDERS).status)
    }

    @Test
    fun `the pdf list names one document per supplier and the fetch streams that document`() =
        testApplication {
            val fixture = installOrderTestApplication()
            val admin = signedInClient("ADMIN")

            val listed = admin.get(ADMIN_PDFS)
            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals(ORDER_ID, fixture.pdfs.lastOrderId)
            assertEquals(
                listOf(
                    SUPPLIER_ID to "ORD-$ORDER_ID.pdf",
                    OTHER_SUPPLIER_ID to "ORD-$ORDER_ID.pdf",
                ),
                Json.parseToJsonElement(listed.bodyAsText()).jsonArray.map { element ->
                    val entry = element.jsonObject
                    entry.getValue("supplierId").jsonPrimitive.content.toLong() to
                        entry.getValue("fileName").jsonPrimitive.content
                },
                "The suppliers keep the order the generator produced them in",
            )

            val fetched = admin.get("$ADMIN_PDFS/$OTHER_SUPPLIER_ID")
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertEquals(ContentType.Application.Pdf, fetched.contentType()?.withoutParameters())
            assertEquals(
                "attachment; filename=\"ORD-$ORDER_ID.pdf\"",
                fetched.headers[HttpHeaders.ContentDisposition],
            )
            assertContentEquals(byteArrayOf(OTHER_SUPPLIER_ID.toByte()), fetched.bodyAsBytes())

            val unknownSupplier = admin.get("$ADMIN_PDFS/999")
            assertEquals(HttpStatusCode.NotFound, unknownSupplier.status)
            assertEquals("Order not found", unknownSupplier.message())
        }

    @Test
    fun `a generation failure maps to a safe status and code that names no internals`() =
        testApplication {
            val fixture = installOrderTestApplication()
            val admin = signedInClient("ADMIN")

            fixture.pdfs.result = ProductionPdfResult.OrderNotFound
            val unknown = admin.get(ADMIN_PDFS)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertEquals("Order not found", unknown.message())

            val expected =
                mapOf(
                    ProductionPdfError.MISSING_IMAGE to
                        (HttpStatusCode.Conflict to "PRODUCTION_PDF_MISSING_IMAGE"),
                    ProductionPdfError.UNREADABLE_IMAGE to
                        (HttpStatusCode.Conflict to "PRODUCTION_PDF_UNREADABLE_IMAGE"),
                    ProductionPdfError.INVALID_SOURCE to
                        (HttpStatusCode.Conflict to "PRODUCTION_PDF_INVALID_SOURCE"),
                    ProductionPdfError.RENDER_FAILURE to
                        (HttpStatusCode.InternalServerError to "PRODUCTION_PDF_RENDER_FAILURE"),
                )
            expected.forEach { (error, contract) ->
                fixture.pdfs.result = ProductionPdfResult.GenerationFailed(error)
                listOf(admin.get(ADMIN_PDFS), admin.get("$ADMIN_PDFS/$SUPPLIER_ID")).forEach {
                    response ->
                    val (status, code) = contract
                    assertEquals(status, response.status)
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    assertEquals(code, body.getValue("code").jsonPrimitive.content)
                    val message = body.getValue("message").jsonPrimitive.content
                    assertTrue(
                        FORBIDDEN_IN_MESSAGES.none { internal ->
                            message.contains(internal, ignoreCase = true)
                        },
                        "A failure message must not leak internals: $message",
                    )
                }
            }
        }

    private fun ApplicationTestBuilder.installOrderTestApplication(): Fixture {
        val orders = StubOrderOperations()
        val pdfs = StubProductionPdfs()
        application {
            val authSettings = AuthSettings(SESSION_SECRET)
            installHttpRuntime()
            installAuthModule(authSettings)
            installOrderRoutes(orders, pdfs, GuestTokens(authSettings))
            installTestIdentityRoutes(GuestTokens(authSettings))
        }
        return Fixture(orders, pdfs)
    }

    /**
     * The two identities the routes read, minted the way the rest of the application mints them: a
     * user session, and a guest cookie whose plaintext token the caller learns so it can assert
     * which token reached the operations.
     */
    private fun Application.installTestIdentityRoutes(guestTokens: GuestTokens) {
        routing {
            post("/test/sign-in/{role}") {
                call.sessions.set(
                    UserSession(
                        userId = USER_ID.toString(),
                        roles = setOf(checkNotNull(call.parameters["role"])),
                    )
                )
                call.respond(HttpStatusCode.OK)
            }
            get("/test/guest") { call.respond(guestTokens.getOrCreate(call)) }
        }
    }

    private suspend fun ApplicationTestBuilder.signedInClient(role: String): HttpClient =
        createClient {
            install(HttpCookies)
        }
        .also { client ->
            assertEquals(HttpStatusCode.OK, client.post("/test/sign-in/$role").status)
        }

    private suspend fun HttpClient.mintGuestToken(): String {
        val response = get("/test/guest")
        assertEquals(HttpStatusCode.OK, response.status)
        return response.bodyAsText()
    }

    private class Fixture(
        val orders: StubOrderOperations,
        val pdfs: StubProductionPdfs,
    )

    /** Records which identity a read was answered for, and answers with whatever a test sets. */
    private class StubOrderOperations : OrderOperations {
        val calls: MutableList<String> = mutableListOf()
        var lastIdentity: Pair<Long?, String?>? = null
        var lastOrderId: Long? = null
        var historyResult: OperationResult<List<OrderView>>? = null
        var orderResult: OperationResult<OrderView>? = null

        /** Every raw token string the lookup route handed on, in call order. */
        val tokens: MutableList<String> = mutableListOf()
        var tokenResult: OperationResult<OrderView>? = null

        override suspend fun history(
            userId: Long?,
            guestToken: String?,
        ): OperationResult<List<OrderView>> {
            calls += "history"
            lastIdentity = userId to guestToken
            return historyResult ?: OperationResult.Success(emptyList())
        }

        override suspend fun order(
            orderId: Long,
            userId: Long?,
            guestToken: String?,
        ): OperationResult<OrderView> {
            calls += "order"
            lastOrderId = orderId
            lastIdentity = userId to guestToken
            return orderResult ?: OperationResult.Success(orderView())
        }

        override suspend fun orderByToken(token: String): OperationResult<OrderView> {
            calls += "orderByToken"
            tokens += token
            return tokenResult ?: OperationResult.Success(orderView())
        }

        private fun orderView(): OrderView =
            OrderView(
                orderId = ORDER_ID,
                createdAt = Instant.parse("2026-07-30T09:12:44Z"),
                status = OrderStatus.PAID,
                subtotal = 3_980,
                shippingCost = 490,
                discountAmount = 400,
                total = 4_070,
                items = emptyList(),
            )
    }

    /** Counts every generation, so "no unauthorized request generated a document" is provable. */
    private class StubProductionPdfs : ProductionPdfGenerator {
        var calls: Int = 0
        var lastOrderId: Long? = null
        var result: ProductionPdfResult =
            ProductionPdfResult.Generated(
                listOf(document(SUPPLIER_ID), document(OTHER_SUPPLIER_ID))
            )

        override suspend fun generate(orderId: Long): ProductionPdfResult {
            calls++
            lastOrderId = orderId
            return result
        }
    }

    private companion object {
        const val SESSION_SECRET = "order-route-security-session-secret"
        const val ORDERS = "/api/orders"
        const val LOOKUP = "/api/order-lookup"
        const val TOKEN = "3D0lyGxV8mAqXk2rTsUuWvZyB1cE4fH6jK8mN0pQrSt"
        const val ORDER_ID = 42L
        const val SUPPLIER_ID = 7L
        const val OTHER_SUPPLIER_ID = 9L
        const val USER_ID = 11L
        const val ADMIN_PDFS = "/api/admin/orders/$ORDER_ID/production-pdfs"

        val FORBIDDEN_IN_MESSAGES = listOf("Exception", "/var/", ".webp", "PDFBox")

        fun document(supplierId: Long): ProductionPdfDocument =
            ProductionPdfDocument(
                supplierId = supplierId,
                fileName = "ORD-$ORDER_ID.pdf",
                mediaType = "application/pdf",
                bytes = byteArrayOf(supplierId.toByte()),
                sha256 = "0".repeat(64),
            )

        suspend fun HttpResponse.message(): String? =
            Json.parseToJsonElement(bodyAsText()).jsonObject["message"]?.jsonPrimitive?.content
    }
}
