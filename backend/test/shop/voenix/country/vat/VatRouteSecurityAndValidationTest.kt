package shop.voenix.country.vat

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
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
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
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
import shop.voenix.installHttpRuntime
import shop.voenix.vat.Vat
import shop.voenix.vat.VatInput
import shop.voenix.vat.VatOperations
import shop.voenix.vat.VatResult
import shop.voenix.vatModule

class VatRouteSecurityAndValidationTest {
    @Test
    fun `admin routes reject requests before body binding or vat operations`() = testApplication {
        val vats = StubVatOperations()
        application { installVatTestApplication(vats) }

        listOf(
                client.get("/api/admin/vat"),
                client.get("/api/admin/vat/1"),
                client.post("/api/admin/vat"),
                client.put("/api/admin/vat/1"),
                client.delete("/api/admin/vat/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(0, vats.operationCalls)

        val customer = signedInClient("CUSTOMER")
        assertEquals(HttpStatusCode.Forbidden, customer.get("/api/admin/vat").status)
        assertEquals(HttpStatusCode.Forbidden, customer.post("/api/admin/vat").status)
        assertEquals(0, vats.operationCalls)

        val admin = signedInClient("ADMIN")
        listOf(
                admin.post("/api/admin/vat"),
                admin.put("/api/admin/vat/1"),
                admin.delete("/api/admin/vat/1"),
            )
            .forEach { response ->
                assertApiError(response.status, response.bodyAsText(), "Invalid CSRF token")
            }
        assertEquals(0, vats.operationCalls)
    }

    @Test
    fun `http validation rejects before vat operations and uses lower camel case fields`() =
        testApplication {
            val vats = StubVatOperations()
            application { installVatTestApplication(vats) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            val response =
                admin.post("/api/admin/vat") {
                    header(ApplicationAuth.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"   ","percent":101,"isDefault":false}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                apiErrorJson
                    .encodeToJsonElement(
                        ApiError(
                            "Validation failed",
                            linkedMapOf(
                                "name" to listOf("Name is required"),
                                "percent" to listOf("Percent must be between 0 and 100"),
                            ),
                        )
                    )
                    .jsonObject,
                Json.parseToJsonElement(response.bodyAsText()).jsonObject,
            )
            assertEquals(0, vats.operationCalls)
        }

    @Test
    fun `vat results preserve success and failure http contracts`() = testApplication {
        val vats = StubVatOperations()
        application { installVatTestApplication(vats) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        assertEquals(HttpStatusCode.OK, admin.get("/api/admin/vat").status)
        assertEquals(HttpStatusCode.OK, admin.get("/api/admin/vat/42").status)

        val created =
            admin.post("/api/admin/vat") {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":" Standard ","percent":19,"description":" Rate ","isDefault":true}"""
                )
            }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("/api/admin/vat/42", created.headers[HttpHeaders.Location])

        val updated =
            admin.put("/api/admin/vat/42") {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Reduced","percent":7,"isDefault":false}""")
            }
        assertEquals(HttpStatusCode.OK, updated.status)

        val deleted =
            admin.delete("/api/admin/vat/42") { header(ApplicationAuth.CSRF_HEADER, token) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)

        vats.getResult = VatResult.NotFound
        val missing = admin.get("/api/admin/vat/404")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertApiError(missing.status, missing.bodyAsText(), "VAT not found")

        vats.createResult = VatResult.Conflict
        val conflict =
            admin.post("/api/admin/vat") {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Standard","percent":19}""")
            }
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertApiError(conflict.status, conflict.bodyAsText(), "VAT entry already exists")

        vats.listResult = VatResult.DatabaseError
        val failure = admin.get("/api/admin/vat")
        assertEquals(HttpStatusCode.InternalServerError, failure.status)
        assertApiError(failure.status, failure.bodyAsText(), "Internal server error")
    }

    private fun Application.installVatTestApplication(vats: VatOperations) {
        installHttpRuntime()
        ApplicationAuth.install(this, AuthSettings("vat-route-contract-session-secret"))
        vatModule(vats)
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

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.signedInClient(
        role: String
    ): HttpClient = createClient {
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

    private fun assertApiError(
        status: HttpStatusCode,
        body: String,
        message: String,
    ) {
        assertTrue(status.value >= 400)
        assertEquals(
            apiErrorJson.encodeToJsonElement(ApiError(message)).jsonObject,
            Json.parseToJsonElement(body).jsonObject,
        )
    }

    private class StubVatOperations : VatOperations {
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var listResult: VatResult<List<Vat>> = VatResult.Success(emptyList())
        var getResult: VatResult<Vat>? = null
        var createResult: VatResult<Vat>? = null

        val operationCalls: Int
            get() = listCalls + getCalls + createCalls + updateCalls + deleteCalls

        override suspend fun list(): VatResult<List<Vat>> {
            listCalls++
            return listResult
        }

        override suspend fun get(id: Long): VatResult<Vat> {
            getCalls++
            return getResult ?: VatResult.Success(Vat(id, "Standard", 19, null, true))
        }

        override suspend fun create(input: VatInput): VatResult<Vat> {
            createCalls++
            return createResult
                ?: VatResult.Success(
                    Vat(
                        42,
                        input.name.orEmpty().trim(),
                        input.percent ?: 0,
                        input.description?.trim(),
                        input.isDefault,
                    )
                )
        }

        override suspend fun update(
            id: Long,
            input: VatInput,
        ): VatResult<Vat> {
            updateCalls++
            return VatResult.Success(
                Vat(
                    id,
                    input.name.orEmpty(),
                    input.percent ?: 0,
                    input.description,
                    input.isDefault,
                )
            )
        }

        override suspend fun delete(id: Long): VatResult<Unit> {
            deleteCalls++
            return VatResult.Success(Unit)
        }
    }

    private companion object {
        val apiErrorJson = Json { encodeDefaults = true }
    }
}
