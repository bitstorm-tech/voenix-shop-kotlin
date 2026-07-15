package shop.voenix.supplier

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import shop.voenix.auth.ApplicationAuth
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.http.ApiError
import shop.voenix.http.installHttpRuntime
import shop.voenix.operation.OperationResult
import shop.voenix.supplier.installSupplierFeature

internal class SupplierRouteSecurityAndValidationTest {
    @Test
    fun `admin subtree rejects before id binding body binding or supplier operations`() =
        testApplication {
            val suppliers = StubSupplierOperations()
            application { installSupplierTestApplication(suppliers) }

            listOf(
                    client.get("/api/admin/suppliers"),
                    client.get("/api/admin/suppliers/not-a-long"),
                    client.post("/api/admin/suppliers"),
                    client.put("/api/admin/suppliers/not-a-long"),
                    client.delete("/api/admin/suppliers/not-a-long"),
                )
                .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
            assertEquals(0, suppliers.operationCalls)

            val customer = signedInClient("CUSTOMER")
            assertEquals(HttpStatusCode.Forbidden, customer.get("/api/admin/suppliers").status)
            assertEquals(HttpStatusCode.Forbidden, customer.post("/api/admin/suppliers").status)
            assertEquals(0, suppliers.operationCalls)

            val admin = signedInClient("ADMIN")
            listOf(
                    admin.post("/api/admin/suppliers"),
                    admin.put("/api/admin/suppliers/not-a-long"),
                    admin.delete("/api/admin/suppliers/not-a-long"),
                )
                .forEach { response ->
                    assertApiError(response, HttpStatusCode.BadRequest, "Invalid CSRF token")
                }
            assertEquals(0, suppliers.operationCalls)

            assertApiError(
                admin.get("/api/admin/suppliers/not-a-long"),
                HttpStatusCode.BadRequest,
                "Invalid supplier id",
            )
            val token = antiforgeryToken(admin)
            assertApiError(
                admin.put("/api/admin/suppliers/not-a-long") {
                    header(ApplicationAuth.CSRF_HEADER, token)
                },
                HttpStatusCode.BadRequest,
                "Invalid supplier id",
            )
            assertEquals(0, suppliers.operationCalls)
        }

