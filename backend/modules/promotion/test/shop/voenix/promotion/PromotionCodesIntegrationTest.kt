package shop.voenix.promotion

import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Covers the exported [PromotionCodes] capability against a real PostgreSQL database: every reason
 * a code can fail, the redemption record itself, and the two races that the usage limits and the
 * admin lock semantics depend on.
 */
internal class PromotionCodesIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `validate resolves a code with trimming and case-insensitive matching`() {
        withCodes("promotion-codes-resolve-test") { codes, _, _ ->
            assertEquals(
                PromotionCodeResult.Applicable(
                    id = 1,
                    name = "Winter sale",
                    couponCode = "Winter10",
                    discount = Discount.Percentage(BigDecimal("10.00")),
                ),
                codes.validate("  wInTeR10  "),
            )
        }
    }

    @Test
    fun `validate reports every reason a code cannot be applied`() {
        withCodes("promotion-codes-failures-test") { codes, _, _ ->
            assertEquals(PromotionCodeResult.InvalidCode, codes.validate("Nothing10"))
            assertEquals(PromotionCodeResult.Inactive, codes.validate("Off5"))
            assertEquals(PromotionCodeResult.NotStarted, codes.validate("Soon5"))
            assertEquals(PromotionCodeResult.Expired, codes.validate("Past5"))

            assertEquals(PromotionCodeResult.TotalExhausted, codes.validate("Public1"))
            assertEquals(PromotionCodeResult.TotalExhausted, codes.validate("Public1", userId = 42))

            assertEquals(
                PromotionCodeResult.PerUserExhausted,
                codes.validate("Personal1", userId = 42),
            )
            assertEquals(
                "Personal sale",
                (codes.validate("Personal1", userId = 43) as PromotionCodeResult.Applicable).name,
                "Another customer still has their own allowance",
            )
        }
    }

    @Test
    fun `a guest facing a per-user limit is told to log in before anything is counted`() {
        withCodes("promotion-codes-login-required-test") { codes, _, _ ->
            // The total limit of promotion 7 is already exhausted, yet the guest learns that
            // logging in may still help; a signed-in customer sees the real exhaustion.
            assertEquals(PromotionCodeResult.LoginRequired, codes.validate("Both1"))
            assertEquals(PromotionCodeResult.TotalExhausted, codes.validate("Both1", userId = 42))
        }
    }

    @Test
    fun `the activity window includes both of its boundaries`() {
        migratedDataSource("promotion-codes-window-test").use { dataSource ->
            seedPromotions(dataSource)
            val database = Database.connect(datasource = dataSource)

            runBlocking {
                val atStart = codesAt(database, "2026-01-01T00:00:00Z").validate("Winter10")
                val atEnd = codesAt(database, "2026-03-01T00:00:00Z").validate("Winter10")
                val beforeStart = codesAt(database, "2025-12-31T23:59:59Z").validate("Winter10")
                val afterEnd = codesAt(database, "2026-03-01T00:00:01Z").validate("Winter10")

                assertEquals("Winter sale", (atStart as PromotionCodeResult.Applicable).name)
                assertEquals("Winter sale", (atEnd as PromotionCodeResult.Applicable).name)
                assertEquals(PromotionCodeResult.NotStarted, beforeStart)
                assertEquals(PromotionCodeResult.Expired, afterEnd)
            }
        }
    }

    @Test
    fun `redeem records the promotion, the optional user, the order, and the time`() {
        withCodes("promotion-codes-redeem-test") { codes, database, dataSource ->
            assertEquals(
                PromotionCodeResult.Applicable(
                    id = 8,
                    name = "Open public sale",
                    couponCode = "Open2",
                    discount = Discount.Percentage(BigDecimal("15.00")),
                ),
                redeem(codes, database, promotionId = 8, userId = 42, orderId = 4),
            )
            // A guest may redeem a promotion that only carries a total limit.
            assertEquals(
                PromotionCodeResult.Applicable(
                    id = 8,
                    name = "Open public sale",
                    couponCode = "Open2",
                    discount = Discount.Percentage(BigDecimal("15.00")),
                ),
                redeem(codes, database, promotionId = 8, orderId = 5),
            )

            assertEquals(listOf(42L, null), redeemedUsersOf(dataSource, promotionId = 8))
            assertEquals(listOf(4L, 5L), redeemedOrdersOf(dataSource, promotionId = 8))
            // The total limit of two is now used up for everybody.
            assertEquals(
                PromotionCodeResult.TotalExhausted,
                redeem(codes, database, promotionId = 8, orderId = 6),
            )
            assertEquals(
                PromotionCodeResult.TotalExhausted,
                redeem(codes, database, promotionId = 8, userId = 43, orderId = 7),
            )
            assertEquals(2, redeemedUsersOf(dataSource, promotionId = 8).size)
        }
    }

    @Test
    fun `redeem re-checks the limits and refuses an unknown promotion`() {
        withCodes("promotion-codes-redeem-failures-test") { codes, database, dataSource ->
            assertEquals(
                PromotionCodeResult.TotalExhausted,
                redeem(codes, database, promotionId = 5, orderId = 8),
            )
            assertEquals(
                PromotionCodeResult.PerUserExhausted,
                redeem(codes, database, promotionId = 6, userId = 42, orderId = 9),
            )
            assertEquals(
                PromotionCodeResult.LoginRequired,
                redeem(codes, database, promotionId = 7, orderId = 10),
            )
            assertEquals(
                PromotionCodeResult.InvalidCode,
                redeem(codes, database, promotionId = 404, orderId = 11),
            )

            assertEquals(1, redeemedUsersOf(dataSource, promotionId = 5).size)
            assertEquals(1, redeemedUsersOf(dataSource, promotionId = 6).size)
            assertEquals(1, redeemedUsersOf(dataSource, promotionId = 7).size)
        }
    }

    @Test
    fun `redeem joins the caller transaction and rolls back with it`() {
        withCodes("promotion-codes-redeem-transaction-test") { codes, database, dataSource ->
            assertFailsWith<Rollback> {
                withContext(Dispatchers.IO) {
                    suspendTransaction(db = database) {
                        maxAttempts = 1
                        codes.redeem(promotionId = 8, userId = 42, orderId = 12)
                        throw Rollback()
                    }
                }
            }

            assertEquals(
                emptyList(),
                redeemedUsersOf(dataSource, promotionId = 8),
                "A caller transaction that fails must leave no redemption behind",
            )

            // Without a caller transaction there is nothing to join, which is a wiring bug.
            assertFailsWith<IllegalStateException> {
                codes.redeem(promotionId = 8, userId = 42, orderId = 13)
            }
            assertEquals(emptyList(), redeemedUsersOf(dataSource, promotionId = 8))
        }
    }

    @Test
    fun `an order redeems a promotion at most once`() {
        withCodes("promotion-codes-redeem-once-per-order-test") { codes, database, dataSource ->
            assertIs<PromotionCodeResult.Applicable>(
                redeem(codes, database, promotionId = 8, userId = 42, orderId = 14)
            )

            val duplicate =
                assertFailsWith<SQLException> {
                    redeem(codes, database, promotionId = 8, userId = 42, orderId = 14)
                }

            assertTrue(
                UNIQUE_VIOLATION in sqlStatesOf(duplicate),
                "The unique order id must reject a second redemption of the same order",
            )
            assertEquals(listOf(14L), redeemedOrdersOf(dataSource, promotionId = 8))
        }
    }

    @Test
    fun `find resolves stored promotion ids and leaves unknown ones out`() {
        withCodes("promotion-codes-find-test") { codes, _, _ ->
            val found = codes.find(setOf(1, 2, 404))

            assertEquals(
                setOf(1L, 2L),
                found.keys,
                "An id that names no promotion is absent, never null-valued",
            )
            assertEquals(
                PromotionCodeResult.Applicable(
                    id = 1,
                    name = "Winter sale",
                    couponCode = "Winter10",
                    discount = Discount.Percentage(BigDecimal("10.00")),
                ),
                found[1],
            )
            // Availability is not this read's business: the switched-off promotion is answered
            // with its current master data, including a fixed-amount discount.
            assertEquals(
                PromotionCodeResult.Applicable(
                    id = 2,
                    name = "Switched off sale",
                    couponCode = "Off5",
                    discount = Discount.FixedAmount(BigDecimal("500.00")),
                ),
                found[2],
            )
        }
    }

    @Test
    fun `find answers an empty id set without touching the database`() {
        migratedDataSource("promotion-codes-find-empty-test").use { dataSource ->
            seedPromotions(dataSource)
            val counting = CountingDataSource(dataSource)
            val codes = codesAt(Database.connect(datasource = counting), NOW)

            runBlocking {
                counting.statements.clear()
                assertEquals(emptyMap(), codes.find(emptySet()))
                assertEquals(emptyList(), counting.statements.toList())

                // A batch that does hold ids costs the promotion read and its redemption counts.
                assertEquals(setOf(1L), codes.find(setOf(1)).keys)
                assertTrue(counting.statements.isNotEmpty())
            }
        }
    }

    @Test
    fun `two concurrent redeems against a total limit of one produce exactly one redemption`() {
        migratedDataSource("promotion-codes-race-test").use { dataSource ->
            seedPromotions(dataSource)
            val database = Database.connect(datasource = dataSource)
            val codes = codesAt(database, NOW)

            runBlocking {
                val results = coroutineScope {
                    listOf(
                            async(Dispatchers.IO) {
                                redeem(codes, database, promotionId = 9, userId = 42, orderId = 15)
                            },
                            async(Dispatchers.IO) {
                                redeem(codes, database, promotionId = 9, userId = 43, orderId = 16)
                            },
                        )
                        .map { deferred -> deferred.await() }
                }

                assertEquals(1, results.count { it is PromotionCodeResult.Applicable })
                assertEquals(1, results.count { it == PromotionCodeResult.TotalExhausted })
                assertEquals(1, redeemedUsersOf(dataSource, promotionId = 9).size)
            }
        }
    }

    @Test
    fun `an admin update that races a redemption sees the redemption and locks`() {
        migratedDataSource("promotion-codes-lock-race-test").use { dataSource ->
            seedPromotions(dataSource)
            val service =
                PromotionService(
                    PromotionRepository(Database.connect(datasource = dataSource)),
                    fixedClock(NOW),
                )

            dataSource.connection.use { redeemer ->
                redeemer.autoCommit = false
                redeemer.createStatement().use { statement ->
                    statement.execute("SELECT id FROM voenix.promotions WHERE id = 8 FOR UPDATE")
                }

                runBlocking {
                    val update =
                        async(Dispatchers.IO) {
                            service.update(8, promotionInput("Renamed sale", "Renamed2"))
                        }
                    // Waiting for the update to actually block is what makes this test evidence
                    // rather than a coin flip: only then does the redemption commit inside the
                    // window that a bare `NOT EXISTS` guard would miss.
                    val updateWasBlocked = awaitBlockedPromotionStatement(redeemer)
                    withContext(Dispatchers.IO) {
                        redeemer.createStatement().use { statement ->
                            statement.execute(
                                "INSERT INTO voenix.promotion_redemptions " +
                                    "(promotion_id, user_id, order_id, redeemed_at) " +
                                    "VALUES (8, 42, 17, '2026-02-01T12:00:00Z')"
                            )
                        }
                        redeemer.commit()
                    }
                    // Assert only once the lock is released, so a failure cannot leave the racing
                    // update blocked forever.
                    val result = update.await()

                    assertTrue(
                        updateWasBlocked,
                        "The racing update never reached the promotion row lock",
                    )
                    assertSame(OperationResult.Conflict, result)
                }
            }

            runBlocking {
                val stored = service.get(8)
                assertEquals(
                    "Open public sale",
                    (stored as OperationResult.Success<Promotion>).value.name,
                )
            }
        }
    }

    /**
     * Whether another session is waiting for a lock, polled over [connection] — the one session
     * that is guaranteed not to be blocked, because it holds the lock. A session waiting for a row
     * lock waits on the holder's transaction id rather than on the table, so this asks
     * `pg_stat_activity` instead of matching a relation in `pg_locks`.
     *
     * Never throws and never waits forever: the caller must reach the statement that releases the
     * lock, or the racing update would block for the rest of the test run.
     */
    private suspend fun awaitBlockedPromotionStatement(connection: Connection): Boolean {
        repeat(LOCK_POLL_ATTEMPTS) {
            val blocked =
                withContext(Dispatchers.IO) {
                    connection.createStatement().use { statement ->
                        statement
                            .executeQuery(
                                "SELECT count(*) FROM pg_stat_activity " +
                                    "WHERE datname = current_database() " +
                                    "AND wait_event_type = 'Lock'"
                            )
                            .use { rows ->
                                check(rows.next()) { "count() always returns a row" }
                                rows.getLong(1)
                            }
                    }
                }
            if (blocked > 0) return true
            delay(LOCK_POLL_INTERVAL_MILLIS)
        }
        return false
    }

    /** Runs [block] against the capability over a freshly seeded database. */
    private fun withCodes(
        poolName: String,
        block: suspend (PromotionCodes, Database, DataSource) -> Unit,
    ) {
        migratedDataSource(poolName).use { dataSource ->
            seedPromotions(dataSource)
            val database = Database.connect(datasource = dataSource)
            runBlocking { block(codesAt(database, NOW), database, dataSource) }
        }
    }

    /**
     * Redeems the way the paid-order workflow will: inside a transaction the caller owns, which is
     * the only way [PromotionCodes.redeem] may be called at all.
     */
    private suspend fun redeem(
        codes: PromotionCodes,
        database: Database,
        promotionId: Long,
        orderId: Long,
        userId: Long? = null,
    ): PromotionCodeResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                codes.redeem(promotionId, orderId, userId)
            }
        }

    /** The SQL states of [throwable] and every cause behind it. */
    private fun sqlStatesOf(throwable: Throwable): List<String?> =
        generateSequence(throwable) { it.cause }
            .filterIsInstance<SQLException>()
            .map { exception -> exception.sqlState }
            .toList()

    private fun codesAt(
        database: Database,
        now: String,
    ): PromotionCodes = PromotionService(PromotionRepository(database), fixedClock(now))

    private fun fixedClock(now: String): Clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC)

    private fun promotionInput(
        name: String,
        code: String,
    ): PromotionInput =
        PromotionInput(
            name = name,
            couponCode = code,
            discountType = "PERCENTAGE",
            discountValue = BigDecimal("10.00"),
            isActive = true,
        )

    /** The user ids of the recorded redemptions of [promotionId], oldest first. */
    private fun redeemedUsersOf(
        dataSource: DataSource,
        promotionId: Long,
    ): List<Long?> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT user_id, redeemed_at FROM voenix.promotion_redemptions " +
                        "WHERE promotion_id = ? ORDER BY id"
                )
                .use { statement ->
                    statement.setLong(1, promotionId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                checkNotNull(rows.getTimestamp("redeemed_at")) {
                                    "A redemption must record its time"
                                }
                                val userId = rows.getLong("user_id")
                                add(if (rows.wasNull()) null else userId)
                            }
                        }
                    }
                }
        }

    /** The order ids of the recorded redemptions of [promotionId], oldest first. */
    private fun redeemedOrdersOf(
        dataSource: DataSource,
        promotionId: Long,
    ): List<Long> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT order_id FROM voenix.promotion_redemptions " +
                        "WHERE promotion_id = ? ORDER BY id"
                )
                .use { statement ->
                    statement.setLong(1, promotionId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(rows.getLong("order_id"))
                            }
                        }
                    }
                }
        }

    private fun seedPromotions(dataSource: DataSource) {
        insertOrders(dataSource, *(1L..ORDER_COUNT).toList().toLongArray())
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    DELETE FROM voenix.promotion_redemptions;
                    DELETE FROM voenix.promotions;
                    INSERT INTO voenix.promotions (
                        id, name, discount_type, discount_value, coupon_code,
                        coupon_code_normalized, starts_at, ends_at,
                        usage_limit_total, usage_limit_per_user, is_active
                    ) VALUES
                        (1, 'Winter sale', 'PERCENTAGE', 10.00, 'Winter10', 'WINTER10',
                         '2026-01-01T00:00:00Z', '2026-03-01T00:00:00Z', NULL, NULL, TRUE),
                        (2, 'Switched off sale', 'FIXED_AMOUNT', 500.00, 'Off5', 'OFF5',
                         NULL, NULL, NULL, NULL, FALSE),
                        (3, 'Future sale', 'PERCENTAGE', 5.00, 'Soon5', 'SOON5',
                         '2026-06-01T00:00:00Z', NULL, NULL, NULL, TRUE),
                        (4, 'Past sale', 'PERCENTAGE', 5.00, 'Past5', 'PAST5',
                         NULL, '2026-01-15T00:00:00Z', NULL, NULL, TRUE),
                        (5, 'Public sale', 'PERCENTAGE', 30.00, 'Public1', 'PUBLIC1',
                         NULL, NULL, 1, NULL, TRUE),
                        (6, 'Personal sale', 'PERCENTAGE', 25.00, 'Personal1', 'PERSONAL1',
                         NULL, NULL, NULL, 1, TRUE),
                        (7, 'Members sale', 'PERCENTAGE', 20.00, 'Both1', 'BOTH1',
                         NULL, NULL, 1, 1, TRUE),
                        (8, 'Open public sale', 'PERCENTAGE', 15.00, 'Open2', 'OPEN2',
                         NULL, NULL, 2, NULL, TRUE),
                        (9, 'Race sale', 'PERCENTAGE', 50.00, 'Race1', 'RACE1',
                         NULL, NULL, 1, NULL, TRUE);
                    INSERT INTO voenix.promotion_redemptions
                        (promotion_id, user_id, order_id, redeemed_at)
                    VALUES
                        (5, NULL, 1, '2026-01-20T10:00:00Z'),
                        (6, 42, 2, '2026-01-20T10:00:00Z'),
                        (7, 42, 3, '2026-01-20T10:00:00Z');
                    """
                        .trimIndent()
                )
            }
        }
    }

    /** The failure of a caller transaction that had already redeemed a promotion. */
    private class Rollback : RuntimeException()

    private companion object {
        /** Inside the activity window of the seeded `Winter10` promotion. */
        const val NOW = "2026-02-01T12:00:00Z"

        /** Enough orders for every redemption of this file: each one redeems exactly one order. */
        const val ORDER_COUNT = 17L

        const val UNIQUE_VIOLATION = "23505"

        const val LOCK_POLL_ATTEMPTS = 100
        const val LOCK_POLL_INTERVAL_MILLIS = 50L
    }
}
