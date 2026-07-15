package shop.voenix.supplier

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
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.ApplicationAuth
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.country.createCountryModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.supplier.installSupplierModule
import shop.voenix.testing.PostgresIntegrationTest

internal class SupplierAdminCrudIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `admin can create list read fully replace and delete a supplier`() {
        migratedDataSource("supplier-admin-crud-test").use { dataSource ->
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application {
                    installHttpRuntime()
                    ApplicationAuth.install(
                        this,
                        AuthSettings("supplier-admin-crud-session-secret"),
                    )
                    installSupplierModule(database, createCountryModule(database).reader)
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
                    admin.post("/api/admin/suppliers") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "name":" Acme ",
                              "title":" Dr. ",
                              "firstName":" Ada ",
                              "lastName":" Lovelace ",
                              "city":" Berlin ",
                              "countryId":1,
                              "email":" ada@example.test "
                            }
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.Created, created.status)
                assertEquals(
                    "/api/admin/suppliers/1",
                    created.headers[HttpHeaders.Location],
                )
                val createdBody = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals("Acme", createdBody.getValue("name").jsonPrimitive.content)
                assertEquals("Berlin", createdBody.getValue("city").jsonPrimitive.content)
                assertEquals(
                    "DE",
                    createdBody
                        .getValue("country")
                        .jsonObject
                        .getValue("countryCode")
                        .jsonPrimitive
                        .content,
                )
                assertTrue(createdBody.containsKey("website"))

                val listed =
                    Json.parseToJsonElement(admin.get("/api/admin/suppliers").bodyAsText())
                        .jsonArray
                assertEquals(1, listed.size)
                assertEquals(
                    createdBody,
                    listed.single().jsonObject,
                )

                assertEquals(
                    createdBody,
                    Json.parseToJsonElement(admin.get("/api/admin/suppliers/1").bodyAsText())
                        .jsonObject,
                )

                val updated =
                    admin.put("/api/admin/suppliers/1") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "name":" Globex ",
                              "title":null,
                              "firstName":null,
                              "lastName":null,
                              "street":null,
                              "houseNumber":null,
                              "city":null,
                              "postalCode":null,
                              "countryId":null,
                              "phoneNumber1":null,
                              "phoneNumber2":null,
                              "phoneNumber3":null,
                              "email":null,
                              "website":null
                            }
                            """
                                .trimIndent()
                        )
                    }
                assertEquals(HttpStatusCode.OK, updated.status)
                val updatedBody = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
                assertEquals("Globex", updatedBody.getValue("name").jsonPrimitive.content)
                listOf("title", "firstName", "city", "countryId", "country", "email").forEach {
                    field ->
                    assertEquals("null", updatedBody.getValue(field).toString())
                }

                val deleted =
                    admin.delete("/api/admin/suppliers/1") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                    }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals("", deleted.bodyAsText())

                val missing = admin.get("/api/admin/suppliers/1")
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals(
                    Json.parseToJsonElement("""{"message":"Supplier not found","errors":{}}"""),
                    Json.parseToJsonElement(missing.bodyAsText()),
                )
            }
        }
    }
}
