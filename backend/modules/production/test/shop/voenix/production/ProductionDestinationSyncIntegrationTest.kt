package shop.voenix.production

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.tshirt.SpodCatalogSource
import shop.voenix.article.tshirt.TshirtCatalogSync
import shop.voenix.article.tshirt.TshirtSyncReport
import shop.voenix.article.tshirt.TshirtSyncResult
import shop.voenix.article.tshirt.TshirtSyncStatus
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.production.delivery.resetProductionTables
import shop.voenix.spod.SpodEnvironment
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The trigger route against the real destination rows: which destination has a catalog at all, and
 * what the sync is handed for the one that has.
 *
 * The disabled destination is the case ADR 0003 decides (D5): a shop that switched a channel off
 * may still read its backoffice, so `enabled` is not part of the lookup and only the channel is.
 * The source the stub records is the other half — the supplier, the installation, and the token
 * come off that row and nowhere else, including the access token no other destination read selects.
 *
 * What the run itself does to the article tables is the article module's own suite; this one stops
 * at the seam between the two.
 */
internal class ProductionDestinationSyncIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a sync reads the destination it is triggered on and refuses one without a catalog`() {
        migratedDataSource("production-destination-sync-test").use { dataSource ->
            resetProductionTables(dataSource)
            insertSupplier(dataSource, "Acme")
            insertSupplier(dataSource, "Spreadconnect")
            val database = Database.connect(datasource = dataSource)
            val sync = RecordingTshirtCatalogSync()

            testApplication {
                application { installDestinationTestApplication(database, sync) }
                val admin = signedInAdmin()
                val token = antiforgeryToken(admin)
                assertEquals(HttpStatusCode.Created, admin.create(token, SFTP_BODY).status)
                assertEquals(HttpStatusCode.Created, admin.create(token, DISABLED_SPOD_BODY).status)

                assertEquals(HttpStatusCode.NotFound, admin.sync(token, id = 404).status)
                assertEquals(HttpStatusCode.Conflict, admin.sync(token, id = 1).status)
                assertEquals(emptyList(), sync.sources)

                val synced = admin.sync(token, id = 2)
                assertEquals(
                    HttpStatusCode.OK,
                    synced.status,
                    "a disabled destination may still sync",
                )
                assertEquals(
                    "2",
                    Json.parseToJsonElement(synced.bodyAsText())
                        .jsonObject
                        .getValue("destinationId")
                        .jsonPrimitive
                        .content,
                )
                val source = sync.sources.single()
                assertEquals(2, source.supplierId)
                assertEquals(2, source.access.destinationId)
                assertEquals(SpodEnvironment.STAGING, source.access.environment)
                assertEquals("spod-access-token", source.access.accessToken)
                assertEquals(30, source.access.timeoutSeconds)
            }
        }
    }

    /** Answers a report for every run and remembers what the route handed it. */
    private class RecordingTshirtCatalogSync : TshirtCatalogSync {
        val sources: MutableList<SpodCatalogSource> = mutableListOf()

        override suspend fun sync(source: SpodCatalogSource): TshirtSyncResult {
            sources += source
            return TshirtSyncResult.Reported(
                TshirtSyncReport(
                    destinationId = source.access.destinationId,
                    supplierId = source.supplierId,
                    environment = source.access.environment,
                    status = TshirtSyncStatus.COMPLETED,
                    startedAt = Instant.EPOCH,
                    finishedAt = Instant.EPOCH,
                )
            )
        }
    }

    private fun io.ktor.server.application.Application.installDestinationTestApplication(
        database: Database,
        sync: TshirtCatalogSync,
    ) {
        installHttpRuntime()
        installAuthModule(AuthSettings("production-destination-sync-session-secret"))
        installProductionModule(database, sync)
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

    private suspend fun HttpClient.create(token: String, body: String): HttpResponse =
        post("/api/admin/production/destinations") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.sync(token: String, id: Long): HttpResponse =
        post("/api/admin/production/destinations/$id/sync-articles") {
            header(AuthRouting.CSRF_HEADER, token)
        }

    private fun insertSupplier(dataSource: HikariDataSource, name: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO voenix.suppliers (name) VALUES (?)").use {
                statement ->
                assertEquals(1, statement.also { it.setString(1, name) }.executeUpdate())
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

        val DISABLED_SPOD_BODY =
            """
            {
              "supplierId":2,
              "channel":"SPOD",
              "label":"Spreadconnect staging",
              "enabled":false,
              "spod":{
                "environment":"STAGING",
                "accessToken":"spod-access-token",
                "timeoutSeconds":30
              }
            }
            """
                .trimIndent()
    }
}
