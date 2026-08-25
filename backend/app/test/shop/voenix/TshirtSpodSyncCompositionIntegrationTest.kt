package shop.voenix

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.application.Application as KtorApplication
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.sql.DataSource
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.UserSession
import shop.voenix.spod.SpodClient
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The composition test seam for the print-on-demand partner: the whole application, talking to
 * [spod] instead of building its own client on the real installations.
 *
 * It is the `module(mollie)` seam's sibling and exists for the same reason — where a partner call
 * goes is a property of the code, never of the configuration — and it lives in the test sources for
 * the same reason too, which `Application.install` spells out.
 */
internal fun KtorApplication.module(spod: SpodClient): Unit = Application.install(this, spod = spod)

/**
 * The t-shirt sync through the whole composition: an admin creates a print-on-demand destination
 * over the real admin API, presses the sync button of that destination, and a shirt of the
 * partner's backoffice becomes a row of this shop's catalog.
 *
 * What only a composed test can show is exactly the edge this ticket added. The button belongs to
 * the production module, the articles it writes belong to the article module, and the two meet
 * nowhere but in `Application.installModules` — through one `SpodClient` both of them were handed.
 * A module suite cannot see any of that; this test fails the moment the wiring is dropped.
 *
 * The partner is a `MockEngine` behind the application's *own* client, injected through the
 * `module(spod)` seam for the same reason the checkout suites inject a Mollie stub: where the calls
 * go is not a configuration key, and it must not become one.
 */
internal class TshirtSpodSyncCompositionIntegrationTest : PostgresIntegrationTest() {
    private val imageRoot: Path = createTempDirectory("tshirt-sync-composition")

    @AfterTest
    fun cleanUp() {
        imageRoot.toFile().deleteRecursively()
    }

    @Test
    fun `an admin syncs a spod destination and the backoffice shirt becomes an article`() {
        dataSource("tshirt-sync-composition", SCHEMA).use { dataSource ->
            testApplication {
                environment { config = applicationConfig() }
                application {
                    module(SpodClient(engine = backoffice(), nowMillis = { 0 }, pause = {}))
                    routing {
                        post("/test/sign-in") {
                            call.sessions.set(UserSession(userId = "11", role = "ADMIN"))
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }
                startApplication()
                seedSupplier(dataSource)

                val admin = createClient { install(HttpCookies) }
                assertEquals(HttpStatusCode.OK, admin.post("/test/sign-in").status)
                val csrf =
                    Regex("\"requestToken\":\"([^\"]+)\"")
                        .find(admin.get("/api/antiforgery/token").bodyAsText())
                        ?.groupValues
                        ?.get(1) ?: error("No antiforgery token")

                val created =
                    admin.post("/api/admin/production/destinations") {
                        header(AuthRouting.CSRF_HEADER, csrf)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "supplierId":1,
                              "channel":"SPOD",
                              "label":"Spreadconnect staging",
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
                assertEquals(HttpStatusCode.Created, created.status)

                val synced =
                    admin.post("/api/admin/production/destinations/1/sync-articles") {
                        header(AuthRouting.CSRF_HEADER, csrf)
                    }

                assertEquals(HttpStatusCode.OK, synced.status)
                val report = synced.bodyAsText()
                assertContains(report, "\"status\":\"COMPLETED\"")
                assertContains(report, "\"fetchedArticles\":1")
                assertEquals(
                    listOf("Classic Shirt", "a-1", "STAGING", "1", "1"),
                    syncedShirt(dataSource),
                    report,
                )
            }
        }
    }

    /** The configuration a deployment would carry, with this suite's schema and image roots. */
    private fun applicationConfig(): MapApplicationConfig =
        MapApplicationConfig().apply {
            put("database.host", postgres.host)
            put("database.port", postgres.firstMappedPort.toString())
            put("database.database", postgres.databaseName)
            put("database.username", postgres.username)
            put("database.password", postgres.password)
            put("database.searchPath", SCHEMA)
            put("database.sslMode", "Disable")
            put("auth.sessionSecret", "tshirt-sync-composition-session-secret")
            put("frontend.baseUrl", "http://localhost:5173")
            put("generator.dummyMode", "true")
            put("production.artifactRoot", imageRoot.resolve("production-artifacts").toString())
            put("production.spod.webhookSecret", "tshirt-sync-composition-webhook-secret")
            put("production.spod.alertEmail", "ops@voenix.test")
            put("image.publicRoot", imageRoot.resolve("public").toString())
            put("image.privateRoot", imageRoot.resolve("private").toString())
            put("image.cacheRoot", imageRoot.resolve("cache").toString())
            put("mollie.apiKey", "test_tshirt_sync_mollie_key")
            put("mollie.redirectUrl", "http://localhost:5173/checkout/success")
            put("mollie.webhookUrl", "https://voenix.test/api/payments/webhook/$MOLLIE_SECRET")
            put("mollie.webhookSecret", MOLLIE_SECRET)
        }

    /** One shirt in one colour and one size, plus the size chart and the pictures of both. */
    private fun backoffice(): MockEngine = MockEngine { request ->
        when {
            request.url.encodedPath == "/articles" ->
                respond(CATALOG, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            request.url.encodedPath.endsWith("/size-chart") ->
                respond(
                    """{"sizeImageUrl":"https://cdn.example.test/chart.png"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            // A real picture, because the composed application stores it through the real image
            // storage, which converts what it is given and refuses anything it cannot read.
            else -> respond(pngBytes(), headers = headersOf(HttpHeaders.ContentType, "image/png"))
        }
    }

    private fun pngBytes(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", bytes)
        return bytes.toByteArray()
    }

    private fun seedSupplier(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO $SCHEMA.suppliers (id, name) VALUES (1, 'SPOD')"
                )
            }
        }
    }

    /** Name, partner id, environment, destination, and supplier of the one synced shirt. */
    private fun syncedShirt(dataSource: DataSource): List<String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT name, spod_article_id, spod_environment, spod_destination_id, " +
                            "supplier_id FROM $SCHEMA.article_tshirts"
                    )
                    .use { rows ->
                        if (!rows.next()) return emptyList()
                        (1..5).map { column -> rows.getString(column) }
                    }
            }
        }

    private companion object {
        const val SCHEMA = "tshirt_sync_composition"

        /** No payment happens here; the block only has to be a valid one. */
        const val MOLLIE_SECRET = "tshirt-sync-composition-mollie-webhook-secret"

        val CATALOG =
            """
            {
              "items":[
                {
                  "id":"a-1",
                  "title":"Classic Shirt",
                  "description":"A shirt",
                  "variants":[
                    {
                      "id":"a-1-v-1",
                      "productTypeId":812,
                      "appearanceId":5,
                      "appearanceName":"Black",
                      "appearanceColorValue":"#101010",
                      "sizeId":91,
                      "sizeName":"M",
                      "sku":"SKU-1"
                    }
                  ],
                  "images":[
                    {
                      "id":"a-1-i-1",
                      "appearanceId":5,
                      "perspective":"front",
                      "imageUrl":"https://cdn.example.test/a-1-i-1.png"
                    }
                  ]
                }
              ],
              "count":1,
              "limit":100,
              "offset":0
            }
            """
                .trimIndent()
    }
}
