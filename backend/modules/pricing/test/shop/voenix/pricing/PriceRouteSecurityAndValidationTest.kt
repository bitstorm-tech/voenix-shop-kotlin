package shop.voenix.pricing

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.auth.ApplicationAuth
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.http.installHttpRuntime
import shop.voenix.operation.OperationResult
import shop.voenix.pricing.installPricingFeature
import shop.voenix.vat.Vat

internal class PriceRouteSecurityAndValidationTest {
    @Test
    fun `admin routes reject before body binding or price operations`() = testApplication {
        val prices = StubPriceOperations()
        application { installPriceTestApplication(prices) }

        listOf(
                client.post("/api/admin/prices"),
                client.post("/api/admin/prices/calculate"),
                client.get("/api/admin/prices/default"),
                client.get("/api/admin/prices/1"),
                client.put("/api/admin/prices/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(0, prices.operationCalls)

        val customer = signedInClient("CUSTOMER")
        assertEquals(HttpStatusCode.Forbidden, customer.get("/api/admin/prices/1").status)
        assertEquals(HttpStatusCode.Forbidden, customer.post("/api/admin/prices").status)
        assertEquals(0, prices.operationCalls)

        val admin = signedInClient("ADMIN")
        listOf(
                admin.post("/api/admin/prices"),
                admin.post("/api/admin/prices/calculate"),
                admin.put("/api/admin/prices/1"),
            )
            .forEach { response ->
                assertApiError(response, HttpStatusCode.BadRequest, "Invalid CSRF token")
            }
        assertEquals(0, prices.operationCalls)
    }

    @Test
    fun `http validation rejects active invalid fields before operations`() = testApplication {
        val prices = StubPriceOperations()
        application { installPriceTestApplication(prices) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        val response =
            admin.post("/api/admin/prices/calculate") {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "purchaseVatId": 0,
                      "purchaseActiveRow": "COST_PERCENT",
                      "purchasePriceInputCents": -1,
                      "purchaseCostPercent": 12.345,
                      "salesVatId": 0,
                      "salesMarginPercent": 0,
                      "salesTotalInputCents": -1
                    }
                    """
                        .trimIndent()
                )
            }

        assertApiError(
            response,
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf(
                "purchaseVatId" to listOf("Purchase VAT id is required"),
                "salesVatId" to listOf("Sales VAT id is required"),
                "purchasePriceInputCents" to listOf("Purchase price input must not be negative"),
                "purchaseCostPercent" to
                    listOf("Purchase cost percent must have at most two decimal places"),
                "salesTotalInputCents" to listOf("Sales total input must not be negative"),
            ),
        )
        assertEquals(0, prices.operationCalls)

        listOf(
                validInputJson.replace("\"NET\"", "\"INVALID\""),
                validInputJson.replace("12.34", "\"12.34\""),
            )
            .forEach { invalidJson ->
                val invalidBody =
                    admin.post("/api/admin/prices/calculate") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(invalidJson)
                    }
                assertApiError(
                    invalidBody,
                    HttpStatusCode.BadRequest,
                    "Invalid request body",
                )
            }
        assertEquals(0, prices.operationCalls)
    }

    @Test
    fun `price routes preserve create and calculation contracts`() = testApplication {
        val prices = StubPriceOperations()
        application { installPriceTestApplication(prices) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        val created = admin.writePrice("/api/admin/prices", token, isPut = false)
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("/api/admin/prices/42", created.headers[HttpHeaders.Location])
        assertEquals(
            42,
            Json.parseToJsonElement(created.bodyAsText())
                .jsonObject["id"]
                ?.jsonPrimitive
                ?.content
                ?.toLong(),
        )

        val calculated = admin.writePrice("/api/admin/prices/calculate", token, isPut = false)
        assertEquals(HttpStatusCode.OK, calculated.status)
        val calculatedJson = Json.parseToJsonElement(calculated.bodyAsText()).jsonObject
        assertEquals("NET", calculatedJson["purchaseCalculationMode"]?.jsonPrimitive?.content)
        assertEquals("12.34", calculatedJson["purchaseCostPercent"]?.jsonPrimitive?.content)
        assertFalse(calculatedJson["purchaseCostPercent"]?.jsonPrimitive?.isString ?: true)
        assertEquals("null", calculatedJson["id"].toString())
        val purchaseVat = calculatedJson["purchaseVat"]?.jsonObject
        assertEquals(
            setOf("id", "name", "percent", "description", "isDefault"),
            purchaseVat?.keys,
        )
        assertEquals("Standard", purchaseVat?.get("name")?.jsonPrimitive?.content)
        assertEquals("null", purchaseVat?.get("description").toString())
        assertEquals("true", purchaseVat?.get("isDefault")?.jsonPrimitive?.content)

        assertEquals(HttpStatusCode.OK, admin.get("/api/admin/prices/default").status)
        assertEquals(HttpStatusCode.OK, admin.get("/api/admin/prices/42").status)
        assertEquals(
            HttpStatusCode.OK,
            admin.writePrice("/api/admin/prices/42", token, isPut = true).status,
        )
    }

    @Test
    fun `price operation failures map to stable api errors`() = testApplication {
        val prices = StubPriceOperations()
        application { installPriceTestApplication(prices) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        prices.getResult = OperationResult.NotFound
        assertApiError(
            admin.get("/api/admin/prices/404"),
            HttpStatusCode.NotFound,
            "Price not found",
        )

        assertApiError(
            admin.get("/api/admin/prices/not-a-number"),
            HttpStatusCode.BadRequest,
            "Invalid price id",
        )

        prices.defaultResult = OperationResult.Invalid(emptyMap())
        assertApiError(
            admin.get("/api/admin/prices/default"),
            HttpStatusCode.BadRequest,
            "No VAT is configured",
        )

        val vatErrors = mapOf("purchaseVatId" to listOf("Purchase VAT not found"))
        prices.createResult = OperationResult.Invalid(vatErrors)
        assertApiError(
            admin.writePrice("/api/admin/prices", token, isPut = false),
            HttpStatusCode.BadRequest,
            "Validation failed",
            vatErrors,
        )

        prices.calculateResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.writePrice("/api/admin/prices/calculate", token, isPut = false),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    private fun Application.installPriceTestApplication(prices: PriceOperations) {
        installHttpRuntime()
        install(RequestValidation) { validatePricingRequests() }
        ApplicationAuth.install(this, AuthSettings("price-route-contract-session-secret"))
        installPricingFeature(prices)
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
        .also { signedIn ->
            assertEquals(HttpStatusCode.OK, signedIn.post("/test/sign-in/$role").status)
        }

    private suspend fun antiforgeryToken(client: HttpClient): String {
        val body = client.get("/api/antiforgery/token").bodyAsText()
        return Regex("\"requestToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: error("No antiforgery token in response: $body")
    }

    private suspend fun HttpClient.writePrice(
        path: String,
        token: String,
        isPut: Boolean,
    ) =
        if (isPut) {
            put(path) {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody(validInputJson)
            }
        } else {
            post(path) {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody(validInputJson)
            }
        }

    private suspend fun assertApiError(
        response: io.ktor.client.statement.HttpResponse,
        status: HttpStatusCode,
        message: String,
        errors: Map<String, List<String>> = emptyMap(),
    ) {
        assertEquals(status, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(message, json["message"]?.jsonPrimitive?.content)
        assertEquals(errors.keys, json["errors"]?.jsonObject?.keys)
    }

    private class StubPriceOperations : PriceOperations {
        var calculateCalls = 0
        var createCalls = 0
        var defaultCalls = 0
        var getCalls = 0
        var updateCalls = 0
        var calculateResult: OperationResult<CalculatedPrice>? = null
        var createResult: OperationResult<CalculatedPrice>? = null
        var defaultResult: OperationResult<CalculatedPrice>? = null
        var getResult: OperationResult<CalculatedPrice>? = null

        val operationCalls: Int
            get() = calculateCalls + createCalls + defaultCalls + getCalls + updateCalls

        override suspend fun calculate(input: PriceInput): OperationResult<CalculatedPrice> {
            calculateCalls++
            return calculateResult ?: OperationResult.Success(calculatedPrice(id = null, input))
        }

        override suspend fun create(input: PriceInput): OperationResult<CalculatedPrice> {
            createCalls++
            return createResult ?: OperationResult.Success(calculatedPrice(id = 42, input))
        }

        override suspend fun default(): OperationResult<CalculatedPrice> {
            defaultCalls++
            return defaultResult ?: OperationResult.Success(calculatedPrice(id = null))
        }

        override suspend fun get(id: Long): OperationResult<CalculatedPrice> {
            getCalls++
            return getResult ?: OperationResult.Success(calculatedPrice(id = id))
        }

        override suspend fun update(
            id: Long,
            input: PriceInput,
        ): OperationResult<CalculatedPrice> {
            updateCalls++
            return OperationResult.Success(calculatedPrice(id, input))
        }

        private fun calculatedPrice(
            id: Long?,
            input: PriceInput =
                PriceInput(
                    purchaseVatId = 1,
                    purchaseCostPercent = BigDecimal("12.34"),
                    salesVatId = 1,
                ),
        ): CalculatedPrice =
            PriceCalculator.calculate(
                id = id,
                input = input,
                purchaseVat = standardVat,
                salesVat = standardVat,
            )
    }

    private companion object {
        val standardVat = Vat(1, "Standard", 19, description = null, isDefault = true)
        val validInputJson =
            """
            {
              "purchaseVatId": 1,
              "purchaseCalculationMode": "NET",
              "purchaseActiveRow": "COST_PERCENT",
              "purchasePriceInputCents": 1000,
              "purchaseCostInputCents": 0,
              "purchaseCostPercent": 12.34,
              "salesVatId": 1,
              "salesCalculationMode": "GROSS",
              "salesActiveRow": "TOTAL",
              "salesMarginInputCents": 0,
              "salesMarginPercent": 0,
              "salesTotalInputCents": 1500
            }
            """
                .trimIndent()
    }
}
