package shop.voenix.production

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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import shop.voenix.article.tshirt.TshirtSyncReport
import shop.voenix.article.tshirt.TshirtSyncStatus
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.ApiError
import shop.voenix.http.installHttpRuntime
import shop.voenix.operation.OperationResult
import shop.voenix.spod.SpodEnvironment
import shop.voenix.spod.SpodError

internal class ProductionDestinationRouteSecurityAndValidationTest {
    @Test
    fun `admin subtree rejects before id binding body binding or destination operations`() =
        testApplication {
            val destinations = StubDestinationOperations()
            application { installDestinationTestApplication(destinations) }

            listOf(
                    client.get("/api/admin/production/destinations"),
                    client.get("/api/admin/production/destinations/not-a-long"),
                    client.post("/api/admin/production/destinations"),
                    client.put("/api/admin/production/destinations/not-a-long"),
                    client.delete("/api/admin/production/destinations/not-a-long"),
                    client.post("/api/admin/production/destinations/1/sync-articles"),
                )
                .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
            assertEquals(0, destinations.operationCalls)

            val customer = signedInClient("CUSTOMER")
            assertEquals(
                HttpStatusCode.Forbidden,
                customer.get("/api/admin/production/destinations").status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                customer.post("/api/admin/production/destinations").status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                customer.post("/api/admin/production/destinations/1/sync-articles").status,
            )
            assertEquals(0, destinations.operationCalls)

            val admin = signedInClient("ADMIN")
            listOf(
                    admin.post("/api/admin/production/destinations"),
                    admin.put("/api/admin/production/destinations/not-a-long"),
                    admin.delete("/api/admin/production/destinations/not-a-long"),
                    admin.post("/api/admin/production/destinations/1/sync-articles"),
                )
                .forEach { response ->
                    assertApiError(response, HttpStatusCode.BadRequest, "Invalid CSRF token")
                }
            assertEquals(0, destinations.operationCalls)

            assertApiError(
                admin.get("/api/admin/production/destinations/not-a-long"),
                HttpStatusCode.BadRequest,
                "Invalid production destination id",
            )
            assertEquals(0, destinations.operationCalls)
        }

