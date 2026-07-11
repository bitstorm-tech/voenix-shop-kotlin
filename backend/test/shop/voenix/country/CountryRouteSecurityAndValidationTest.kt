package shop.voenix.country

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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import shop.voenix.auth.ApplicationAuth
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.countryModule
import shop.voenix.http.HttpRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountryRouteSecurityAndValidationTest {
    @Test
    fun `all admin routes enforce role before csrf and body handling`() = testApplication {
        val countries = StubCountryOperations()
        application { installCountryTestApplication(countries) }

        val anonymousResponses =
            listOf(
                client.get("/api/admin/countries"),
                client.get("/api/admin/countries/1"),
                client.post("/api/admin/countries"),
                client.put("/api/admin/countries/1"),
                client.delete("/api/admin/countries/1"),
            )
        anonymousResponses.forEach { response ->
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("Authentication required"))
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/API/ADMIN/COUNTRIES/").status)
        assertMalformedIdsNeverReachAuthentication(client)
        assertEquals(0, countries.operationCalls)

        val customer = signedInClient("CUSTOMER")
        val customerResponses =
            listOf(
                customer.get("/api/admin/countries"),
                customer.get("/api/admin/countries/1"),
                customer.post("/api/admin/countries"),
                customer.put("/api/admin/countries/1"),
                customer.delete("/api/admin/countries/1"),
            )
        customerResponses.forEach { response ->
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("Admin access required"))
        }
        assertMalformedIdsNeverReachAuthentication(customer)
        assertEquals(0, countries.operationCalls)

        val admin = signedInClient("ADMIN")
        assertMalformedIdsNeverReachAuthentication(admin)
        assertEquals(HttpStatusCode.OK, admin.get("/api/admin/countries").status)
        assertEquals(HttpStatusCode.OK, admin.get("/api/admin/countries/1").status)
        assertEquals(HttpStatusCode.OK, admin.get("/API/ADMIN/COUNTRIES/").status)
        assertEquals(HttpStatusCode.OK, admin.get("/API/ADMIN/COUNTRIES/1/").status)

        val writeCallsBeforeCsrfRejections = countries.writeCalls
        val missingCsrfResponses =
            listOf(
                admin.post("/api/admin/countries"),
                admin.put("/api/admin/countries/1"),
                admin.delete("/api/admin/countries/1"),
            )
        missingCsrfResponses.forEach { response ->
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                ContentType.Application.ProblemJson.withCharset(Charsets.UTF_8),
                response.contentType(),
            )
            assertTrue(response.bodyAsText().contains("\"title\":\"Bad Request\""))
        }
        assertEquals(writeCallsBeforeCsrfRejections, countries.writeCalls)

        antiforgeryToken(admin)
        val invalidCsrfResponses =
            listOf(
                admin.post("/api/admin/countries") { header(ApplicationAuth.CSRF_HEADER, "invalid") },
                admin.put("/api/admin/countries/1") { header(ApplicationAuth.CSRF_HEADER, "invalid") },
                admin.delete("/api/admin/countries/1") { header(ApplicationAuth.CSRF_HEADER, "invalid") },
            )
        invalidCsrfResponses.forEach { response ->
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                ContentType.Application.ProblemJson.withCharset(Charsets.UTF_8),
                response.contentType(),
            )
            assertTrue(response.bodyAsText().contains("\"title\":\"Bad Request\""))
        }
        assertEquals(writeCallsBeforeCsrfRejections, countries.writeCalls)
    }

    @Test
    fun `create and update validate payloads before calling the service`() = testApplication {
        val countries = StubCountryOperations()
        application { installCountryTestApplication(countries) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)
        val invalidPayloads =
            listOf(
                """{"name":"   ","countryCode":"DE"}""" to "Name is required",
                """{"name":"${"A".repeat(256)}","countryCode":"DE"}""" to
                    "Name must be at most 255 characters",
                """{"name":"Germany","countryCode":"D"}""" to
                    "Country code must be exactly 2 characters",
                """{"name":"Germany","countryCode":"GER"}""" to
                    "Country code must be exactly 2 characters",
                """{"name":"Germany","countryCode":"D1"}""" to
                    "Country code must contain only letters",
                """{"name":"Germany","countryCode":"   "}""" to
                    "Country code is required",
            )

        invalidPayloads.forEach { (body, message) ->
            val create =
                admin.post("/api/admin/countries") {
                    header(ApplicationAuth.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, create.status)
            assertEquals(
                ContentType.Application.ProblemJson.withCharset(Charsets.UTF_8),
                create.contentType(),
            )
            assertTrue(create.bodyAsText().contains(message))

            val update =
                admin.put("/api/admin/countries/1") {
                    header(ApplicationAuth.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, update.status)
            assertTrue(update.bodyAsText().contains(message))
        }

        assertEquals(0, countries.createCalls)
        assertEquals(0, countries.updateCalls)

        val caseInsensitiveInput =
            admin.post("/api/admin/countries") {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"Name":" Denmark ","COUNTRYCODE":" dk ","ignored":true}""",
                )
            }
        assertEquals(HttpStatusCode.Created, caseInsensitiveInput.status)
        assertEquals("Denmark", countries.lastCreated?.name?.trim())
        assertEquals("dk", countries.lastCreated?.countryCode?.trim())
    }

    @Test
    fun `country results map to the compatible http errors`() = testApplication {
        val countries = StubCountryOperations()
        application { installCountryTestApplication(countries) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        countries.getResult = CountryResult.NotFound
        val missing = admin.get("/api/admin/countries/999")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("""{"status":404,"detail":"Country not found"}""", missing.bodyAsText())

        countries.createResult = CountryResult.NameConflict
        val nameConflict = admin.createCountry(token)
        assertEquals(HttpStatusCode.Conflict, nameConflict.status)
        assertTrue(nameConflict.bodyAsText().contains("Country name already exists"))

        countries.createResult = CountryResult.CodeConflict
        val codeConflict = admin.createCountry(token)
        assertEquals(HttpStatusCode.Conflict, codeConflict.status)
        assertTrue(codeConflict.bodyAsText().contains("Country code already exists"))

        countries.createResult = CountryResult.DatabaseError
        val createDatabaseError = admin.createCountry(token)
        assertEquals(HttpStatusCode.InternalServerError, createDatabaseError.status)
        assertEquals(
            """{"status":500,"detail":"Internal server error"}""",
            createDatabaseError.bodyAsText(),
        )

        countries.updateResult = CountryResult.NotFound
        assertEquals(HttpStatusCode.NotFound, admin.updateCountry(token).status)
        countries.updateResult = CountryResult.NameConflict
        assertEquals(HttpStatusCode.Conflict, admin.updateCountry(token).status)
        countries.updateResult = CountryResult.CodeConflict
        assertEquals(HttpStatusCode.Conflict, admin.updateCountry(token).status)

        countries.deleteResult = CountryResult.NotFound
        assertEquals(HttpStatusCode.NotFound, admin.deleteCountry(token).status)
        countries.deleteResult = CountryResult.DatabaseError
        assertEquals(HttpStatusCode.InternalServerError, admin.deleteCountry(token).status)

        countries.listAdminResult = CountryResult.DatabaseError
        assertEquals(HttpStatusCode.InternalServerError, admin.get("/api/admin/countries").status)
        countries.listPublicResult = CountryResult.DatabaseError
        assertEquals(HttpStatusCode.InternalServerError, client.get("/api/countries").status)

        countries.createResult = CountryResult.Invalid("Name", "Name is required")
        assertEquals(HttpStatusCode.InternalServerError, admin.createCountry(token).status)

        val getCalls = countries.getCalls
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/admin/countries/not-a-long").status)
        assertEquals(
            HttpStatusCode.NotFound,
            admin.get("/api/admin/countries/999999999999999999999999").status,
        )
        assertEquals(getCalls, countries.getCalls)
    }

    @Test
    fun `body binding preserves unsupported media and json error contracts`() = testApplication {
        val countries = StubCountryOperations()
        application { installCountryTestApplication(countries) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        val missingContentType =
            admin.post("/api/admin/countries") {
                header(ApplicationAuth.CSRF_HEADER, token)
            }
        assertEquals(HttpStatusCode.UnsupportedMediaType, missingContentType.status)
        assertEquals(
            ContentType.Application.ProblemJson.withCharset(Charsets.UTF_8),
            missingContentType.contentType(),
        )
        assertTrue(missingContentType.bodyAsText().contains("\"status\":415"))

        val textPlain =
            admin.post("/api/admin/countries") {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.Text.Plain)
                setBody("""{"name":"Denmark","countryCode":"DK"}""")
            }
        assertEquals(HttpStatusCode.UnsupportedMediaType, textPlain.status)

        val textJson =
            admin.post("/api/admin/countries") {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType("text", "json"))
                setBody("""{"name":"Denmark","countryCode":"DK"}""")
            }
        assertEquals(HttpStatusCode.Created, textJson.status)

        val duplicateCaseVariants =
            admin.post("/api/admin/countries") {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"first","Name":"second","name":"third","countryCode":"DK"}""",
                )
            }
        assertEquals(HttpStatusCode.Created, duplicateCaseVariants.status)
        assertEquals("third", countries.lastCreated?.name)

        val unsupportedCharset =
            admin.post("/api/admin/countries") {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.parse("application/json; charset=iso-8859-1"))
                setBody("""{"name":"Denmark","countryCode":"DK"}""")
            }
        assertEquals(HttpStatusCode.UnsupportedMediaType, unsupportedCharset.status)

        listOf("", "null").forEach { body ->
            val emptyRequest =
                admin.post("/api/admin/countries") {
                    header(ApplicationAuth.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, emptyRequest.status)
            assertTrue(
                emptyRequest.bodyAsText().contains(
                    "\"\":[\"A non-empty request body is required.\"]",
                ),
            )
            assertTrue(emptyRequest.bodyAsText().contains("\"request\""))
        }

        val missingFields =
            admin.post("/api/admin/countries") {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        assertEquals(HttpStatusCode.BadRequest, missingFields.status)
        assertTrue(missingFields.bodyAsText().contains("\"Name\":[\"Name is required\"]"))
        assertTrue(
            missingFields.bodyAsText().contains(
                "\"CountryCode\":[\"Country code is required\"]",
            ),
        )

        listOf("[]" to 1, "123" to 3).forEach { (body, position) ->
            val topLevelValue =
                admin.post("/api/admin/countries") {
                    header(ApplicationAuth.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, topLevelValue.status)
            assertTrue(topLevelValue.bodyAsText().contains("\"$\""))
            assertTrue(topLevelValue.bodyAsText().contains("BytePositionInLine: $position."))
        }

        val malformed =
            admin.post("/api/admin/countries") {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":""")
            }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertTrue(malformed.bodyAsText().contains("\"request\":[\"The request field is required.\"]"))
        assertTrue(malformed.bodyAsText().contains("\"$.name\""))
        assertTrue(malformed.bodyAsText().contains("BytePositionInLine: 8."))

        val nonString =
            admin.post("/api/admin/countries") {
                header(ApplicationAuth.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody("""{"name":123,"countryCode":"DE"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, nonString.status)
        assertTrue(nonString.bodyAsText().contains("\"$.name\""))
        assertTrue(nonString.bodyAsText().contains("BytePositionInLine: 11."))
        assertEquals(2, countries.createCalls)
    }

    @Test
    fun `validation errors continue valid traceparents with a new span`() = testApplication {
        val countries = StubCountryOperations()
        application { installCountryTestApplication(countries) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)
        val trace = "11111111111111111111111111111111"
        val parentSpan = "2222222222222222"

        val continued =
            admin.post("/api/admin/countries") {
                header(ApplicationAuth.CSRF_HEADER, token)
                header("traceparent", "00-$trace-$parentSpan-01")
                contentType(ContentType.Application.Json)
                setBody("""{"name":" ","countryCode":"DE"}""")
            }
        val continuedTrace = traceId(continued.bodyAsText())
        assertTrue(continuedTrace.startsWith("00-$trace-"))
        assertTrue(continuedTrace.endsWith("-01"))
        assertTrue(continuedTrace.split('-')[2] != parentSpan)

        val invalidParent =
            admin.post("/api/admin/countries") {
                header(ApplicationAuth.CSRF_HEADER, token)
                header("traceparent", "00-$trace-0000000000000000-01")
                contentType(ContentType.Application.Json)
                setBody("""{"name":" ","countryCode":"DE"}""")
            }
        val freshTrace = traceId(invalidParent.bodyAsText())
        assertTrue(!freshTrace.startsWith("00-$trace-"))
        assertTrue(freshTrace.endsWith("-00"))
    }

    private fun Application.installCountryTestApplication(countries: CountryOperations) {
        HttpRuntime.install(this)
        ApplicationAuth.install(this, AuthSettings("country-route-contract-session-secret"))
        countryModule(countries)
        routing {
            post("/test/sign-in/{role}") {
                call.sessions.set(
                    UserSession(
                        userId = call.request.queryParameters["userId"] ?: "11",
                        roles = checkNotNull(call.parameters["role"]).split(',').toSet(),
                    ),
                )
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.signedInClient(
        role: String,
    ): HttpClient =
        createClient { install(HttpCookies) }.also { client ->
            signIn(client, role)
        }

    private suspend fun signIn(
        client: HttpClient,
        role: String,
        userId: String = "11",
    ) {
        assertEquals(
            HttpStatusCode.OK,
            client.post("/test/sign-in/$role?userId=$userId").status,
        )
    }

    private suspend fun antiforgeryToken(client: HttpClient): String {
        val body = client.get("/api/antiforgery/token").bodyAsText()
        return Regex("\"requestToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: error("No antiforgery token in response: $body")
    }

    private fun traceId(body: String): String =
        Regex("\"traceId\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: error("No traceId in response: $body")

    private suspend fun assertMalformedIdsNeverReachAuthentication(client: HttpClient) {
        listOf("not-a-long", "999999999999999999999999").forEach { id ->
            assertEquals(HttpStatusCode.NotFound, client.get("/api/admin/countries/$id").status)
            assertEquals(HttpStatusCode.NotFound, client.put("/api/admin/countries/$id").status)
            assertEquals(HttpStatusCode.NotFound, client.delete("/api/admin/countries/$id").status)
        }
    }

    private suspend fun HttpClient.createCountry(token: String) =
        post("/api/admin/countries") {
            header(ApplicationAuth.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Denmark","countryCode":"DK"}""")
        }

    private suspend fun HttpClient.updateCountry(token: String) =
        put("/api/admin/countries/1") {
            header(ApplicationAuth.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Denmark","countryCode":"DK"}""")
        }

    private suspend fun HttpClient.deleteCountry(token: String) =
        delete("/api/admin/countries/1") {
            header(ApplicationAuth.CSRF_HEADER, token)
        }

    private class StubCountryOperations : CountryOperations {
        var createCalls = 0
        var updateCalls = 0
        var getCalls = 0
        var listAdminCalls = 0
        var deleteCalls = 0
        var lastCreated: CreateAdminCountryRequest? = null
        var listPublicResult: CountryResult<CountryListResponse> =
            CountryResult.Success(CountryListResponse(emptyList()))
        var listAdminResult: CountryResult<AdminCountryListResponse> =
            CountryResult.Success(AdminCountryListResponse(emptyList()))
        var getResult: CountryResult<AdminCountryDto>? = null
        var createResult: CountryResult<AdminCountryDto>? = null
        var updateResult: CountryResult<AdminCountryDto>? = null
        var deleteResult: CountryResult<Unit> = CountryResult.Success(Unit)

        override suspend fun listPublic(): CountryResult<CountryListResponse> =
            listPublicResult

        val operationCalls: Int
            get() = listAdminCalls + getCalls + writeCalls

        val writeCalls: Int
            get() = createCalls + updateCalls + deleteCalls

        override suspend fun listAdmin(): CountryResult<AdminCountryListResponse> {
            listAdminCalls++
            return listAdminResult
        }

        override suspend fun get(id: Long): CountryResult<AdminCountryDto> {
            getCalls++
            return getResult ?: CountryResult.Success(AdminCountryDto(id, "Germany", "DE"))
        }

        override suspend fun create(request: CreateAdminCountryRequest): CountryResult<AdminCountryDto> {
            createCalls++
            lastCreated = request
            createResult?.let { return it }
            return CountryResult.Success(
                AdminCountryDto(
                    id = 42,
                    name = checkNotNull(request.name).trim(),
                    countryCode = checkNotNull(request.countryCode).trim().uppercase(),
                ),
            )
        }

        override suspend fun update(
            id: Long,
            request: UpdateAdminCountryRequest,
        ): CountryResult<AdminCountryDto> {
            updateCalls++
            updateResult?.let { return it }
            return CountryResult.Success(
                AdminCountryDto(
                    id = id,
                    name = checkNotNull(request.name).trim(),
                    countryCode = checkNotNull(request.countryCode).trim().uppercase(),
                ),
            )
        }

        override suspend fun delete(id: Long): CountryResult<Unit> {
            deleteCalls++
            return deleteResult
        }
    }
}
