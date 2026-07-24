package shop.voenix.promotion

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
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
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(0, promotions.operationCalls)

        val customer = signedInClient("CUSTOMER")
        assertEquals(HttpStatusCode.Forbidden, customer.get("/api/admin/promotions").status)
        assertEquals(HttpStatusCode.Forbidden, customer.get("/api/admin/promotions/1").status)
        assertEquals(0, promotions.operationCalls)

        val admin = signedInClient("ADMIN")
        assertApiError(
            admin.get("/api/admin/promotions/not-a-long"),
            HttpStatusCode.BadRequest,
            "Invalid promotion id",
        )
        assertEquals(0, promotions.operationCalls)
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

    private fun Application.installPromotionTestApplication(promotions: PromotionOperations) {
        installHttpRuntime()
        installAuthModule(AuthSettings("promotion-route-contract-session-secret"))
        installPromotionModule(promotions)
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

    private suspend fun assertApiError(
        response: HttpResponse,
        status: HttpStatusCode,
        message: String,
    ) {
        assertEquals(status, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        assertEquals(
            apiErrorJson.encodeToJsonElement(ApiError(message)).jsonObject,
            Json.parseToJsonElement(response.bodyAsText()).jsonObject,
        )
    }

    private class StubPromotionOperations : PromotionOperations {
        var listCalls = 0
        var getCalls = 0
        var lastRequestedId: Long? = null
        var listResult: OperationResult<List<Promotion>>? = null
        var getResult: OperationResult<Promotion>? = null

        val operationCalls: Int
            get() = listCalls + getCalls

        override suspend fun list(): OperationResult<List<Promotion>> {
            listCalls++
            return listResult ?: OperationResult.Success(listOf(promotion(1)))
        }

        override suspend fun get(id: Long): OperationResult<Promotion> {
            getCalls++
            lastRequestedId = id
            return getResult ?: OperationResult.Success(promotion(id))
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
