package shop.voenix.magiccoins

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.setCookie
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.testing.PostgresIntegrationTest

internal class MagicCoinsBalanceRouteIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `first guest contact grants 10 coins and issues the guest cookie`() {
        withBalanceApplication("magic-coins-route-guest") { dataSource ->
            val client = createClient { install(HttpCookies) }

            val first = client.get("/api/magic-coins/balance")
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals("""{"balance":10}""", first.bodyAsText())
            assertEquals("no-store", first.headers[HttpHeaders.CacheControl])
            assertEquals(1, first.setCookie().count { it.name == "voenix.guest" })
            assertEquals(1, balanceRowCount(dataSource))

            val second = client.get("/api/magic-coins/balance")
            assertEquals("""{"balance":10}""", second.bodyAsText())
            assertTrue(second.setCookie().none { it.name == "voenix.guest" })
            assertEquals(1, balanceRowCount(dataSource))
        }
    }

    @Test
    fun `a tampered guest cookie becomes a fresh guest instead of an error`() {
        withBalanceApplication("magic-coins-route-tampered") { dataSource ->
            val tampered =
                client.get("/api/magic-coins/balance") {
                    header(HttpHeaders.Cookie, "voenix.guest=tampered-beyond-recognition")
                }
            assertEquals(HttpStatusCode.OK, tampered.status)
            assertEquals("""{"balance":10}""", tampered.bodyAsText())
            assertEquals(1, tampered.setCookie().count { it.name == "voenix.guest" })
            assertEquals(1, balanceRowCount(dataSource))
        }
    }

    @Test
    fun `a signed-in user owns a user balance and gets no guest cookie`() {
        withBalanceApplication("magic-coins-route-user") { dataSource ->
            val client = createClient { install(HttpCookies) }
            assertEquals(HttpStatusCode.OK, client.post("/test/sign-in/7").status)

            val response = client.get("/api/magic-coins/balance")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"balance":10}""", response.bodyAsText())
            assertTrue(response.setCookie().none { it.name == "voenix.guest" })
            assertEquals(1, userRowCount(dataSource, userId = 7))
            assertEquals(1, balanceRowCount(dataSource))
        }
    }

    @Test
    fun `a session user id that is not a positive long falls back to the guest path`() {
        withBalanceApplication("magic-coins-route-fallback") { dataSource ->
            for (userId in listOf("not-a-number", "0")) {
                val client = createClient { install(HttpCookies) }
                assertEquals(HttpStatusCode.OK, client.post("/test/sign-in/$userId").status)

                val response = client.get("/api/magic-coins/balance")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("""{"balance":10}""", response.bodyAsText())
                assertEquals(1, response.setCookie().count { it.name == "voenix.guest" })
            }
            assertEquals(2, guestRowCount(dataSource))
        }
    }

    @Test
    fun `concurrent first requests create exactly one balance row`() {
        withBalanceApplication("magic-coins-route-concurrent") { dataSource ->
            val client = createClient { install(HttpCookies) }
            assertEquals(HttpStatusCode.OK, client.post("/test/sign-in/42").status)

            val responses = coroutineScope {
                List(8) { async { client.get("/api/magic-coins/balance") } }.awaitAll()
            }
            responses.forEach { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("""{"balance":10}""", response.bodyAsText())
            }
            assertEquals(1, userRowCount(dataSource, userId = 42))
            assertEquals(1, balanceRowCount(dataSource))
        }
    }

    private fun withBalanceApplication(
        poolName: String,
        block: suspend ApplicationTestBuilder.(HikariDataSource) -> Unit,
    ) {
        migratedDataSource(poolName).use { dataSource ->
            MagicCoinsTestSupport.truncateMagicCoins(dataSource)
            val database = Database.connect(datasource = dataSource)
            val authSettings = AuthSettings("magic-coins-route-test-secret-with-32-bytes")

            testApplication {
                application {
                    installHttpRuntime()
                    installAuthModule(authSettings)
                    installMagicCoinsModule(database, GuestTokens(authSettings))
                    routing {
                        post("/test/sign-in/{userId}") {
                            call.sessions.set(
                                UserSession(
                                    userId = checkNotNull(call.parameters["userId"]),
                                    role = "USER",
                                )
                            )
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }

                block(dataSource)
            }
        }
    }

    private fun balanceRowCount(dataSource: HikariDataSource): Int =
        MagicCoinsTestSupport.count(dataSource, "SELECT COUNT(*) FROM voenix.magic_coins")

    private fun guestRowCount(dataSource: HikariDataSource): Int =
        MagicCoinsTestSupport.count(
            dataSource,
            "SELECT COUNT(*) FROM voenix.magic_coins WHERE guest_session_token IS NOT NULL",
        )

    private fun userRowCount(
        dataSource: HikariDataSource,
        userId: Long,
    ): Int =
        MagicCoinsTestSupport.count(
            dataSource,
            "SELECT COUNT(*) FROM voenix.magic_coins WHERE user_id = $userId",
        )
}
