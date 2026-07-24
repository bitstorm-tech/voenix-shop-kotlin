package shop.voenix.promotion

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.testing.PostgresIntegrationTest

internal class PromotionAdminCrudIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `admin can list and read seeded promotions with redemption counts`() {
        migratedDataSource("promotion-admin-read-test").use { dataSource ->
            seedPromotions(dataSource)
            val database = Database.connect(datasource = dataSource)

            testApplication {
                application {
                    installHttpRuntime()
                    installAuthModule(AuthSettings("promotion-admin-read-session-secret"))
                    installPromotionModule(database)
                    routing {
                        post("/test/sign-in") {
                            call.sessions.set(UserSession(userId = "11", role = "ADMIN"))
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }

                val admin = createClient { install(HttpCookies) }
                assertEquals(HttpStatusCode.OK, admin.post("/test/sign-in").status)

                val listed =
                    Json.parseToJsonElement(admin.get("/api/admin/promotions").bodyAsText())
                        .jsonArray
                assertEquals(
                    listOf("Autumn sale", "Winter sale", "Winter sale"),
                    listed.map { it.jsonObject.getValue("name").jsonPrimitive.content },
                )
                assertEquals(
                    listOf(3L, 1L, 2L),
                    listed.map { it.jsonObject.getValue("id").jsonPrimitive.content.toLong() },
                )

                val autumn = listed[0].jsonObject
                assertEquals("Autumn5", autumn.getValue("couponCode").jsonPrimitive.content)
                assertEquals(
                    """{"discountType":"FIXED_AMOUNT","discountValue":500.00}""",
                    autumn.getValue("discount").toString(),
                )
                assertEquals(0L, autumn.getValue("redemptionCount").jsonPrimitive.content.toLong())
                assertEquals(false, autumn.getValue("isLocked").jsonPrimitive.content.toBoolean())
                assertEquals("null", autumn.getValue("startsAt").toString())

                val winter = listed[1].jsonObject
                assertEquals(
                    """{"discountType":"PERCENTAGE","discountValue":10.00}""",
                    winter.getValue("discount").toString(),
                )
                assertEquals(2L, winter.getValue("redemptionCount").jsonPrimitive.content.toLong())
                assertEquals(true, winter.getValue("isLocked").jsonPrimitive.content.toBoolean())
                assertEquals(
                    "2026-01-01T00:00:00Z",
                    winter.getValue("startsAt").jsonPrimitive.content,
                )
                assertEquals(
                    "2026-03-01T00:00:00Z",
                    winter.getValue("endsAt").jsonPrimitive.content,
                )
                assertEquals(100, winter.getValue("usageLimitTotal").jsonPrimitive.content.toInt())
                assertEquals(1, winter.getValue("usageLimitPerUser").jsonPrimitive.content.toInt())

                val fetched = admin.get("/api/admin/promotions/1")
                assertEquals(HttpStatusCode.OK, fetched.status)
                assertEquals(
                    winter,
                    Json.parseToJsonElement(fetched.bodyAsText()).jsonObject,
                )

                val missing = admin.get("/api/admin/promotions/404")
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals(
                    Json.parseToJsonElement("""{"message":"Promotion not found","errors":{}}"""),
                    Json.parseToJsonElement(missing.bodyAsText()),
                )
            }
        }
    }

    private fun seedPromotions(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO voenix.promotions (
                        id, name, discount_type, discount_value, coupon_code,
                        coupon_code_normalized, starts_at, ends_at,
                        usage_limit_total, usage_limit_per_user, is_active
                    ) VALUES
                        (1, 'Winter sale', 'PERCENTAGE', 10.00, 'Winter10', 'WINTER10',
                         '2026-01-01T00:00:00Z', '2026-03-01T00:00:00Z', 100, 1, TRUE),
                        (2, 'Winter sale', 'PERCENTAGE', 15.00, 'Winter15', 'WINTER15',
                         NULL, NULL, NULL, NULL, FALSE),
                        (3, 'Autumn sale', 'FIXED_AMOUNT', 500.00, 'Autumn5', 'AUTUMN5',
                         NULL, NULL, NULL, NULL, TRUE);
                    INSERT INTO voenix.promotion_redemptions (promotion_id, user_id, redeemed_at)
                    VALUES
                        (1, 42, '2026-02-01T10:00:00Z'),
                        (1, NULL, '2026-02-02T10:00:00Z');
                    """
                        .trimIndent()
                )
            }
        }
    }
}
