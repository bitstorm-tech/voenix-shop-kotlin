package shop.voenix.country

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
import io.ktor.server.application.call
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
import shop.voenix.countryModule
import shop.voenix.http.ApiError
import shop.voenix.installHttpRuntime

class CountryRouteSecurityAndValidationTest {
    @Test
    fun `admin routes reject requests before body binding or country operations`() =
        testApplication {
            val countries = StubCountryOperations()
            application { installCountryTestApplication(countries) }

            listOf(
                    client.get("/api/admin/countries"),
                    client.get("/api/admin/countries/1"),
                    client.post("/api/admin/countries"),
                    client.put("/api/admin/countries/1"),
                    client.delete("/api/admin/countries/1"),
                )
                .forEach { response ->
                    assertEquals(HttpStatusCode.Unauthorized, response.status)
                    assertTrue(response.bodyAsText().contains("Authentication required"))
                }
            assertEquals(0, countries.operationCalls)

            val customer = signedInClient("CUSTOMER")
            listOf(
                    customer.get("/api/admin/countries"),
                    customer.get("/api/admin/countries/1"),
                    customer.post("/api/admin/countries"),
                    customer.put("/api/admin/countries/1"),
                    customer.delete("/api/admin/countries/1"),
                )
                .forEach { response ->
                    assertEquals(HttpStatusCode.Forbidden, response.status)
                    assertTrue(response.bodyAsText().contains("Admin access required"))
                }
            assertEquals(0, countries.operationCalls)

            val admin = signedInClient("ADMIN")
            listOf(
                    admin.post("/api/admin/countries"),
                    admin.put("/api/admin/countries/1"),
                    admin.delete("/api/admin/countries/1"),
                )
                .forEach { response ->
                    assertApiError(response, HttpStatusCode.BadRequest, "Invalid CSRF token")
                }
            assertEquals(0, countries.operationCalls)

            val token = antiforgeryToken(admin)
            listOf(
                    admin.post("/api/admin/countries") {
                        header(ApplicationAuth.CSRF_HEADER, "invalid")
                    },
                    admin.put("/api/admin/countries/1") {
                        header(ApplicationAuth.CSRF_HEADER, "invalid")
                    },
                    admin.delete("/api/admin/countries/1") {
                        header(ApplicationAuth.CSRF_HEADER, "invalid")
                    },
                )
                .forEach { response ->
                    assertApiError(response, HttpStatusCode.BadRequest, "Invalid CSRF token")
                }
            assertTrue(token.isNotBlank())
            assertEquals(0, countries.operationCalls)
        }

