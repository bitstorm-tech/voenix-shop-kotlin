package shop.voenix.pricing

import io.ktor.client.plugins.cookies.HttpCookies
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
import shop.voenix.pricing.installPricingModule
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.createVatModule

internal class PriceAdminIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `admin can calculate create read default and update prices`() {
        migratedDataSource("pricing-admin-integration-test").use { dataSource ->
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        TRUNCATE voenix.prices, voenix.value_added_taxes RESTART IDENTITY CASCADE;
                        INSERT INTO voenix.value_added_taxes
                            (name, percent, description, is_default)
                        VALUES ('Standard', 19, NULL, TRUE);
                        """
                            .trimIndent()
                    )
                }
            }
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application {
                    installHttpRuntime()
                    ApplicationAuth.install(
                        this,
                        AuthSettings("pricing-admin-session-secret-for-tests"),
                    )
                    installPricingModule(database, createVatModule(database).reader)
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

                val defaultPrice = admin.get("/api/admin/prices/default")
                assertEquals(HttpStatusCode.OK, defaultPrice.status)
                assertEquals("null", priceJson(defaultPrice.bodyAsText()).getValue("id").toString())

                val calculated =
                    admin.post("/api/admin/prices/calculate") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(priceInputJson(total = 1_190))
                    }
                assertEquals(HttpStatusCode.OK, calculated.status)
                assertEquals(0, priceCount(dataSource))

                val created =
                    admin.post("/api/admin/prices") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(priceInputJson(total = 1_190))
                    }
                assertEquals(HttpStatusCode.Created, created.status)
                assertEquals("/api/admin/prices/1", created.headers[HttpHeaders.Location])
                assertEquals(
                    1,
                    priceJson(created.bodyAsText()).getValue("id").jsonPrimitive.content.toLong(),
                )

                assertEquals(created.bodyAsText(), admin.get("/api/admin/prices/1").bodyAsText())

                val updated =
                    admin.put("/api/admin/prices/1") {
                        header(ApplicationAuth.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(priceInputJson(total = 2_380))
                    }
                assertEquals(HttpStatusCode.OK, updated.status)
                assertEquals(
                    "2380",
                    priceJson(updated.bodyAsText())
                        .getValue("salesTotalInputCents")
                        .jsonPrimitive
                        .content,
                )
                assertEquals(HttpStatusCode.NotFound, admin.get("/api/admin/prices/404").status)
            }
        }
    }

    private fun priceJson(body: String) = Json.parseToJsonElement(body).jsonObject

    private fun priceInputJson(total: Int): String =
        """
        {
          "purchaseVatId": 1,
          "purchaseCalculationMode": "NET",
          "purchaseActiveRow": "COST",
          "purchasePriceInputCents": 1000,
          "purchaseCostInputCents": 0,
          "purchaseCostPercent": 0,
          "salesVatId": 1,
          "salesCalculationMode": "GROSS",
          "salesActiveRow": "TOTAL",
          "salesMarginInputCents": 0,
          "salesMarginPercent": 0,
          "salesTotalInputCents": $total
        }
        """
            .trimIndent()

    private fun priceCount(dataSource: com.zaxxer.hikari.HikariDataSource): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM voenix.prices").use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
        }
}
