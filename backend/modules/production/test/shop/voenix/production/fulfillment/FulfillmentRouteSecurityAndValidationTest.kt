package shop.voenix.production.fulfillment

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.auth.AuthRoles
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.SupplierAccounts
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime

/**
 * The two fulfillment subtrees over HTTP, without a database: who is refused, in which order, and
 * with which scope the operations are called when a request does get through.
 *
 * The order matters as much as the outcome. Authorization runs before the job id is bound and
 * before any read happens, so an anonymous probe learns nothing about which ids exist — the stub
 * records every call, and the assertions are as much about the calls that must *not* appear.
 */
internal class FulfillmentRouteSecurityAndValidationTest {
    @Test
    fun `both subtrees reject before id binding or any fulfillment read`() = testApplication {
        val fulfillment = StubFulfillmentOperations()
        application { installFulfillmentTestApplication(fulfillment) }

        listOf(
                client.get("/api/supplier/me"),
                client.get("/api/supplier/production-jobs"),
                client.get("/api/supplier/production-jobs/not-a-long/pdf"),
                client.get("/api/admin/production/jobs"),
                client.get("/api/admin/production/jobs/not-a-long/pdf"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(emptyList(), fulfillment.calls)

        val customer = signedInClient(roles = "CUSTOMER", userId = LINKED_USER_ID)
        listOf(
                customer.get("/api/supplier/production-jobs"),
                customer.get("/api/admin/production/jobs"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Forbidden, response.status) }
        assertEquals(emptyList(), fulfillment.calls)
    }

    @Test
    fun `an admin is not a supplier and a supplier is not an admin`() = testApplication {
        val fulfillment = StubFulfillmentOperations()
        application { installFulfillmentTestApplication(fulfillment) }

        val admin = signedInClient(roles = AuthRoles.ADMIN, userId = LINKED_USER_ID)
        assertEquals(HttpStatusCode.Forbidden, admin.get("/api/supplier/me").status)

        val supplier = signedInClient(roles = AuthRoles.SUPPLIER, userId = LINKED_USER_ID)
        assertEquals(
            HttpStatusCode.Forbidden,
            supplier.get("/api/admin/production/jobs").status,
        )
        assertEquals(emptyList(), fulfillment.calls)
    }

    @Test
    fun `a supplier login is answered for its own supplier and never for a sent one`() =
        testApplication {
            val fulfillment = StubFulfillmentOperations()
            application { installFulfillmentTestApplication(fulfillment) }
            val supplier = signedInClient(roles = AuthRoles.SUPPLIER, userId = LINKED_USER_ID)

            assertEquals(HttpStatusCode.OK, supplier.get("/api/supplier/me").status)
            assertEquals(
                HttpStatusCode.OK,
                supplier.get("/api/supplier/production-jobs?supplierId=999").status,
            )
            assertEquals(
                HttpStatusCode.OK,
                supplier.get("/api/supplier/production-jobs?status=SHIPPED").status,
            )

            assertEquals(
                listOf(
                    "identity($SUPPLIER_ID)",
                    "supplierJobs($SUPPLIER_ID, OPEN)",
                    "supplierJobs($SUPPLIER_ID, SHIPPED)",
                ),
                fulfillment.calls,
            )
        }

    @Test
    fun `an unknown status is a bad request and no status at all is the open list`() =
        testApplication {
            val fulfillment = StubFulfillmentOperations()
            application { installFulfillmentTestApplication(fulfillment) }
            val supplier = signedInClient(roles = AuthRoles.SUPPLIER, userId = LINKED_USER_ID)
            val admin = signedInClient(roles = AuthRoles.ADMIN, userId = ADMIN_USER_ID)

            listOf(
                    supplier.get("/api/supplier/production-jobs?status=open"),
                    supplier.get("/api/supplier/production-jobs?status=DELIVERED"),
                    admin.get("/api/admin/production/jobs?status=whatever"),
                )
                .forEach { response ->
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertEquals("INVALID_STATUS", response.errorCode())
                }
            assertEquals(emptyList(), fulfillment.calls, "an unusable status reads nothing")

            val brokenFilter = admin.get("/api/admin/production/jobs?supplierId=x")
            assertEquals(HttpStatusCode.BadRequest, brokenFilter.status)
            assertEquals("INVALID_SUPPLIER_ID", brokenFilter.errorCode())
            assertEquals(
                HttpStatusCode.OK,
                admin.get("/api/admin/production/jobs?supplierId=4").status,
            )
            assertEquals(listOf("adminJobs(OPEN, 4)"), fulfillment.calls)
        }

    @Test
    fun `an unusable job id answers like an unknown one and reads nothing`() = testApplication {
        val fulfillment = StubFulfillmentOperations()
        application { installFulfillmentTestApplication(fulfillment) }
        val supplier = signedInClient(roles = AuthRoles.SUPPLIER, userId = LINKED_USER_ID)
        val admin = signedInClient(roles = AuthRoles.ADMIN, userId = ADMIN_USER_ID)

        listOf(
                supplier.get("/api/supplier/production-jobs/not-a-long/pdf"),
                admin.get("/api/admin/production/jobs/not-a-long/pdf"),
            )
            .forEach { response ->
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals("Production job not found", response.message())
            }
        assertEquals(emptyList(), fulfillment.calls)
    }

    @Test
    fun `the three artifact states map onto their conflict codes and nothing is cacheable`() =
        testApplication {
            val fulfillment = StubFulfillmentOperations()
            application { installFulfillmentTestApplication(fulfillment) }
            val supplier = signedInClient(roles = AuthRoles.SUPPLIER, userId = LINKED_USER_ID)

            val expected =
                mapOf(
                    FulfillmentArtifactResult.NotGenerated to "ARTIFACT_NOT_GENERATED",
                    FulfillmentArtifactResult.Missing to "ARTIFACT_MISSING",
                    FulfillmentArtifactResult.DigestMismatch to "ARTIFACT_DIGEST_MISMATCH",
                )
            expected.forEach { (result, code) ->
                fulfillment.artifact = result
                val response = supplier.get("/api/supplier/production-jobs/7/pdf")
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals(code, response.errorCode())
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            }

            fulfillment.artifact = FulfillmentArtifactResult.NotFound
            val missing = supplier.get("/api/supplier/production-jobs/7/pdf")
            assertEquals(HttpStatusCode.NotFound, missing.status)

            assertEquals(
                List(4) { "artifact(7, $SUPPLIER_ID)" },
                fulfillment.calls,
                "the supplier scope is the resolved one, on every attempt",
            )
            assertEquals("no-store", supplier.get("/api/supplier/me").cacheControl())
        }

    private fun Application.installFulfillmentTestApplication(fulfillment: FulfillmentOperations) {
        installHttpRuntime()
        installAuthModule(AuthSettings(SESSION_SECRET))
        installProductionFulfillment(
            fulfillment,
            SupplierAccounts { userId -> SUPPLIER_ID.takeIf { userId == LINKED_USER_ID } },
        )
        routing {
            post("/test/sign-in") {
                val now = Instant.now().epochSecond
                call.sessions.set(
                    UserSession(
                        userId = call.request.queryParameters["userId"].orEmpty(),
                        roles = setOf(call.request.queryParameters["roles"].orEmpty()),
                        issuedAtEpochSeconds = now,
                        expiresAtEpochSeconds = now + SESSION_DURATION_SECONDS,
                    )
                )
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.signedInClient(
        roles: String,
        userId: Long,
    ): HttpClient {
        val client = createClient { install(HttpCookies) }
        val response =
            client.post("/test/sign-in") {
                parameter("roles", roles)
                parameter("userId", "$userId")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        return client
    }

    private suspend fun HttpResponse.errorCode(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content

    private suspend fun HttpResponse.message(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("message").jsonPrimitive.content

    private fun HttpResponse.cacheControl(): String? = headers[HttpHeaders.CacheControl]

    private companion object {
        const val SESSION_SECRET = "fulfillment-route-test-secret-with-enough-bytes"
        const val SESSION_DURATION_SECONDS = 24L * 60L * 60L
        const val LINKED_USER_ID = 21L
        const val ADMIN_USER_ID = 22L
        const val SUPPLIER_ID = 7L
    }
}
