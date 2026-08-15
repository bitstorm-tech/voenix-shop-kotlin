package shop.voenix.promotion

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.ApiError
import shop.voenix.http.installHttpRuntime
import shop.voenix.operation.OperationResult

internal class PromotionRouteSecurityAndValidationTest {
    @Test
    fun `admin subtree rejects before id binding or promotion operations`() = testApplication {
        val promotions = StubPromotionOperations()
        application { installPromotionTestApplication(promotions) }

        listOf(
                client.get("/api/admin/promotions"),
                client.get("/api/admin/promotions/1"),
                client.get("/api/admin/promotions/not-a-long"),
                client.post("/api/admin/promotions"),
                client.put("/api/admin/promotions/1"),
                client.delete("/api/admin/promotions/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(0, promotions.operationCalls)

        val customer = signedInClient("CUSTOMER")
        assertEquals(HttpStatusCode.Forbidden, customer.get("/api/admin/promotions").status)
        assertEquals(HttpStatusCode.Forbidden, customer.get("/api/admin/promotions/1").status)
        assertEquals(HttpStatusCode.Forbidden, customer.post("/api/admin/promotions").status)
        assertEquals(HttpStatusCode.Forbidden, customer.put("/api/admin/promotions/1").status)
        assertEquals(HttpStatusCode.Forbidden, customer.delete("/api/admin/promotions/1").status)
        assertEquals(0, promotions.operationCalls)

        val admin = signedInClient("ADMIN")
        assertApiError(
            admin.post("/api/admin/promotions"),
            HttpStatusCode.BadRequest,
            "Invalid CSRF token",
        )
        assertApiError(
            admin.put("/api/admin/promotions/1"),
            HttpStatusCode.BadRequest,
            "Invalid CSRF token",
        )
        assertApiError(
            admin.delete("/api/admin/promotions/1"),
            HttpStatusCode.BadRequest,
            "Invalid CSRF token",
        )
        assertApiError(
            admin.get("/api/admin/promotions/not-a-long"),
            HttpStatusCode.BadRequest,
            "Invalid promotion id",
        )
        assertEquals(0, promotions.operationCalls)
    }

    @Test
    fun `http validation rejects before operations and valid creates preserve contracts`() =
        testApplication {
            val promotions = StubPromotionOperations()
            application { installPromotionTestApplication(promotions) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            val invalid =
                admin.post("/api/admin/promotions") {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"   ","discountType":"PERCENTAGE","discountValue":101}""")
                }
            assertApiError(
                invalid,
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf(
                    "name" to listOf("Name is required"),
                    "couponCode" to listOf("CouponCode is required"),
                    "discountValue" to
                        listOf("DiscountValue must be at most 100 for percentage promotions"),
                ),
            )
            assertEquals(0, promotions.operationCalls)

            val created =
                admin.post("/api/admin/promotions") {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"name":" Summer sale ","couponCode":" Summer10 ",
                         "discountType":"PERCENTAGE","discountValue":10.00,
                         "startsAt":null,"endsAt":null,
                         "usageLimitTotal":null,"usageLimitPerUser":null,"isActive":true}
                        """
                            .trimIndent()
                    )
                }
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("/api/admin/promotions/42", created.headers[HttpHeaders.Location])
            assertEquals(
                PromotionInput(
                    name = " Summer sale ",
                    couponCode = " Summer10 ",
                    discountType = "PERCENTAGE",
                    discountValue = BigDecimal("10.00"),
                    isActive = true,
                ),
                promotions.lastCreated,
            )
            assertEquals(
                "SUMMER10",
                Json.parseToJsonElement(created.bodyAsText())
                    .jsonObject
                    .getValue("couponCode")
                    .jsonPrimitive
                    .content,
            )

            promotions.createResult = OperationResult.Conflict
            val conflict =
                admin.post("/api/admin/promotions") {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"name":"Summer sale","couponCode":"Summer10",
                         "discountType":"PERCENTAGE","discountValue":10}
                        """
                            .trimIndent()
                    )
                }
            assertApiError(conflict, HttpStatusCode.Conflict, "Coupon code is already in use")
        }

    @Test
    fun `admin can list and read promotions and results map to the required api errors`() =
        testApplication {
            val promotions = StubPromotionOperations()
            application { installPromotionTestApplication(promotions) }
            val admin = signedInClient("ADMIN")

            val listed = admin.get("/api/admin/promotions")
            assertEquals(HttpStatusCode.OK, listed.status)
            val listedJson = Json.parseToJsonElement(listed.bodyAsText()).jsonArray
            assertEquals(1, listedJson.size)
            val promotionJson = listedJson.single().jsonObject
            assertEquals("SUMMER10", promotionJson.getValue("couponCode").jsonPrimitive.content)
            assertEquals(
                """{"discountType":"PERCENTAGE","discountValue":10.00}""",
                promotionJson.getValue("discount").toString(),
            )

            val fetched = admin.get("/api/admin/promotions/7")
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertEquals(7L, promotions.lastRequestedId)

            promotions.getResult = OperationResult.NotFound
            assertApiError(
                admin.get("/api/admin/promotions/404"),
                HttpStatusCode.NotFound,
                "Promotion not found",
            )

            promotions.listResult = OperationResult.UnexpectedFailure
            assertApiError(
                admin.get("/api/admin/promotions"),
                HttpStatusCode.InternalServerError,
                "Internal server error",
            )
        }

    @Test
    fun `update and delete validate first and map their results to the required responses`() =
        testApplication {
            val promotions = StubPromotionOperations()
            application { installPromotionTestApplication(promotions) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            val invalid =
                admin.put("/api/admin/promotions/7") {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"name":"Summer sale","couponCode":"Summer10","discountType":"WRONG"}"""
                    )
                }
            assertApiError(
                invalid,
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf(
                    "discountType" to listOf("DiscountType must be PERCENTAGE or FIXED_AMOUNT"),
                    "discountValue" to listOf("DiscountValue is required"),
                ),
            )
            assertEquals(0, promotions.operationCalls)

