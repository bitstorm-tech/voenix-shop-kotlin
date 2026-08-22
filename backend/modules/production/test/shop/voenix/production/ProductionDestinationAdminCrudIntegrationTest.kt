package shop.voenix.production

import com.zaxxer.hikari.HikariDataSource
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
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

internal class ProductionDestinationAdminCrudIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `admin can create list read replace disable and delete an sftp destination`() {
        migratedDataSource("production-destination-crud-test").use { dataSource ->
            resetProductionTables(dataSource)
            insertSupplier(dataSource, "Acme")
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application { installDestinationTestApplication(database) }
                val admin = signedInAdmin()
                val token = antiforgeryToken(admin)

                val created =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SFTP",
                          "label":" Producer drop ",
                          "notificationEmail":" producer@example.test ",
                          "sftp":{
                            "host":" sftp.example.test ",
                            "username":" voenix ",
                            "password":"super-secret",
                            "hostKeyFingerprint":" SHA256:0123456789abcdef ",
                            "timeoutSeconds":30
                          }
                        }
                        """
                            .trimIndent(),
                    )
                assertEquals(HttpStatusCode.Created, created.status)
                assertEquals(
                    "/api/admin/production/destinations/1",
                    created.headers[HttpHeaders.Location],
                )
                assertFalse(created.bodyAsText().contains("super-secret"))
                val createdBody = created.body()
                assertEquals("SFTP", createdBody.getValue("channel").jsonPrimitive.content)
                assertEquals("Producer drop", createdBody.getValue("label").jsonPrimitive.content)
                assertEquals("true", createdBody.getValue("enabled").toString())
                assertEquals("null", createdBody.getValue("spod").toString())
                assertEquals(
                    "producer@example.test",
                    createdBody.getValue("notificationEmail").jsonPrimitive.content,
                )
                val createdSftp = createdBody.getValue("sftp").jsonObject
                assertFalse(createdSftp.containsKey("password"))
                assertEquals("22", createdSftp.getValue("port").toString())
                assertEquals("/", createdSftp.getValue("remotePath").jsonPrimitive.content)
                assertEquals(
                    "sftp.example.test",
                    createdSftp.getValue("host").jsonPrimitive.content,
                )
                assertEquals("super-secret", storedPassword(dataSource))

                val missingPassword =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SFTP",
                          "label":"No password",
                          "sftp":{
                            "host":"sftp.example.test",
                            "username":"voenix",
                            "hostKeyFingerprint":"SHA256:0123456789abcdef",
                            "timeoutSeconds":30
                          }
                        }
                        """
                            .trimIndent(),
                    )
                assertEquals(HttpStatusCode.BadRequest, missingPassword.status)
                assertEquals(
                    listOf("\"Password is required\""),
                    missingPassword.fieldErrors("sftp.password"),
                )

                val blankOverlongPassword =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SFTP",
                          "label":"Blank overlong password",
                          "sftp":{
                            "host":"sftp.example.test",
                            "username":"voenix",
                            "password":"${" ".repeat(OVERLONG_PASSWORD_LENGTH)}",
                            "hostKeyFingerprint":"SHA256:0123456789abcdef",
                            "timeoutSeconds":30
                          }
                        }
                        """
                            .trimIndent(),
                    )
                assertEquals(HttpStatusCode.BadRequest, blankOverlongPassword.status)
                assertEquals(
                    listOf(
                        "\"Password must be at most 255 characters\"",
                        "\"Password is required\"",
                    ),
                    blankOverlongPassword.fieldErrors("sftp.password"),
                )

                val unknownSupplier =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":404,
                          "channel":"SFTP",
                          "label":"Unknown supplier",
                          "sftp":{
                            "host":"sftp.example.test",
                            "username":"voenix",
                            "password":"unused-secret",
                            "hostKeyFingerprint":"SHA256:0123456789abcdef",
                            "timeoutSeconds":30
                          }
                        }
                        """
                            .trimIndent(),
                    )
                assertEquals(HttpStatusCode.BadRequest, unknownSupplier.status)
                assertFalse(unknownSupplier.bodyAsText().contains("unused-secret"))
                assertEquals(
                    listOf("\"Supplier not found\""),
                    unknownSupplier.fieldErrors("supplierId"),
                )

                val listed =
                    Json.parseToJsonElement(
                            admin.get("/api/admin/production/destinations").bodyAsText()
                        )
                        .jsonArray
                assertEquals(listOf(createdBody), listed.map { it.jsonObject })
                assertEquals(
                    createdBody,
                    Json.parseToJsonElement(
                            admin.get("/api/admin/production/destinations/1").bodyAsText()
                        )
                        .jsonObject,
                )

                val disabled =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SFTP",
                          "label":"Backup drop",
                          "enabled":false,
                          "notificationEmail":null,
                          "notificationName":null,
                          "sftp":{
                            "host":"sftp.example.test",
                            "port":2222,
                            "username":"voenix",
                            "hostKeyFingerprint":"SHA256:fedcba9876543210",
                            "remotePath":"/drop",
                            "timeoutSeconds":60
                          }
                        }
                        """
                            .trimIndent(),
                        id = 1,
                    )
                assertEquals(HttpStatusCode.OK, disabled.status)
                val disabledBody = disabled.body()
                assertEquals("false", disabledBody.getValue("enabled").toString())
                assertEquals("null", disabledBody.getValue("notificationEmail").toString())
                val disabledSftp = disabledBody.getValue("sftp").jsonObject
                assertFalse(disabledSftp.containsKey("password"))
                assertEquals("2222", disabledSftp.getValue("port").toString())
                assertEquals("/drop", disabledSftp.getValue("remotePath").jsonPrimitive.content)
                assertEquals(
                    "super-secret",
                    storedPassword(dataSource),
                    "an omitted password keeps the stored one",
                )

                val rotated =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SFTP",
                          "label":"Backup drop",
                          "enabled":true,
                          "sftp":{
                            "host":"sftp.example.test",
                            "port":2222,
                            "username":"voenix",
                            "password":"rotated-secret",
                            "hostKeyFingerprint":"SHA256:fedcba9876543210",
                            "remotePath":"/drop",
                            "timeoutSeconds":60
                          }
                        }
                        """
                            .trimIndent(),
                        id = 1,
                    )
                assertEquals(HttpStatusCode.OK, rotated.status)
                assertFalse(rotated.bodyAsText().contains("rotated-secret"))
                assertEquals("rotated-secret", storedPassword(dataSource))

                val defaulted =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SFTP",
                          "label":"Padded secret",
                          "sftp":{
                            "host":"sftp.example.test",
                            "username":"voenix",
                            "password":" pad ded ",
                            "hostKeyFingerprint":"SHA256:fedcba9876543210",
                            "remotePath":"   ",
                            "timeoutSeconds":60
                          }
                        }
                        """
                            .trimIndent(),
                        id = 1,
                    )
                assertEquals(HttpStatusCode.OK, defaulted.status)
                val defaultedSftp = defaulted.body().getValue("sftp").jsonObject
                assertEquals("true", defaulted.body().getValue("enabled").toString())
                assertEquals("22", defaultedSftp.getValue("port").toString())
                assertEquals("/", defaultedSftp.getValue("remotePath").jsonPrimitive.content)
                assertEquals(" pad ded ", storedPassword(dataSource))

                val deleted =
                    admin.delete("/api/admin/production/destinations/1") {
                        header(AuthRouting.CSRF_HEADER, token)
                    }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals("", deleted.bodyAsText())
                assertNull(storedPassword(dataSource), "the detail row goes with the destination")

                val missing = admin.get("/api/admin/production/destinations/1")
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals(
                    Json.parseToJsonElement(
                        """{"message":"Production destination not found","errors":{}}"""
                    ),
                    Json.parseToJsonElement(missing.bodyAsText()),
                )
            }
        }
    }

    @Test
    fun `admin can manage a spod destination without its token ever leaving the database`() {
        migratedDataSource("production-destination-spod-crud-test").use { dataSource ->
            resetProductionTables(dataSource)
            insertSupplier(dataSource, "Spreadconnect")
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application { installDestinationTestApplication(database) }
                val admin = signedInAdmin()
                val token = antiforgeryToken(admin)

                val created =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SPOD",
                          "label":" Spreadconnect staging ",
                          "spod":{
                            "environment":"STAGING",
                            "accessToken":"spod-access-token",
                            "timeoutSeconds":30
                          }
                        }
                        """
                            .trimIndent(),
                    )
                assertEquals(HttpStatusCode.Created, created.status)
                assertFalse(created.bodyAsText().contains("spod-access-token"))
                val createdBody = created.body()
                assertEquals("SPOD", createdBody.getValue("channel").jsonPrimitive.content)
                assertEquals("null", createdBody.getValue("sftp").toString())
                val createdSpod = createdBody.getValue("spod").jsonObject
                assertFalse(createdSpod.containsKey("accessToken"))
                assertEquals("STAGING", createdSpod.getValue("environment").jsonPrimitive.content)
                assertEquals("30", createdSpod.getValue("timeoutSeconds").toString())
                assertEquals("spod-access-token", storedAccessToken(dataSource))

                val missingToken =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SPOD",
                          "label":"No token",
                          "spod":{"environment":"PRODUCTION","timeoutSeconds":30}
                        }
                        """
                            .trimIndent(),
                    )
                assertEquals(HttpStatusCode.BadRequest, missingToken.status)
                assertEquals(
                    listOf("\"AccessToken is required\""),
                    missingToken.fieldErrors("spod.accessToken"),
                )

                val secondEnabled =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SPOD",
                          "label":"Second enabled account",
                          "spod":{
                            "environment":"PRODUCTION",
                            "accessToken":"second-token",
                            "timeoutSeconds":30
                          }
                        }
                        """
                            .trimIndent(),
                    )
                assertEquals(HttpStatusCode.BadRequest, secondEnabled.status)
                assertFalse(secondEnabled.bodyAsText().contains("second-token"))
                assertEquals(
                    listOf(
                        "\"Supplier already has an enabled SPOD destination; disable it first\""
                    ),
                    secondEnabled.fieldErrors("channel"),
                )

                val disabledSuccessor =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SPOD",
                          "label":"Prepared successor",
                          "enabled":false,
                          "spod":{
                            "environment":"PRODUCTION",
                            "accessToken":"successor-token",
                            "timeoutSeconds":30
                          }
                        }
                        """
                            .trimIndent(),
                    )
                assertEquals(
                    HttpStatusCode.Created,
                    disabledSuccessor.status,
                    "a disabled second account may be prepared",
                )

                val keptToken =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SPOD",
                          "label":"Spreadconnect staging",
                          "spod":{"environment":"STAGING","timeoutSeconds":45}
                        }
                        """
                            .trimIndent(),
                        id = 1,
                    )
                assertEquals(HttpStatusCode.OK, keptToken.status)
                assertEquals(
                    "45",
                    keptToken
                        .body()
                        .getValue("spod")
                        .jsonObject
                        .getValue("timeoutSeconds")
                        .toString(),
                )
                assertEquals(
                    "spod-access-token",
                    storedAccessToken(dataSource),
                    "an omitted token keeps the stored one",
                )

                val rotated =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SPOD",
                          "label":"Spreadconnect staging",
                          "spod":{
                            "environment":"STAGING",
                            "accessToken":"rotated-token",
                            "timeoutSeconds":45
                          }
                        }
                        """
                            .trimIndent(),
                        id = 1,
                    )
                assertEquals(HttpStatusCode.OK, rotated.status)
                assertFalse(rotated.bodyAsText().contains("rotated-token"))
                assertEquals("rotated-token", storedAccessToken(dataSource))
            }
        }
    }

    @Test
    fun `a body whose block does not match its channel is a channel error`() {
        migratedDataSource("production-destination-channel-block-test").use { dataSource ->
            resetProductionTables(dataSource)
            insertSupplier(dataSource, "Acme")
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application { installDestinationTestApplication(database) }
                val admin = signedInAdmin()
                val token = antiforgeryToken(admin)

                val sftpWithoutBlock =
                    admin.write(
                        token,
                        """{"supplierId":1,"channel":"SFTP","label":"No block"}""",
                    )
                assertEquals(HttpStatusCode.BadRequest, sftpWithoutBlock.status)
                assertEquals(
                    listOf("\"SFTP destinations require the sftp block\""),
                    sftpWithoutBlock.fieldErrors("channel"),
                )

                val spodWithSftpBlock =
                    admin.write(
                        token,
                        """
                        {
                          "supplierId":1,
                          "channel":"SPOD",
                          "label":"Wrong block",
                          "spod":{
                            "environment":"STAGING",
                            "accessToken":"spod-access-token",
                            "timeoutSeconds":30
                          },
                          "sftp":{
                            "host":"sftp.example.test",
                            "username":"voenix",
                            "password":"super-secret",
                            "hostKeyFingerprint":"SHA256:0123456789abcdef",
                            "timeoutSeconds":30
                          }
                        }
                        """
                            .trimIndent(),
                    )
                assertEquals(HttpStatusCode.BadRequest, spodWithSftpBlock.status)
                assertFalse(spodWithSftpBlock.bodyAsText().contains("spod-access-token"))
                assertFalse(spodWithSftpBlock.bodyAsText().contains("super-secret"))
                assertEquals(
                    listOf("\"SPOD destinations must not carry the sftp block\""),
                    spodWithSftpBlock.fieldErrors("channel"),
                )
                assertEquals(0, countRows(dataSource, "voenix.production_destinations"))
            }
        }
    }

    /**
     * A destination's channel is fixed at creation. Open `production_deliveries` rows point at the
     * destination, and nothing would invalidate them if the channel underneath them changed — so
     * the replace refuses instead, and an admin who wants the other channel creates a destination.
     */
    @Test
    fun `a replace cannot change the channel of a stored destination`() {
        migratedDataSource("production-destination-channel-immutable-test").use { dataSource ->
            resetProductionTables(dataSource)
            insertSupplier(dataSource, "Acme")
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application { installDestinationTestApplication(database) }
                val admin = signedInAdmin()
                val token = antiforgeryToken(admin)

                assertEquals(HttpStatusCode.Created, admin.write(token, SFTP_BODY).status)

                val switched = admin.write(token, spodBody(), id = 1)
                assertEquals(HttpStatusCode.BadRequest, switched.status)
                assertEquals(
                    listOf("\"Channel cannot be changed after creation\""),
                    switched.fieldErrors("channel"),
                )
                assertEquals(1, countRows(dataSource, "voenix.production_destination_sftp"))
                assertEquals(0, countRows(dataSource, "voenix.production_destination_spod"))
            }
        }
    }

    /**
     * The write side of the print-on-demand configuration rule. A deployment without a
     * `production.spod` block refuses to start once a SPOD destination exists — a channel whose
     * shipments arrive by webhook cannot report a single one without the secret that authorizes the
     * callback. So the destination that would cause that must not be storable in the first place:
     * the startup check catches the row that is already there, the service refuses the one somebody
     * is adding now.
     */
    @Test
    fun `a spod destination is refused when the deployment has no spod configuration`() {
        migratedDataSource("production-destination-spod-unconfigured-test").use { dataSource ->
            resetProductionTables(dataSource)
            insertSupplier(dataSource, "Spreadconnect")
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application { installDestinationTestApplication(database, spodConfigured = false) }
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
                assertEquals(0, countRows(dataSource, "voenix.production_destinations"))
            }
        }
    }

    private fun spodBody(enabled: Boolean = true): String =
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

    private fun io.ktor.server.application.Application.installDestinationTestApplication(
        database: Database,
        spodConfigured: Boolean = true,
    ) {
        installHttpRuntime()
        installAuthModule(AuthSettings("production-destination-crud-session-secret"))
        installProductionModule(database, spodConfigured = spodConfigured)
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

    private suspend fun HttpResponse.body(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.fieldErrors(field: String): List<String> =
        body().getValue("errors").jsonObject.getValue(field).jsonArray.map(Any::toString)

    private fun insertSupplier(dataSource: HikariDataSource, name: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO voenix.suppliers (name) VALUES (?)").use {
                statement ->
                statement.setString(1, name)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun storedPassword(dataSource: HikariDataSource): String? =
        singleValue(dataSource, "SELECT password FROM voenix.production_destination_sftp")

    private fun storedAccessToken(dataSource: HikariDataSource): String? =
        singleValue(
            dataSource,
            "SELECT access_token FROM voenix.production_destination_spod WHERE id = 1",
        )

    private fun singleValue(dataSource: HikariDataSource, sql: String): String? =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }
        }

    private fun countRows(dataSource: HikariDataSource, table: String): Int =
        checkNotNull(singleValue(dataSource, "SELECT count(*) FROM $table")).toInt()

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

        /**
         * One character longer than the password limit — and blank, so the length rule of
         * `SftpDestinationInput.validate()` and the service's "required" rule both fire and the
         * builder keeps both messages. This suite installs no `RequestValidation`; in the deployed
         * app the plugin's length rule refuses the body first.
         */
        const val OVERLONG_PASSWORD_LENGTH = 256
    }
}