    @Test
    fun `only canonical routes match and ids are parsed after security checks`() = testApplication {
        val countries = StubCountryOperations()
        application { installCountryTestApplication(countries) }

        assertEquals(HttpStatusCode.NotFound, client.get("/API/ADMIN/COUNTRIES").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/admin/countries/").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/admin/countries/1/").status)
        assertEquals(0, countries.operationCalls)

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/admin/countries/not-a-long").status,
        )
        val customer = signedInClient("CUSTOMER")
        assertEquals(
            HttpStatusCode.Forbidden,
            customer.get("/api/admin/countries/not-a-long").status,
        )

        val admin = signedInClient("ADMIN")
        listOf("not-a-long", "999999999999999999999999").forEach { id ->
            assertApiError(
                admin.get("/api/admin/countries/$id"),
                HttpStatusCode.BadRequest,
                "Invalid country id",
            )
        }
        assertEquals(0, countries.operationCalls)

        val token = antiforgeryToken(admin)
        listOf(
                admin.put("/api/admin/countries/not-a-long") {
                    header(ApplicationAuth.CSRF_HEADER, token)
                },
                admin.delete("/api/admin/countries/not-a-long") {
                    header(ApplicationAuth.CSRF_HEADER, token)
                },
            )
            .forEach { response ->
                assertApiError(response, HttpStatusCode.BadRequest, "Invalid country id")
            }
        assertEquals(0, countries.operationCalls)
    }

    @Test
    fun `application json binds case-sensitive input and ignores unknown properties`() =
        testApplication {
            val countries = StubCountryOperations()
            application { installCountryTestApplication(countries) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            val created =
                admin.post("/api/admin/countries") {
                    header(ApplicationAuth.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":" Denmark ","countryCode":" dk ","ignored":true}""")
                }
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("/api/admin/countries/42", created.headers[HttpHeaders.Location])
            assertEquals(CountryInput(" Denmark ", " dk "), countries.lastCreated)

            val updated =
                admin.put("/api/admin/countries/42") {
                    header(ApplicationAuth.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Norway","countryCode":"NO","ignored":true}""")
                }
            assertEquals(HttpStatusCode.OK, updated.status)
            assertEquals(CountryInput("Norway", "NO"), countries.lastUpdated)

            val wrongPropertyCase =
                admin.post("/api/admin/countries") {
                    header(ApplicationAuth.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"Name":"Denmark","countryCode":"DK","unknown":"ignored"}""")
                }
            assertApiError(
                wrongPropertyCase,
                HttpStatusCode.BadRequest,
                "Validation failed",
                mapOf("name" to listOf("Name is required")),
            )
            assertEquals(1, countries.createCalls)
            assertEquals(CountryInput(" Denmark ", " dk "), countries.lastCreated)
        }

    @Test
    fun `binding failures return generic api errors without calling country operations`() =
        testApplication {
            val countries = StubCountryOperations()
            application { installCountryTestApplication(countries) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            val unsupported =
                listOf(
                    admin.post("/api/admin/countries") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                    },
                    admin.post("/api/admin/countries") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                        contentType(ContentType.Text.Plain)
                        setBody("""{"name":"Denmark","countryCode":"DK"}""")
                    },
                    admin.post("/api/admin/countries") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                        contentType(ContentType("text", "json"))
                        setBody("""{"name":"Denmark","countryCode":"DK"}""")
                    },
                    admin.post("/api/admin/countries") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                        contentType(ContentType("application", "vnd.voenix+json"))
                        setBody("""{"name":"Denmark","countryCode":"DK"}""")
                    },
                )
            unsupported.forEach { response ->
                assertApiError(
                    response,
                    HttpStatusCode.UnsupportedMediaType,
                    "Unsupported media type",
                )
            }

            listOf(
                    "",
                    "null",
                    "[]",
                    """{"name":"""",
                    """{"name":123,"countryCode":"DK"}""",
                )
                .forEach { body ->
                    val response =
                        admin.post("/api/admin/countries") {
                            header(ApplicationAuth.CSRF_HEADER, token)
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    assertApiError(response, HttpStatusCode.BadRequest, "Invalid request body")
                }
            assertEquals(0, countries.createCalls)
        }

    @Test
    fun `country results map to small api errors`() = testApplication {
        val countries = StubCountryOperations()
        application { installCountryTestApplication(countries) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        countries.getResult = CountryResult.NotFound
        assertApiError(
            admin.get("/api/admin/countries/999"),
            HttpStatusCode.NotFound,
            "Country not found",
        )

        countries.createResult = CountryResult.NameConflict
        assertApiError(
            admin.createCountry(token),
            HttpStatusCode.Conflict,
            "Country name already exists",
        )
        countries.createResult = CountryResult.CodeConflict
        assertApiError(
            admin.createCountry(token),
            HttpStatusCode.Conflict,
            "Country code already exists",
        )
        countries.createResult =
            CountryResult.Invalid(
                mapOf(
                    "name" to listOf("Name is required"),
                    "countryCode" to listOf("Country code is required"),
                )
            )
        assertApiError(
            admin.createCountry(token),
            HttpStatusCode.BadRequest,
            "Validation failed",
            mapOf(
                "name" to listOf("Name is required"),
                "countryCode" to listOf("Country code is required"),
            ),
        )
        countries.createResult = CountryResult.DatabaseError
        assertApiError(
            admin.createCountry(token),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )

        countries.deleteResult = CountryResult.NotFound
        assertApiError(
            admin.delete("/api/admin/countries/999") { header(ApplicationAuth.CSRF_HEADER, token) },
            HttpStatusCode.NotFound,
            "Country not found",
        )
        countries.listPublicResult = CountryResult.DatabaseError
        assertApiError(
            client.get("/api/countries"),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    private fun Application.installCountryTestApplication(countries: CountryOperations) {
        installHttpRuntime()
        ApplicationAuth.install(this, AuthSettings("country-route-contract-session-secret"))
        countryModule(countries)
        routing {
            post("/test/sign-in/{role}") {
                call.sessions.set(
                    UserSession(
                        userId = call.request.queryParameters["userId"] ?: "11",
                        roles = checkNotNull(call.parameters["role"]).split(',').toSet(),
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
        .also { client ->
            assertEquals(
                HttpStatusCode.OK,
                client.post("/test/sign-in/$role?userId=11").status,
            )
        }

    private suspend fun antiforgeryToken(client: HttpClient): String {
        val body = client.get("/api/antiforgery/token").bodyAsText()
        return Regex("\"requestToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: error("No antiforgery token in response: $body")
    }

    private suspend fun HttpClient.createCountry(token: String): HttpResponse =
        post("/api/admin/countries") {
            header(ApplicationAuth.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Denmark","countryCode":"DK"}""")
        }

    private suspend fun assertApiError(
        response: HttpResponse,
        status: HttpStatusCode,
        message: String,
        errors: Map<String, List<String>> = emptyMap(),
    ) {
        assertEquals(status, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(
            apiErrorJson.encodeToJsonElement(ApiError(message, errors)).jsonObject,
            body,
        )
    }

    private class StubCountryOperations : CountryOperations {
        var createCalls = 0
        var updateCalls = 0
        var getCalls = 0
        var listAdminCalls = 0
        var deleteCalls = 0
        var lastCreated: CountryInput? = null
        var lastUpdated: CountryInput? = null
        var listPublicResult: CountryResult<List<PublicCountry>> =
            CountryResult.Success(emptyList())
        var listAdminResult: CountryResult<List<Country>> = CountryResult.Success(emptyList())
        var getResult: CountryResult<Country>? = null
        var createResult: CountryResult<Country>? = null
        var updateResult: CountryResult<Country>? = null
        var deleteResult: CountryResult<Unit> = CountryResult.Success(Unit)

        val operationCalls: Int
            get() = listAdminCalls + getCalls + createCalls + updateCalls + deleteCalls

        override suspend fun listPublic(): CountryResult<List<PublicCountry>> = listPublicResult

        override suspend fun listAdmin(): CountryResult<List<Country>> {
            listAdminCalls++
            return listAdminResult
        }

        override suspend fun get(id: Long): CountryResult<Country> {
            getCalls++
            return getResult ?: CountryResult.Success(Country(id, "Germany", "DE"))
        }

        override suspend fun create(input: CountryInput): CountryResult<Country> {
            createCalls++
            lastCreated = input
            createResult?.let {
                return it
            }
            return CountryResult.Success(
                Country(
                    id = 42,
                    name = input.name.orEmpty(),
                    countryCode = input.countryCode.orEmpty(),
                )
            )
        }

        override suspend fun update(
            id: Long,
            input: CountryInput,
        ): CountryResult<Country> {
            updateCalls++
            lastUpdated = input
            updateResult?.let {
                return it
            }
            return CountryResult.Success(
                Country(
                    id = id,
                    name = input.name.orEmpty(),
                    countryCode = input.countryCode.orEmpty(),
                )
            )
        }

        override suspend fun delete(id: Long): CountryResult<Unit> {
            deleteCalls++
            return deleteResult
        }
    }

    private companion object {
        val apiErrorJson = Json { encodeDefaults = true }
    }
}