            val updated =
                admin.put("/api/admin/promotions/7") {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"name":" Summer sale ","couponCode":" Summer10 ",
                         "discountType":"PERCENTAGE","discountValue":10.00,"isActive":true}
                        """
                            .trimIndent()
                    )
                }
            assertEquals(HttpStatusCode.OK, updated.status)
            assertEquals(7L, promotions.lastRequestedId)
            assertEquals(
                PromotionInput(
                    name = " Summer sale ",
                    couponCode = " Summer10 ",
                    discountType = "PERCENTAGE",
                    discountValue = BigDecimal("10.00"),
                    isActive = true,
                ),
                promotions.lastUpdated,
            )

            promotions.updateResult = OperationResult.Conflict
            assertApiError(
                admin.putValidPromotion(token),
                HttpStatusCode.Conflict,
                "Coupon code is already in use or the promotion is locked",
            )

            promotions.updateResult = OperationResult.NotFound
            assertApiError(
                admin.putValidPromotion(token),
                HttpStatusCode.NotFound,
                "Promotion not found",
            )

            val deleted =
                admin.delete("/api/admin/promotions/9") { header(AuthRouting.CSRF_HEADER, token) }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertEquals("", deleted.bodyAsText())
            assertEquals(9L, promotions.lastRequestedId)

            promotions.deleteResult = OperationResult.Conflict
            assertApiError(
                admin.delete("/api/admin/promotions/9") { header(AuthRouting.CSRF_HEADER, token) },
                HttpStatusCode.Conflict,
                "Promotion is still in use and cannot be deleted",
            )

            promotions.deleteResult = OperationResult.NotFound
            assertApiError(
                admin.delete("/api/admin/promotions/9") { header(AuthRouting.CSRF_HEADER, token) },
                HttpStatusCode.NotFound,
                "Promotion not found",
            )
        }

    private suspend fun HttpClient.putValidPromotion(token: String): HttpResponse =
        put("/api/admin/promotions/7") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"name":"Summer sale","couponCode":"Summer10",
                 "discountType":"PERCENTAGE","discountValue":10}
                """
                    .trimIndent()
            )
        }

    private fun Application.installPromotionTestApplication(promotions: PromotionOperations) {
        installHttpRuntime()
        install(RequestValidation) { validatePromotionRequests() }
        installAuthModule(AuthSettings("promotion-route-contract-session-secret"))
        installPromotionRoutes(promotions)
        routing {
            post("/test/sign-in/{role}") {
                call.sessions.set(
                    UserSession(
                        userId = "11",
                        roles = setOf(checkNotNull(call.parameters["role"])),
                    )
                )
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.signedInClient(role: String): HttpClient =
        createClient {
            install(HttpCookies)
        }
        .also { client ->
            assertEquals(HttpStatusCode.OK, client.post("/test/sign-in/$role").status)
        }

    private suspend fun antiforgeryToken(client: HttpClient): String =
        Json.parseToJsonElement(client.get("/api/antiforgery/token").bodyAsText())
            .jsonObject
            .getValue("requestToken")
            .jsonPrimitive
            .content

    private suspend fun assertApiError(
        response: HttpResponse,
        status: HttpStatusCode,
        message: String,
        errors: Map<String, List<String>> = emptyMap(),
    ) {
        assertEquals(status, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        assertEquals(
            apiErrorJson.encodeToJsonElement(ApiError(message, errors)).jsonObject,
            Json.parseToJsonElement(response.bodyAsText()).jsonObject,
        )
    }

    private class StubPromotionOperations : PromotionOperations {
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var lastRequestedId: Long? = null
        var lastCreated: PromotionInput? = null
        var lastUpdated: PromotionInput? = null
        var listResult: OperationResult<List<Promotion>>? = null
        var getResult: OperationResult<Promotion>? = null
        var createResult: OperationResult<Promotion>? = null
        var updateResult: OperationResult<Promotion>? = null
        var deleteResult: OperationResult<Unit>? = null

        val operationCalls: Int
            get() = listCalls + getCalls + createCalls + updateCalls + deleteCalls

        override suspend fun list(): OperationResult<List<Promotion>> {
            listCalls++
            return listResult ?: OperationResult.Success(listOf(promotion(1)))
        }

        override suspend fun get(id: Long): OperationResult<Promotion> {
            getCalls++
            lastRequestedId = id
            return getResult ?: OperationResult.Success(promotion(id))
        }

        override suspend fun create(input: PromotionInput): OperationResult<Promotion> {
            createCalls++
            lastCreated = input
            return createResult ?: OperationResult.Success(promotion(42))
        }

        override suspend fun update(
            id: Long,
            input: PromotionInput,
        ): OperationResult<Promotion> {
            updateCalls++
            lastRequestedId = id
            lastUpdated = input
            return updateResult ?: OperationResult.Success(promotion(id))
        }

        override suspend fun delete(id: Long): OperationResult<Unit> {
            deleteCalls++
            lastRequestedId = id
            return deleteResult ?: OperationResult.Success(Unit)
        }

        private fun promotion(id: Long): Promotion =
            Promotion(
                id = id,
                name = "Summer sale",
                couponCode = "SUMMER10",
                discount = Discount.Percentage(BigDecimal("10.00")),
                startsAt = null,
                endsAt = null,
                usageLimitTotal = null,
                usageLimitPerUser = null,
                isActive = true,
                redemptionCount = 0,
                isLocked = false,
            )
    }

    private companion object {
        val apiErrorJson = Json { encodeDefaults = true }
    }
}
