package shop.voenix.vat

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.ApplicationAuth
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.http.installHttpRuntime
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.installVatModule

internal class VatAdminCrudIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `admin can create list read update and delete vat entries`() {
        migratedDataSource("vat-admin-crud-test").use { dataSource ->
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application {
                    installHttpRuntime()
                    ApplicationAuth.install(
                        this,
                        AuthSettings("vat-admin-crud-session-secret-for-tests"),
                    )
                    installVatModule(database)
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
                    admin.post("/api/admin/vat") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"name":" Standard ","percent":19,"description":" German rate ","isDefault":true}"""
                        )
                    }
                assertEquals(HttpStatusCode.Created, created.status)
                assertEquals("/api/admin/vat/1", created.headers[HttpHeaders.Location])
                assertEquals(
                    """{"id":1,"name":"Standard","percent":19,"description":"German rate","isDefault":true}""",
                    created.bodyAsText(),
                )

                assertEquals(created.bodyAsText(), admin.get("/api/admin/vat/1").bodyAsText())
                assertEquals("[${created.bodyAsText()}]", admin.get("/api/admin/vat").bodyAsText())

                val updated =
                    admin.put("/api/admin/vat/1") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"name":"Reduced","percent":7,"description":"   ","isDefault":false}"""
                        )
                    }
                assertEquals(HttpStatusCode.OK, updated.status)
                assertEquals(
                    """{"id":1,"name":"Reduced","percent":7,"description":null,"isDefault":false}""",
                    updated.bodyAsText(),
                )

                val deleted =
                    admin.delete("/api/admin/vat/1") { header(ApplicationAuth.CSRF_HEADER, token) }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals("", deleted.bodyAsText())
                assertEquals(HttpStatusCode.NotFound, admin.get("/api/admin/vat/1").status)
            }
        }
    }
}
