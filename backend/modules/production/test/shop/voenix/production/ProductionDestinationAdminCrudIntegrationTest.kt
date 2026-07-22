package shop.voenix.production

import com.zaxxer.hikari.HikariDataSource
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
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import shop.voenix.testing.PostgresIntegrationTest

internal class ProductionDestinationAdminCrudIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `admin can create list read replace disable and delete a destination`() {
        migratedDataSource("production-destination-crud-test").use { dataSource ->
            insertSupplier(dataSource, "Acme")
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application {
                    installHttpRuntime()
                    installAuthModule(AuthSettings("production-destination-crud-session-secret"))
                    installProductionModule(database)
                    routing {
                        post("/test/sign-in") {
                            call.sessions.set(UserSession(userId = "11", role = "ADMIN"))
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }

                val admin = createClient { install(HttpCookies) }
                assertEquals(HttpStatusCode.OK, admin.post("/test/sign-in").status)
                val token =
                    Json.parseToJsonElement(admin.get("/api/antiforgery/token").bodyAsText())
                        .jsonObject
                        .getValue("requestToken")
                        .jsonPrimitive
                        .content

                val created =
                    admin.post("/api/admin/production/destinations") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "supplierId":1,
                              "channel":"SFTP",
                              "label":" Producer drop ",
                              "host":" sftp.example.test ",
                              "username":" voenix ",
                              "password":"super-secret",
                              "hostKeyFingerprint":" SHA256:0123456789abcdef ",
                              "timeoutSeconds":30,
                              "notificationEmail":" producer@example.test "
                            }
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.Created, created.status)
                assertEquals(
                    "/api/admin/production/destinations/1",
                    created.headers[HttpHeaders.Location],
                )
                assertFalse(created.bodyAsText().contains("super-secret"))
                val createdBody = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertFalse(createdBody.containsKey("password"))
                assertEquals("SFTP", createdBody.getValue("channel").jsonPrimitive.content)
                assertEquals("Producer drop", createdBody.getValue("label").jsonPrimitive.content)
                assertEquals("true", createdBody.getValue("enabled").toString())
                assertEquals("22", createdBody.getValue("port").toString())
                assertEquals("/", createdBody.getValue("remotePath").jsonPrimitive.content)
                assertEquals(
                    "producer@example.test",
                    createdBody.getValue("notificationEmail").jsonPrimitive.content,
                )
                assertEquals("super-secret", storedPassword(dataSource))

                val missingPassword =
                    admin.post("/api/admin/production/destinations") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "supplierId":1,
                              "channel":"SFTP",
                              "label":"No password",
                              "host":"sftp.example.test",
                              "username":"voenix",
                              "hostKeyFingerprint":"SHA256:0123456789abcdef",
                              "timeoutSeconds":30
                            }
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, missingPassword.status)
                assertEquals(
                    listOf("\"Password is required\""),
                    Json.parseToJsonElement(missingPassword.bodyAsText())
                        .jsonObject
                        .getValue("errors")
                        .jsonObject
                        .getValue("password")
                        .jsonArray
                        .map(Any::toString),
                )

                val unknownSupplier =
                    admin.post("/api/admin/production/destinations") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "supplierId":404,
                              "channel":"SFTP",
                              "label":"Unknown supplier",
                              "host":"sftp.example.test",
                              "username":"voenix",
                              "password":"unused-secret",
                              "hostKeyFingerprint":"SHA256:0123456789abcdef",
                              "timeoutSeconds":30
                            }
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.BadRequest, unknownSupplier.status)
                assertFalse(unknownSupplier.bodyAsText().contains("unused-secret"))
                assertEquals(
                    listOf("\"Supplier not found\""),
                    Json.parseToJsonElement(unknownSupplier.bodyAsText())
                        .jsonObject
                        .getValue("errors")
                        .jsonObject
                        .getValue("supplierId")
                        .jsonArray
                        .map(Any::toString),
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
                    admin.put("/api/admin/production/destinations/1") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "supplierId":1,
                              "channel":"SFTP",
                              "label":"Backup drop",
                              "enabled":false,
                              "host":"sftp.example.test",
                              "port":2222,
                              "username":"voenix",
                              "hostKeyFingerprint":"SHA256:fedcba9876543210",
                              "remotePath":"/drop",
                              "timeoutSeconds":60,
                              "notificationEmail":null,
                              "notificationName":null
                            }
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.OK, disabled.status)
                val disabledBody = Json.parseToJsonElement(disabled.bodyAsText()).jsonObject
                assertFalse(disabledBody.containsKey("password"))
                assertEquals("false", disabledBody.getValue("enabled").toString())
                assertEquals("2222", disabledBody.getValue("port").toString())
                assertEquals("/drop", disabledBody.getValue("remotePath").jsonPrimitive.content)
                assertEquals("null", disabledBody.getValue("notificationEmail").toString())
                assertEquals("super-secret", storedPassword(dataSource))

                val rotated =
                    admin.put("/api/admin/production/destinations/1") {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "supplierId":1,
                              "channel":"SFTP",
                              "label":"Backup drop",
                              "enabled":true,
                              "host":"sftp.example.test",
                              "port":2222,
                              "username":"voenix",
                              "password":"rotated-secret",
                              "hostKeyFingerprint":"SHA256:fedcba9876543210",
                              "remotePath":"/drop",
                              "timeoutSeconds":60,
                              "notificationEmail":null,
                              "notificationName":null
                            }
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.OK, rotated.status)
                assertFalse(rotated.bodyAsText().contains("rotated-secret"))
                assertEquals("rotated-secret", storedPassword(dataSource))

                val deleted =
                    admin.delete("/api/admin/production/destinations/1") {
                        header(AuthRouting.CSRF_HEADER, token)
                    }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals("", deleted.bodyAsText())

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

    private fun insertSupplier(
        dataSource: HikariDataSource,
        name: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO voenix.suppliers (name) VALUES (?)").use {
                statement ->
                statement.setString(1, name)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun storedPassword(dataSource: HikariDataSource): String =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT password FROM voenix.production_destinations WHERE id = 1"
                    )
                    .use { rows ->
                        check(rows.next())
                        rows.getString(1)
                    }
            }
        }
}
