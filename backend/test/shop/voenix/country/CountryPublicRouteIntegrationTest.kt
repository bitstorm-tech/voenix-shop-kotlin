package shop.voenix.country

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.ApplicationAuth
import shop.voenix.auth.AuthSettings
import shop.voenix.countryModule
import shop.voenix.http.HttpRuntime
import shop.voenix.testing.PostgresIntegrationTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CountryPublicRouteIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `public country list returns sorted seed countries with dial codes`() {
        migratedDataSource("country-public-route-test").use { dataSource ->
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application {
                    HttpRuntime.install(this)
                    ApplicationAuth.install(this, AuthSettings("country-public-test-session-secret"))
                    countryModule(database)
                }

                val response = client.get("/api/countries")

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(ContentType.Application.Json.withCharset(Charsets.UTF_8), response.contentType())
                assertEquals(
                    buildJsonObject {
                        putJsonArray("items") {
                            add(country("Austria", "AT", "+43"))
                            add(country("Belgium", "BE", "+32"))
                            add(country("Germany", "DE", "+49"))
                            add(country("Spain", "ES", "+34"))
                            add(country("France", "FR", "+33"))
                            add(country("Italy", "IT", "+39"))
                            add(country("Netherlands", "NL", "+31"))
                            add(country("Sweden", "SE", "+46"))
                        }
                    },
                    Json.parseToJsonElement(response.bodyAsText()),
                )
                assertEquals(
                    response.bodyAsText(),
                    client.get("/API/COUNTRIES/").bodyAsText(),
                )
            }
        }
    }

    private fun country(
        name: String,
        countryCode: String,
        dialCode: String,
    ) = buildJsonObject {
        put("name", name)
        put("countryCode", countryCode)
        put("dialCode", dialCode)
    }
}
