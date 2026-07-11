package shop.voenix.country

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
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.ApplicationAuth
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.countryModule
import shop.voenix.http.HttpRuntime
import shop.voenix.testing.PostgresIntegrationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountryAdminCrudIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `admin can create read update and delete a country with antiforgery protection`() {
        migratedDataSource("country-admin-crud-test").use { dataSource ->
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application {
                    HttpRuntime.install(this)
                    ApplicationAuth.install(this, AuthSettings("country-admin-crud-session-secret"))
                    countryModule(database)
                    routing {
                        post("/test/sign-in") {
                            call.sessions.set(UserSession(userId = "11", role = "ADMIN"))
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }

                val admin = createClient { install(HttpCookies) }
                assertEquals(HttpStatusCode.OK, admin.post("/test/sign-in").status)
                val tokenResponse = admin.get("/api/antiforgery/token")
                assertEquals(HttpStatusCode.OK, tokenResponse.status)
                val csrfToken =
                    Json
                        .parseToJsonElement(tokenResponse.bodyAsText())
                        .jsonObject
                        .getValue("requestToken")
                        .jsonPrimitive.content

                val created =
                    admin.post("/api/admin/countries") {
                        header("X-XSRF-TOKEN", csrfToken)
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":" Denmark ","countryCode":" dk "}""")
                    }
                assertEquals(HttpStatusCode.Created, created.status)
                assertEquals(
                    "http://localhost/api/admin/countries/9",
                    checkNotNull(created.headers[HttpHeaders.Location]),
                )
                assertEquals(
                    """{"id":9,"name":"Denmark","countryCode":"DK"}""",
                    created.bodyAsText(),
                )

                val loaded = admin.get("/api/admin/countries/9")
                assertEquals(HttpStatusCode.OK, loaded.status)
                assertEquals(created.bodyAsText(), loaded.bodyAsText())

                val updated =
                    admin.put("/api/admin/countries/9") {
                        header("X-XSRF-TOKEN", csrfToken)
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":" Norway ","countryCode":" no "}""")
                    }
                assertEquals(HttpStatusCode.OK, updated.status)
                assertEquals(
                    """{"id":9,"name":"Norway","countryCode":"NO"}""",
                    updated.bodyAsText(),
                )

                val deleted =
                    admin.delete("/api/admin/countries/9") {
                        header("X-XSRF-TOKEN", csrfToken)
                    }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals("", deleted.bodyAsText())

                val missing = admin.get("/api/admin/countries/9")
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals(
                    """{"status":404,"detail":"Country not found"}""",
                    missing.bodyAsText(),
                )
            }
        }
    }
}
