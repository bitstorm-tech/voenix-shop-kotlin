package shop.voenix.account

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRoles
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.country.Country
import shop.voenix.country.CountryReader
import shop.voenix.http.FrontendBaseUrl
import shop.voenix.http.installHttpRuntime
import shop.voenix.supplier.installSupplierModule
import shop.voenix.supplier.validateSupplierRequests
import shop.voenix.testing.PostgresIntegrationTest

/**
 * What a supplier login does to the supplier it belongs to, driven through both modules' real admin
 * routes on one database.
 *
 * `users.supplier_id` is the second table that references `suppliers` (the first is `article_mugs`,
 * covered by `ArticleSupplierRelationshipIntegrationTest`), and its foreign key is `ON DELETE
 * RESTRICT` as well: a supplier that somebody can still sign in for must not disappear under that
 * login. The schema test proves the constraint; what has to be proven here is the answer a caller
 * actually receives — the supplier route's `409` with a body that names no constraint, no table,
 * and no driver wording — and that the supplier becomes deletable again once its last login is
 * gone.
 *
 * This module depends on the supplier module in its tests only. Production code of the account
 * module knows nothing about it: the link is the column, not an import.
 */
internal class SupplierLoginSupplierDeleteIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a supplier with a login cannot be deleted and becomes deletable when it is gone`() {
        migratedDataSource("supplier-login-supplier-delete-test-${System.nanoTime()}").use {
            dataSource ->
            prepare(dataSource)
            val database = Database.connect(datasource = dataSource)
            testApplication {
                application {
                    installHttpRuntime()
                    install(RequestValidation) {
                        validateAccountRequests()
                        validateSupplierRequests()
                    }
                    installAuthModule(AuthSettings("supplier-delete-flow-session-secret-000"))
                    installAccountModule(
                        database,
                        AccountSettings(
                            frontendBaseUrl = FrontendBaseUrl("http://localhost:5173"),
                            pbkdf2Iterations = 1_000,
                        ),
                        RecordingUserEmailSender(),
                        MutableClock(Instant.parse("2026-08-13T10:00:00Z")),
                    )
                    installSupplierModule(database, NoCountries)
                    routing {
                        post("/test/sign-in-admin") {
                            call.sessions.set(UserSession(userId = "1", role = AuthRoles.ADMIN))
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }

                val admin = createClient { install(HttpCookies) }
                assertEquals(HttpStatusCode.OK, admin.post("/test/sign-in-admin").status)
                val csrf = admin.antiforgeryToken()

                val created = admin.createSupplierLogin(SUPPLIER_ID, EMAIL, csrf)
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                val userId =
                    Json.parseToJsonElement(created.bodyAsText())
                        .jsonObject
                        .getValue("userId")
                        .jsonPrimitive
                        .content

                val refused = admin.deleteSupplier(SUPPLIER_ID, csrf)
                assertEquals(HttpStatusCode.Conflict, refused.status)
                val body = refused.bodyAsText()
                assertEquals(
                    "Supplier is in use and cannot be deleted",
                    Json.parseToJsonElement(body)
                        .jsonObject
                        .getValue("message")
                        .jsonPrimitive
                        .content,
                )
                LEAKED_SCHEMA_TERMS.forEach { term ->
                    assertFalse(
                        body.contains(term, ignoreCase = true),
                        "The response must not expose `$term`: $body",
                    )
                }
                assertEquals(
                    listOf(SUPPLIER_ID, OTHER_SUPPLIER_ID),
                    admin.supplierIds(),
                    "the refused delete removed nothing",
                )

                // The supplier without a login is unaffected by any of this.
                assertEquals(
                    HttpStatusCode.NoContent,
                    admin.deleteSupplier(OTHER_SUPPLIER_ID, csrf).status,
                )

                // Removing the last login releases the supplier.
                assertEquals(
                    HttpStatusCode.NoContent,
                    admin.deleteSupplierLogin(userId, csrf).status,
                )
                assertEquals(
                    HttpStatusCode.NoContent,
                    admin.deleteSupplier(SUPPLIER_ID, csrf).status,
                )
                assertEquals(emptyList(), admin.supplierIds())
            }
        }
    }

    /** Two suppliers: the one that gets a login, and one that stays unreferenced. */
    private fun prepare(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.users RESTART IDENTITY CASCADE")
                statement.execute("TRUNCATE voenix.suppliers RESTART IDENTITY CASCADE")
                statement.execute(
                    "INSERT INTO voenix.suppliers (id, name) VALUES " +
                        "($SUPPLIER_ID, 'Alpha'), ($OTHER_SUPPLIER_ID, 'Beta')"
                )
            }
        }
    }

    private suspend fun HttpClient.createSupplierLogin(
        supplierId: Long,
        email: String,
        csrf: String,
    ): HttpResponse =
        post("/api/admin/supplier-logins") {
            header(AuthRouting.CSRF_HEADER, csrf)
            contentType(ContentType.Application.Json)
            setBody("""{"supplierId":$supplierId,"email":"$email"}""")
        }

    private suspend fun HttpClient.deleteSupplierLogin(
        userId: String,
        csrf: String,
    ): HttpResponse =
        delete("/api/admin/supplier-logins/$userId") { header(AuthRouting.CSRF_HEADER, csrf) }

    private suspend fun HttpClient.deleteSupplier(id: Long, csrf: String): HttpResponse =
        delete("/api/admin/suppliers/$id") { header(AuthRouting.CSRF_HEADER, csrf) }

    /** The stored suppliers by id, so a refused delete can be shown to have removed nothing. */
    private suspend fun HttpClient.supplierIds(): List<Long> =
        Json.parseToJsonElement(get("/api/admin/suppliers").bodyAsText())
            .jsonArray
            .map { supplier -> supplier.jsonObject.getValue("id").jsonPrimitive.content.toLong() }
            .sorted()

    private suspend fun HttpClient.antiforgeryToken(): String {
        val body = get("/api/antiforgery/token").bodyAsText()
        return Regex("\"requestToken\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: error("No antiforgery token in response: $body")
    }

    /** The supplier module needs a country capability; no supplier here has a country. */
    private object NoCountries : CountryReader {
        override suspend fun find(ids: Set<Long>): Map<Long, Country> = emptyMap()
    }

    private companion object {
        const val SUPPLIER_ID = 7L
        const val OTHER_SUPPLIER_ID = 8L
        const val EMAIL = "logistik@lieferant.example"

        /**
         * What a leaked PostgreSQL error would put into the body: the constraint name, the table
         * behind it, and the wording of the driver's message.
         */
        val LEAKED_SCHEMA_TERMS = listOf("fk_users_supplier", "users", "constraint", "violates")
    }
}