    @Test
    fun `http validation rejects before supplier operations and valid bodies preserve contracts`() =
        testApplication {
            val suppliers = StubSupplierOperations()
            application { installSupplierTestApplication(suppliers) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            val invalid =
                admin.post("/api/admin/suppliers") {
                    header(ApplicationAuth.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"name":"   ","postalCode":"123456789012345678901","email":"invalid"}"""
                    )
                }
            assertApiError(
                invalid,
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf(
                    "name" to listOf("Name is required"),
                    "postalCode" to listOf("PostalCode must be at most 20 characters"),
                    "email" to listOf("Email must be a valid email address"),
                ),
            )
            assertEquals(0, suppliers.operationCalls)

            val created = admin.writeSupplier(HttpMethod.POST, token, id = null, name = " Acme ")
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("/api/admin/suppliers/42", created.headers[HttpHeaders.Location])
            assertEquals(SupplierInput(name = " Acme ", countryId = 1), suppliers.lastCreated)

            val updated = admin.writeSupplier(HttpMethod.PUT, token, id = 42, name = "Globex")
            assertEquals(HttpStatusCode.OK, updated.status)
            assertEquals(SupplierInput(name = "Globex", countryId = 1), suppliers.lastUpdated)
            assertTrue(updated.bodyAsText().contains("\"title\":null"))

            assertEquals(HttpStatusCode.OK, admin.get("/api/admin/suppliers").status)
            assertEquals(HttpStatusCode.OK, admin.get("/api/admin/suppliers/42").status)
            assertEquals(
                HttpStatusCode.NoContent,
                admin
                    .delete("/api/admin/suppliers/42") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                    }
                    .status,
            )
        }

    @Test
    fun `supplier results map to the required api errors`() = testApplication {
        val suppliers = StubSupplierOperations()
        application { installSupplierTestApplication(suppliers) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        suppliers.getResult = OperationResult.NotFound
        assertApiError(
            admin.get("/api/admin/suppliers/404"),
            HttpStatusCode.NotFound,
            "Supplier not found",
        )

        val countryErrors = mapOf("countryId" to listOf("Country not found"))
        suppliers.createResult = OperationResult.Invalid(countryErrors)
        assertApiError(
            admin.writeSupplier(HttpMethod.POST, token, id = null, name = "Acme"),
            HttpStatusCode.BadRequest,
            "Validation failed",
            countryErrors,
        )

        suppliers.listResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.get("/api/admin/suppliers"),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    private fun Application.installSupplierTestApplication(suppliers: SupplierOperations) {
        installHttpRuntime()
        install(RequestValidation) { validateSupplierRequests() }
        ApplicationAuth.install(this, AuthSettings("supplier-route-contract-session-secret"))
        installSupplierFeature(suppliers)
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

    private suspend fun antiforgeryToken(client: HttpClient): String {
        val body = client.get("/api/antiforgery/token").bodyAsText()
        return Regex("\"requestToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: error("No antiforgery token in response: $body")
    }

    private suspend fun HttpClient.writeSupplier(
        method: HttpMethod,
        token: String,
        id: Long?,
        name: String,
    ): HttpResponse {
        val path = id?.let { "/api/admin/suppliers/$it" } ?: "/api/admin/suppliers"
        val request: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(ApplicationAuth.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"$name","title":null,"firstName":null,"lastName":null,"street":null,"houseNumber":null,"city":null,"postalCode":null,"countryId":1,"phoneNumber1":null,"phoneNumber2":null,"phoneNumber3":null,"email":null,"website":null}"""
            )
        }
        return when (method) {
            HttpMethod.POST -> post(path, request)
            HttpMethod.PUT -> put(path, request)
        }
    }

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

    private class StubSupplierOperations : SupplierOperations {
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var lastCreated: SupplierInput? = null
        var lastUpdated: SupplierInput? = null
        var listResult: OperationResult<List<Supplier>> = OperationResult.Success(emptyList())
        var getResult: OperationResult<Supplier>? = null
        var createResult: OperationResult<Supplier>? = null
        var deleteResult: OperationResult<Unit> = OperationResult.Success(Unit)

        val operationCalls: Int
            get() = listCalls + getCalls + createCalls + updateCalls + deleteCalls

        override suspend fun list(): OperationResult<List<Supplier>> {
            listCalls++
            return listResult
        }

        override suspend fun get(id: Long): OperationResult<Supplier> {
            getCalls++
            return getResult ?: OperationResult.Success(supplier(id, "Acme"))
        }

        override suspend fun create(input: SupplierInput): OperationResult<Supplier> {
            createCalls++
            lastCreated = input
            return createResult ?: OperationResult.Success(supplier(42, input.name.orEmpty()))
        }

        override suspend fun update(
            id: Long,
            input: SupplierInput,
        ): OperationResult<Supplier> {
            updateCalls++
            lastUpdated = input
            return OperationResult.Success(supplier(id, input.name.orEmpty()))
        }

        override suspend fun delete(id: Long): OperationResult<Unit> {
            deleteCalls++
            return deleteResult
        }

        private fun supplier(
            id: Long,
            name: String,
        ): Supplier =
            Supplier(
                id = id,
                name = name,
                title = null,
                firstName = null,
                lastName = null,
                street = null,
                houseNumber = null,
                city = null,
                postalCode = null,
                countryId = null,
                country = null,
                phoneNumber1 = null,
                phoneNumber2 = null,
                phoneNumber3 = null,
                email = null,
                website = null,
            )
    }

    private enum class HttpMethod {
        POST,
        PUT,
    }

    private companion object {
        val apiErrorJson = Json { encodeDefaults = true }
    }
}
