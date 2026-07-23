package shop.voenix.production.delivery

import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.production.ProductionOutbox
import shop.voenix.testing.PostgresIntegrationTest

internal class ProductionOutboxIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `request stores one minimal reference row per order`() = runBlocking {
        migratedDataSource("production-outbox-reference-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val outbox = outbox(database)

            val first = request(database, outbox, orderId = 42)
            val duplicate = request(database, outbox, orderId = 42)

            assertEquals(first, duplicate)
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT order_id, attempt_count, last_error_code, processed_at " +
                                "FROM voenix.production_requests"
                        )
                        .use { rows ->
                            assertTrue(rows.next())
                            assertEquals(42, rows.getLong("order_id"))
                            assertEquals(0, rows.getInt("attempt_count"))
                            assertEquals(null, rows.getString("last_error_code"))
                            assertEquals(null, rows.getTimestamp("processed_at"))
                            assertFalse(rows.next())
                        }
                }
            }
        }
    }

    @Test
    fun `request joins and rolls back with the caller transaction`() = runBlocking {
        migratedDataSource("production-outbox-rollback-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val outbox = outbox(database)

            assertFailsWith<Rollback> {
                withContext(Dispatchers.IO) {
                    suspendTransaction(db = database) {
                        maxAttempts = 1
                        outbox.request(44)
                        throw Rollback()
                    }
                }
            }

            assertEquals(0, rowCount(dataSource))
            assertFailsWith<IllegalStateException> { outbox.request(45) }
            Unit
        }
    }

    @Test
    fun `a non positive order id is rejected before touching the database`() = runBlocking {
        migratedDataSource("production-outbox-invalid-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val outbox = outbox(database)

            listOf(0L, -7L).forEach { orderId ->
                assertFailsWith<IllegalArgumentException> {
                    withContext(Dispatchers.IO) {
                        suspendTransaction(db = database) {
                            maxAttempts = 1
                            outbox.request(orderId)
                        }
                    }
                }
            }

            assertEquals(0, rowCount(dataSource))
        }
    }

    @Test
    fun `concurrent duplicate request creates one row with the same id`() = runBlocking {
        migratedDataSource("production-outbox-concurrency-test").use { dataSource ->
            reset(dataSource)
            val database = Database.connect(dataSource)
            val outbox = outbox(database)

            val ids = coroutineScope {
                List(2) { async(Dispatchers.IO) { request(database, outbox, orderId = 46) } }
                    .awaitAll()
            }

            assertEquals(ids[0], ids[1])
            assertEquals(1, rowCount(dataSource))
        }
    }

    private suspend fun request(
        database: Database,
        outbox: ProductionOutbox,
        orderId: Long,
    ): Long =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                outbox.request(orderId)
            }
        }

    private fun outbox(database: Database): ProductionOutbox {
        val repository = ProductionRequestRepository(database)
        return ProductionOutbox { orderId -> repository.requestInCurrentTransaction(orderId) }
    }

    private fun reset(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.production_requests RESTART IDENTITY CASCADE")
            }
        }
    }

    private fun rowCount(dataSource: DataSource): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM voenix.production_requests").use { rows
                    ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private class Rollback : RuntimeException()
}
