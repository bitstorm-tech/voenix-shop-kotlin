package shop.voenix.magiccoins

import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Proves the module's exported capability against real PostgreSQL: what another compilation module
 * receives from [installMagicCoinsModule] can check and charge a balance, and nothing else.
 *
 * The test deliberately uses only [GenerationCoins] and SQL. The internal `balance` operation is
 * never called, so the assertions hold exactly what a consumer outside this module can observe.
 */
internal class GenerationCoinsIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the exported capability charges exactly one coin per generation`() {
        withGenerationCoins("magic-coins-capability-spend") { coins, dataSource ->
            val owner = MagicCoinsOwner.Guest(SPEND_TOKEN)

            assertEquals(OperationResult.Success(true), coins.hasEnoughForGeneration(owner))
            assertTrue(coins.trySpendForGeneration(owner))

            assertEquals(9, balanceOf(dataSource, SPEND_TOKEN))
            assertEquals(
                1,
                MagicCoinsTestSupport.count(dataSource, "SELECT COUNT(*) FROM voenix.magic_coins"),
            )
        }
    }

    @Test
    fun `the exported capability refuses a generation at zero balance`() {
        withGenerationCoins("magic-coins-capability-zero") { coins, dataSource ->
            val owner = MagicCoinsOwner.Guest(ZERO_TOKEN)
            assertTrue(coins.trySpendForGeneration(owner))
            setBalanceToZero(dataSource, ZERO_TOKEN)

            assertEquals(OperationResult.Success(false), coins.hasEnoughForGeneration(owner))
            assertFalse(coins.trySpendForGeneration(owner))
            assertEquals(0, balanceOf(dataSource, ZERO_TOKEN))
        }
    }

    private fun withGenerationCoins(
        poolName: String,
        block: suspend (GenerationCoins, HikariDataSource) -> Unit,
    ) {
        migratedDataSource(poolName).use { dataSource ->
            MagicCoinsTestSupport.truncateMagicCoins(dataSource)
            val database = Database.connect(datasource = dataSource)
            val authSettings = AuthSettings("magic-coins-capability-test-secret-32")

            testApplication {
                var exported: GenerationCoins? = null
                application {
                    exported = installMagicCoinsModule(database, GuestTokens(authSettings))
                }
                startApplication()

                block(
                    checkNotNull(exported) { "The module did not export its capability" },
                    dataSource,
                )
            }
        }
    }

    private fun balanceOf(
        dataSource: HikariDataSource,
        guestSessionToken: String,
    ): Int =
        MagicCoinsTestSupport.count(
            dataSource,
            "SELECT balance FROM voenix.magic_coins " +
                "WHERE guest_session_token = '$guestSessionToken'",
        )

    private fun setBalanceToZero(
        dataSource: HikariDataSource,
        guestSessionToken: String,
    ) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "UPDATE voenix.magic_coins SET balance = 0 " +
                        "WHERE guest_session_token = '$guestSessionToken'"
                )
            }
        }
    }

    private companion object {
        const val SPEND_TOKEN = "capability-spend-token"
        const val ZERO_TOKEN = "capability-zero-token"
    }
}