    @Test
    fun `http validation rejects invalid bodies without echoing the password`() = testApplication {
        val destinations = StubDestinationOperations()
        application { installDestinationTestApplication(destinations) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        val invalid =
            admin.post("/api/admin/production/destinations") {
                header(AuthRouting.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "supplierId":0,
                      "channel":"SFTP",
                      "label":"Drop",
                      "notificationEmail":"not-an-email",
                      "sftp":{
                        "host":"sftp.example.test",
                        "port":70000,
                        "username":"voenix",
                        "password":"super-secret",
                        "hostKeyFingerprint":"SHA256:abc",
                        "timeoutSeconds":0
                      },
                      "spod":{
                        "environment":"STAGING",
                        "accessToken":"spod-access-token",
                        "timeoutSeconds":30
                      }
                    }
                    """
                        .trimIndent()
                )
            }
        assertFalse(invalid.bodyAsText().contains("super-secret"))
        assertFalse(invalid.bodyAsText().contains("spod-access-token"))
        assertApiError(
            invalid,
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf(
                "supplierId" to listOf("SupplierId must be positive"),
                "notificationEmail" to listOf("NotificationEmail must be a valid email address"),
                "channel" to listOf("SFTP destinations must not carry the spod block"),
                "sftp.port" to listOf("Port must be between 1 and 65535"),
                "sftp.timeoutSeconds" to listOf("TimeoutSeconds must be between 1 and 3600"),
            ),
        )
        assertEquals(0, destinations.operationCalls)
    }

    @Test
    fun `destination results map to the required api errors`() = testApplication {
        val destinations = StubDestinationOperations()
        application { installDestinationTestApplication(destinations) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        destinations.getResult = OperationResult.NotFound
        assertApiError(
            admin.get("/api/admin/production/destinations/404"),
            HttpStatusCode.NotFound,
            "Production destination not found",
        )

        destinations.deleteResult = OperationResult.Conflict
        assertApiError(
            admin.delete("/api/admin/production/destinations/1") {
                header(AuthRouting.CSRF_HEADER, token)
            },
            HttpStatusCode.Conflict,
            "Production destination is in use and cannot be deleted; disable it instead",
        )

        destinations.listResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.get("/api/admin/production/destinations"),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    /**
     * The five answers of the trigger route. The two conflicts carry different codes because they
     * have different fixes, and a run that failed is still a `200`: it happened, and its report
     * says what went wrong — see `DestinationSyncResult`.
     */
    @Test
    fun `sync results map to their status codes and conflict codes`() = testApplication {
        val destinations = StubDestinationOperations()
        application { installDestinationTestApplication(destinations) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        val failedRun = admin.sync(token)
        assertEquals(HttpStatusCode.OK, failedRun.status)
        assertTrue(failedRun.bodyAsText().contains("\"status\":\"FAILED\""))

        destinations.syncResult = DestinationSyncResult.NotFound
        assertApiError(
            admin.sync(token),
            HttpStatusCode.NotFound,
            "Production destination not found",
        )

        destinations.syncResult = DestinationSyncResult.NotSyncable
        assertApiError(
            admin.sync(token),
            HttpStatusCode.Conflict,
            "Only print-on-demand destinations have a t-shirt catalog to sync",
            code = "CHANNEL_WITHOUT_CATALOG",
        )

        destinations.syncResult = DestinationSyncResult.Busy
        assertApiError(
            admin.sync(token),
            HttpStatusCode.Conflict,
            "This destination is already syncing; wait for that run to finish",
            code = "SYNC_RUNNING",
        )

        destinations.syncResult = DestinationSyncResult.Failed
        assertApiError(
            admin.sync(token),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    private suspend fun HttpClient.sync(token: String): HttpResponse =
        post("/api/admin/production/destinations/1/sync-articles") {
            header(AuthRouting.CSRF_HEADER, token)
        }

    private fun Application.installDestinationTestApplication(
        destinations: ProductionDestinationOperations
    ) {
        installHttpRuntime()
        install(RequestValidation) { validateProductionRequests() }
        installAuthModule(AuthSettings("production-destination-route-session-secret"))
        installDestinationRoutes(destinations)
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

    private suspend fun assertApiError(
        response: HttpResponse,
        status: HttpStatusCode,
        message: String,
        errors: Map<String, List<String>> = emptyMap(),
        code: String? = null,
    ) {
        assertEquals(status, response.status)
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        assertEquals(
            apiErrorJson.encodeToJsonElement(ApiError(message, errors, code)).jsonObject,
            Json.parseToJsonElement(response.bodyAsText()).jsonObject,
        )
    }

    private class StubDestinationOperations : ProductionDestinationOperations {
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var syncCalls = 0
        var listResult: OperationResult<List<ProductionDestination>> =
            OperationResult.Success(emptyList())
        var getResult: OperationResult<ProductionDestination>? = null
        var deleteResult: OperationResult<Unit> = OperationResult.Success(Unit)

        /** A run that read nothing and says so: the report of a `FAILED` sync is still a `200`. */
        var syncResult: DestinationSyncResult =
            DestinationSyncResult.Reported(
                TshirtSyncReport(
                    destinationId = 1,
                    supplierId = 1,
                    environment = SpodEnvironment.STAGING,
                    status = TshirtSyncStatus.FAILED,
                    failure = SpodError.PROVIDER_UNAVAILABLE,
                    startedAt = Instant.EPOCH,
                    finishedAt = Instant.EPOCH,
                )
            )

        val operationCalls: Int
            get() = listCalls + getCalls + createCalls + updateCalls + deleteCalls + syncCalls

        override suspend fun list(): OperationResult<List<ProductionDestination>> {
            listCalls++
            return listResult
        }

        override suspend fun get(id: Long): OperationResult<ProductionDestination> {
            getCalls++
            return getResult ?: OperationResult.Success(destination(id))
        }

        override suspend fun create(
            input: ProductionDestinationInput
        ): OperationResult<ProductionDestination> {
            createCalls++
            return OperationResult.Success(destination(42))
        }

        override suspend fun update(
            id: Long,
            input: ProductionDestinationInput,
        ): OperationResult<ProductionDestination> {
            updateCalls++
            return OperationResult.Success(destination(id))
        }

        override suspend fun delete(id: Long): OperationResult<Unit> {
            deleteCalls++
            return deleteResult
        }

        override suspend fun syncArticles(id: Long): DestinationSyncResult {
            syncCalls++
            return syncResult
        }

        private fun destination(id: Long): ProductionDestination =
            ProductionDestination(
                id = id,
                supplierId = 1,
                channel = "SFTP",
                label = "Producer drop",
                enabled = true,
                notificationEmail = null,
                notificationName = null,
                sftp =
                    SftpDestinationDetails(
                        host = "sftp.example.test",
                        port = 22,
                        username = "voenix",
                        hostKeyFingerprint = "SHA256:0123456789abcdef",
                        remotePath = "/",
                        timeoutSeconds = 30,
                    ),
            )
    }

    private companion object {
        val apiErrorJson = Json { encodeDefaults = true }
    }
}
