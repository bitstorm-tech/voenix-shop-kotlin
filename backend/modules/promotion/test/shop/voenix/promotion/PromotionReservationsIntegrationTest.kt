package shop.voenix.promotion

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The reservation half of [PromotionCodes] against a real PostgreSQL database: what `reserve`
 * holds, what `release` gives back, what `redeem` consumes, and which of the three checks the
 * activity window.
 *
 * The reservation exists so that capacity a checkout is about to use cannot be used twice. Every
 * test here therefore asserts a *count*: how many units a promotion still has for whom, and whose
 * cart is holding them. The database is real because that is where the answer is decided — the
 * promotion row lock is what turns two simultaneous checkouts into one winner.
 */
internal class PromotionReservationsIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `reserve holds capacity for its cart and never counts the same cart twice`() {
        withCodes("promotion-reserve-idempotent-test") { codes, _, dataSource ->
            assertIs<PromotionCodeResult.Applicable>(codes.reserve(LIMITED, cartId = FIRST_CART))
            // The same cart reserving again is a repeated checkout, not a second unit.
            assertIs<PromotionCodeResult.Applicable>(codes.reserve(LIMITED, cartId = FIRST_CART))

            assertEquals(listOf(FIRST_CART), reservedCartsOf(dataSource, LIMITED))
            assertEquals(
                PromotionCodeResult.TotalExhausted,
                codes.reserve(LIMITED, cartId = SECOND_CART),
                "The single unit is held by another cart",
            )
        }
    }

    @Test
    fun `reserve refuses a promotion that is unknown, switched off, or outside its window`() {
        withCodes("promotion-reserve-availability-test") { codes, _, dataSource ->
            assertEquals(PromotionCodeResult.InvalidCode, codes.reserve(404, cartId = FIRST_CART))
            assertEquals(PromotionCodeResult.Inactive, codes.reserve(INACTIVE, cartId = FIRST_CART))
            assertEquals(PromotionCodeResult.Expired, codes.reserve(EXPIRED, cartId = FIRST_CART))
            assertEquals(
                PromotionCodeResult.LoginRequired,
                codes.reserve(PER_USER, cartId = FIRST_CART),
                "A guest facing a per-user limit is told to log in before anything is counted",
            )

            assertEquals(emptyList(), reservedCartsOf(dataSource, INACTIVE))
            assertEquals(emptyList(), reservedCartsOf(dataSource, EXPIRED))
            assertEquals(emptyList(), reservedCartsOf(dataSource, PER_USER))
        }
    }

    @Test
    fun `two concurrent reserves of different carts against a total limit of one hold it once`() {
        withCodes("promotion-reserve-race-test") { codes, _, dataSource ->
            val results = coroutineScope {
                listOf(
                        async(Dispatchers.IO) { codes.reserve(RACED, cartId = FIRST_CART) },
                        async(Dispatchers.IO) { codes.reserve(RACED, cartId = SECOND_CART) },
                    )
                    .map { deferred -> deferred.await() }
            }

            assertEquals(1, results.count { it is PromotionCodeResult.Applicable })
            assertEquals(1, results.count { it == PromotionCodeResult.TotalExhausted })
            assertEquals(1, reservedCartsOf(dataSource, RACED).size)
        }
    }

    @Test
    fun `the per-user limit counts reservations of the same customer and nobody else`() {
        withCodes("promotion-reserve-per-user-test") { codes, _, dataSource ->
            assertIs<PromotionCodeResult.Applicable>(
                codes.reserve(PER_USER, cartId = FIRST_CART, userId = USER)
            )

            assertEquals(
                PromotionCodeResult.PerUserExhausted,
                codes.reserve(PER_USER, cartId = SECOND_CART, userId = USER),
            )
            assertIs<PromotionCodeResult.Applicable>(
                codes.reserve(PER_USER, cartId = SECOND_CART, userId = OTHER_USER)
            )
            assertEquals(listOf(FIRST_CART, SECOND_CART), reservedCartsOf(dataSource, PER_USER))
        }
    }

    @Test
    fun `redeem consumes the reservation of its own cart in the caller transaction`() {
        withCodes("promotion-redeem-consumes-test") { codes, database, dataSource ->
            assertIs<PromotionCodeResult.Applicable>(
                codes.reserve(UNLIMITED, cartId = ORDER_CART, userId = USER)
            )

            assertIs<PromotionCodeResult.Applicable>(
                redeem(codes, database, UNLIMITED, orderId = ORDER, cartId = ORDER_CART, USER)
            )

            assertEquals(listOf(ORDER), redeemedOrdersOf(dataSource, UNLIMITED))
            assertEquals(
                emptyList(),
                reservedCartsOf(dataSource, UNLIMITED),
                "The reservation became the redemption instead of outliving it",
            )
        }
    }

    @Test
    fun `a rolled back redemption leaves the reservation in place`() {
        withCodes("promotion-redeem-rollback-test") { codes, database, dataSource ->
            assertIs<PromotionCodeResult.Applicable>(
                codes.reserve(UNLIMITED, cartId = ORDER_CART, userId = USER)
            )

            assertFailsWith<Rollback> {
                withContext(Dispatchers.IO) {
                    suspendTransaction(db = database) {
                        maxAttempts = 1
                        codes.redeem(UNLIMITED, orderId = ORDER, cartId = ORDER_CART, userId = USER)
                        throw Rollback()
                    }
                }
            }

            assertEquals(emptyList(), redeemedOrdersOf(dataSource, UNLIMITED))
            assertEquals(listOf(ORDER_CART), reservedCartsOf(dataSource, UNLIMITED))
        }
    }

    @Test
    fun `redeem counts the reservations of other carts but never re-checks the window`() {
        withCodes("promotion-redeem-window-and-limits-test") { codes, database, dataSource ->
            assertIs<PromotionCodeResult.Applicable>(codes.reserve(LIMITED, cartId = FIRST_CART))
            assertEquals(
                PromotionCodeResult.TotalExhausted,
                redeem(codes, database, LIMITED, orderId = ORDER, cartId = ORDER_CART),
                "Another cart holds the only unit",
            )

            // The window is checked when the code is entered and when the checkout starts, never
            // when the payment arrives: a promotion that expires while the customer pays is still
            // redeemed.
            assertIs<PromotionCodeResult.Applicable>(
                redeem(codes, database, EXPIRED, orderId = ORDER, cartId = ORDER_CART)
            )
            assertEquals(listOf(ORDER), redeemedOrdersOf(dataSource, EXPIRED))
        }
    }

    @Test
    fun `release frees the capacity, is idempotent, and refuses to run outside a transaction`() {
        withCodes("promotion-release-test") { codes, database, dataSource ->
            assertIs<PromotionCodeResult.Applicable>(codes.reserve(LIMITED, cartId = FIRST_CART))

            assertFailsWith<IllegalStateException> { codes.release(FIRST_CART) }
            assertEquals(listOf(FIRST_CART), reservedCartsOf(dataSource, LIMITED))

            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    codes.release(FIRST_CART)
                    // Releasing a cart that holds nothing is a normal outcome, not a failure.
                    codes.release(FIRST_CART)
                    codes.release(SECOND_CART)
                }
            }

            assertEquals(emptyList(), reservedCartsOf(dataSource, LIMITED))
            assertIs<PromotionCodeResult.Applicable>(
                codes.reserve(LIMITED, cartId = SECOND_CART),
                "The released unit is available to another cart",
            )
        }
    }

    /**
     * The release for the callers that own no transaction: a checkout whose placement refused, and
     * a cart whose coupon was removed. It commits on its own, which is exactly why no caller here
     * has to open anything.
     */
    @Test
    fun `releaseAbandoned frees the capacity outside any transaction and is idempotent`() {
        withCodes("promotion-release-abandoned-test") { codes, _, dataSource ->
            assertIs<PromotionCodeResult.Applicable>(codes.reserve(LIMITED, cartId = FIRST_CART))

            codes.releaseAbandoned(FIRST_CART)
            // Giving back what is no longer held, and what was never held, are both normal.
            codes.releaseAbandoned(FIRST_CART)
            codes.releaseAbandoned(SECOND_CART)

            assertEquals(emptyList(), reservedCartsOf(dataSource, LIMITED))
            assertIs<PromotionCodeResult.Applicable>(
                codes.reserve(LIMITED, cartId = SECOND_CART),
                "The released unit is available to another cart",
            )
        }
    }

    @Test
    fun `validate counts the reservations of other carts and excludes the caller own one`() {
        withCodes("promotion-validate-reservations-test") { codes, _, _ ->
            assertIs<PromotionCodeResult.Applicable>(codes.reserve(LIMITED, cartId = FIRST_CART))

            assertEquals(PromotionCodeResult.TotalExhausted, codes.validate(LIMITED_CODE))
            assertEquals(
                PromotionCodeResult.TotalExhausted,
                codes.validate(LIMITED_CODE, reservationKey = SECOND_CART),
            )
            assertIs<PromotionCodeResult.Applicable>(
                codes.validate(LIMITED_CODE, reservationKey = FIRST_CART),
                "The cart holding the reservation may re-apply its own code",
            )
        }
    }

    @Test
    fun `validate counts a per-user reservation for its own customer only`() {
        withCodes("promotion-validate-per-user-reservation-test") { codes, _, _ ->
            assertIs<PromotionCodeResult.Applicable>(
                codes.reserve(PER_USER, cartId = FIRST_CART, userId = USER)
            )

            assertEquals(
                PromotionCodeResult.PerUserExhausted,
                codes.validate(PER_USER_CODE, userId = USER),
            )
            assertIs<PromotionCodeResult.Applicable>(
                codes.validate(PER_USER_CODE, userId = USER, reservationKey = FIRST_CART)
            )
            assertIs<PromotionCodeResult.Applicable>(
                codes.validate(PER_USER_CODE, userId = OTHER_USER),
                "Another customer still has their own allowance",
            )
        }
    }

    /** Runs [block] against the capability over a freshly seeded database. */
    private fun withCodes(
        poolName: String,
        block: suspend (PromotionCodes, Database, DataSource) -> Unit,
    ) {
        migratedDataSource(poolName).use { dataSource ->
            seedReservationFixture(dataSource)
            val database = Database.connect(datasource = dataSource)
            val codes = PromotionService(PromotionRepository(database), fixedClock())
            runBlocking { block(codes, database, dataSource) }
        }
    }

    /** Redeems the way the paid-order workflow does: inside a transaction the caller owns. */
    private suspend fun redeem(
        codes: PromotionCodes,
        database: Database,
        promotionId: Long,
        orderId: Long,
        cartId: Long,
        userId: Long? = null,
    ): PromotionCodeResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                codes.redeem(promotionId, orderId, cartId, userId)
            }
        }

    /** The carts holding a reservation of [promotionId], oldest first. */
    private fun reservedCartsOf(
        dataSource: DataSource,
        promotionId: Long,
    ): List<Long> = longsOf(dataSource, "cart_id", "promotion_reservations", promotionId)

    /** The orders that recorded a redemption of [promotionId], oldest first. */
    private fun redeemedOrdersOf(
        dataSource: DataSource,
        promotionId: Long,
    ): List<Long> = longsOf(dataSource, "order_id", "promotion_redemptions", promotionId)

    private fun longsOf(
        dataSource: DataSource,
        column: String,
        table: String,
        promotionId: Long,
    ): List<Long> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT $column FROM voenix.$table WHERE promotion_id = ? ORDER BY id"
                )
                .use { statement ->
                    statement.setLong(1, promotionId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(rows.getLong(column))
                            }
                        }
                    }
                }
        }

    private fun fixedClock(): Clock = Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC)

    /**
     * The promotions, carts, users, and the one order this file reserves against. Every promotion
     * carries exactly one rule that can fail, so a rejected reservation names the rule under test
     * and nothing else.
     */
    private fun seedReservationFixture(dataSource: DataSource) {
        insertOrders(dataSource, ORDER)
        insertCarts(dataSource, FIRST_CART, SECOND_CART)
        insertUsers(dataSource, USER, OTHER_USER)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    DELETE FROM voenix.promotion_reservations;
                    DELETE FROM voenix.promotion_redemptions;
                    DELETE FROM voenix.promotions;
                    INSERT INTO voenix.promotions (
                        id, name, discount_type, discount_value, coupon_code,
                        coupon_code_normalized, starts_at, ends_at,
                        usage_limit_total, usage_limit_per_user, is_active
                    ) VALUES
                        ($UNLIMITED, 'Unlimited sale', 'PERCENTAGE', 10.00, 'Free10', 'FREE10',
                         NULL, NULL, NULL, NULL, TRUE),
                        ($LIMITED, 'Last unit sale', 'PERCENTAGE', 20.00,
                         '$LIMITED_CODE', '${LIMITED_CODE.uppercase()}',
                         NULL, NULL, 1, NULL, TRUE),
                        ($PER_USER, 'Personal sale', 'PERCENTAGE', 25.00,
                         '$PER_USER_CODE', '${PER_USER_CODE.uppercase()}',
                         NULL, NULL, NULL, 1, TRUE),
                        ($EXPIRED, 'Past sale', 'PERCENTAGE', 5.00, 'Past5', 'PAST5',
                         NULL, '2026-01-15T00:00:00Z', NULL, NULL, TRUE),
                        ($INACTIVE, 'Switched off sale', 'PERCENTAGE', 5.00, 'Off5', 'OFF5',
                         NULL, NULL, NULL, NULL, FALSE),
                        ($RACED, 'Race sale', 'PERCENTAGE', 50.00, 'Race1', 'RACE1',
                         NULL, NULL, 1, NULL, TRUE);
                    """
                        .trimIndent()
                )
            }
        }
    }

    /** The failure of a caller transaction that had already redeemed a promotion. */
    private class Rollback : RuntimeException()

    private companion object {
        /** Inside the window of every seeded promotion except the expired one. */
        const val NOW = "2026-02-01T12:00:00Z"

        const val UNLIMITED = 20L
        const val LIMITED = 21L
        const val PER_USER = 22L
        const val EXPIRED = 23L
        const val INACTIVE = 24L
        const val RACED = 25L

        const val LIMITED_CODE = "Last1"
        const val PER_USER_CODE = "Personal1"

        /** The two carts that compete for capacity, and the cart of the seeded order. */
        const val FIRST_CART = 101L
        const val SECOND_CART = 102L
        const val ORDER = 1L
        const val ORDER_CART = ORDER

        const val USER = 42L
        const val OTHER_USER = 43L
    }
}
