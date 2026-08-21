package shop.voenix.production

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
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
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.production.delivery.resetProductionTables
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The write side of the print-on-demand configuration rule.
 *
 * A deployment without a `production.spod` block refuses to start once a SPOD destination exists —
 * a channel whose shipments arrive by webhook cannot report a single one without the secret that
 * authorizes the callback. So the destination that would cause that must not be storable in the
 * first place, and this suite is that half of the rule: the startup check catches the row that is
 * already there, the service refuses the one somebody is adding now.
 */
internal class ProductionDestinationSpodConfigurationIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a spod destination is refused when the deployment has no spod configuration`() {
        migratedDataSource("production-destination-spod-unconfigured-test").use { dataSource ->
            resetProductionTables(dataSource)
            insertSupplier(dataSource, "Spreadconnect")
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application { installUnconfiguredApplication(database) }
                val admin = signedInAdmin()
                val token = antiforgeryToken(admin)

                // Disabled is refused too: the startup check does not look at `enabled` either.
                listOf(true, false).forEach { enabled ->
                    val refused = admin.write(token, spodBody(enabled))
                    assertEquals(HttpStatusCode.BadRequest, refused.status, "enabled=$enabled")
                    assertEquals(
                        listOf(
                            "\"This deployment has no production.spod configuration, so no SPOD " +
                                "destination can be stored\""
                        ),
                        refused.fieldErrors("channel"),
                    )
                }

                val sftp = admin.write(token, SFTP_BODY)
                assertEquals(HttpStatusCode.Created, sftp.status, sftp.bodyAsText())
                assertEquals(1, countRows(dataSource, "voenix.production_destinations"))

                val switched =
                    admin.write(
                        token,
                        spodBody(enabled = true),
                        id = sftp.id(),
                    )
                assertEquals(HttpStatusCode.BadRequest, switched.status)
                assertEquals(
                    0,
                    countRows(dataSource, "voenix.production_destination_spod"),
                    "no update turns a stored destination into an unconfigured SPOD one",
                )
            }
        }
    }

    private fun spodBody(enabled: Boolean): String =
        """
        {
          "supplierId":1,
          "channel":"SPOD",
          "label":"Spreadconnect staging",
          "enabled":$enabled,
          "spod":{
            "environment":"STAGING",
            "accessToken":"spod-access-token",
            "timeoutSeconds":30
          }
        }
        """
            .trimIndent()

    private fun Application.installUnconfiguredApplication(database: Database) {
        installHttpRuntime()
        installAuthModule(AuthSettings("production-destination-spod-configuration-secret"))
        installProductionModule(database, spodConfigured = false)
        routing {
            post("/test/sign-in") {
                call.sessions.set(UserSession(userId = "11", role = "ADMIN"))
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.signedInAdmin(): HttpClient = createClient {
        install(HttpCookies)
    }
        .also { client -> assertEquals(HttpStatusCode.OK, client.post("/test/sign-in").status) }

    private suspend fun antiforgeryToken(client: HttpClient): String =
        Json.parseToJsonElement(client.get("/api/antiforgery/token").bodyAsText())
            .jsonObject
            .getValue("requestToken")
            .jsonPrimitive
            .content

    /** `POST` for a new destination, `PUT` when [id] names an existing one. */
    private suspend fun HttpClient.write(
        token: String,
        body: String,
        id: Long? = null,
    ): HttpResponse {
        val path = "/api/admin/production/destinations" + (id?.let { "/$it" } ?: "")
        val configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (id == null) post(path, configure) else put(path, configure)
    }

    private suspend fun HttpResponse.id(): Long =
        Json.parseToJsonElement(bodyAsText())
            .jsonObject
            .getValue("id")
            .jsonPrimitive
            .content
            .toLong()

    private suspend fun HttpResponse.fieldErrors(field: String): List<String> =
        Json.parseToJsonElement(bodyAsText())
            .jsonObject
            .getValue("errors")
            .jsonObject
            .getValue(field)
            .jsonArray
            .map(Any::toString)

    private fun insertSupplier(dataSource: HikariDataSource, name: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO voenix.suppliers (name) VALUES (?)").use {
                statement ->
                statement.setString(1, name)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun countRows(dataSource: HikariDataSource, table: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                    check(rows.next()) { "count(*) answered no row" }
                    rows.getInt(1)
                }
            }
        }

    private companion object {
        val SFTP_BODY =
            """
            {
              "supplierId":1,
              "channel":"SFTP",
              "label":"Mug producer",
              "sftp":{
                "host":"sftp.example.test",
                "username":"voenix",
                "password":"super-secret",
                "hostKeyFingerprint":"SHA256:0123456789abcdef",
                "timeoutSeconds":30
              }
            }
            """
                .trimIndent()
    }
}
