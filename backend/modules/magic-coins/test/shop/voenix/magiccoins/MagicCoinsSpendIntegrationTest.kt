package shop.voenix.magiccoins

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

internal class MagicCoinsSpendIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `spending creates the balance on first contact and deducts one coin`() {
        withService("magic-coins-spend-first") { service, dataSource ->
            val owner = MagicCoinsOwner.Guest("spend-first-contact-token")

            assertTrue(service.trySpendForGeneration(owner))
            assertEquals(OperationResult.Success(9), service.balance(owner))
            assertEquals(OperationResult.Success(true), service.hasEnoughForGeneration(owner))
            assertEquals(
                1,
                MagicCoinsTestSupport.count(dataSource, "SELECT COUNT(*) FROM voenix.magic_coins"),
            )
        }
    }

    @Test
    fun `spending is refused at zero balance`() {
        withService("magic-coins-spend-zero") { service, dataSource ->
            MagicCoinsTestSupport.seedUser(dataSource, 77)
            val owner = MagicCoinsOwner.User(77)
            assertEquals(OperationResult.Success(10), service.balance(owner))
            setBalance(dataSource, userId = 77, balance = 0)

            assertEquals(OperationResult.Success(false), service.hasEnoughForGeneration(owner))
            assertFalse(service.trySpendForGeneration(owner))
            assertEquals(OperationResult.Success(0), service.balance(owner))
        }
    }

    @Test
    fun `concurrent spends with one coin left allow exactly one success`() {
        withService("magic-coins-spend-concurrent") { service, dataSource ->
            val owner = MagicCoinsOwner.Guest("spend-concurrency-token")
            assertEquals(OperationResult.Success(10), service.balance(owner))
            setBalance(dataSource, guestSessionToken = "spend-concurrency-token", balance = 1)

            val outcomes = coroutineScope {
                List(8) { async { service.trySpendForGeneration(owner) } }.awaitAll()
            }

            assertEquals(1, outcomes.count { it })
            assertEquals(OperationResult.Success(0), service.balance(owner))
        }
    }

    @Test
    fun `concurrent first reads for the same guest create exactly one row`() {
        withService("magic-coins-spend-race") { service, dataSource ->
            val owner = MagicCoinsOwner.Guest("get-or-create-race-token")

            val results = coroutineScope { List(8) { async { service.balance(owner) } }.awaitAll() }

            results.forEach { result -> assertEquals(OperationResult.Success(10), result) }
            assertEquals(
                1,
                MagicCoinsTestSupport.count(dataSource, "SELECT COUNT(*) FROM voenix.magic_coins"),
            )
        }
    }

    @Test
    fun `an unavailable database becomes UnexpectedFailure and an unspent generation`() {
        val dataSource = migratedDataSource("magic-coins-spend-unavailable")
        dataSource.close()
        val service =
            MagicCoinsService(MagicCoinsRepository(Database.connect(datasource = dataSource)))

        runBlocking {
            val owner = MagicCoinsOwner.Guest("unavailable-database-token")
            assertEquals(OperationResult.UnexpectedFailure, service.balance(owner))
            assertEquals(OperationResult.UnexpectedFailure, service.hasEnoughForGeneration(owner))
            assertFalse(service.trySpendForGeneration(owner))
        }
    }

    private fun withService(
        poolName: String,
        block: suspend (MagicCoinsService, HikariDataSource) -> Unit,
    ) {
        migratedDataSource(poolName).use { dataSource ->
            MagicCoinsTestSupport.truncateMagicCoins(dataSource)
            val database = Database.connect(datasource = dataSource)
            val service = MagicCoinsService(MagicCoinsRepository(database))
            runBlocking { block(service, dataSource) }
        }
    }

    private fun setBalance(
        dataSource: HikariDataSource,
        userId: Long? = null,
        guestSessionToken: String? = null,
        balance: Int,
    ) {
        val predicate =
            if (userId != null) {
                "user_id = $userId"
            } else {
                "guest_session_token = '$guestSessionToken'"
            }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "UPDATE voenix.magic_coins SET balance = $balance WHERE $predicate"
                )
            }
        }
    }
}
