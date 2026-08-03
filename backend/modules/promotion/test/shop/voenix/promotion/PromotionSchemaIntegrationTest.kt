package shop.voenix.promotion

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether the Flyway migration builds a schema that actually enforces the promotion invariants.
 *
 * Every constraint is asserted through the behavior it produces — a rejected write and its SQL
 * state — and never through its name, so renaming a constraint stays the free change it should be.
 * Indexes are the one exception: they change no behavior a test can observe, so their names are all
 * there is to assert.
 */
internal class PromotionSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates promotion constraints foreign key and indexes on an empty database`() {
        migratedDataSource("promotion-schema-integration-test").use { dataSource ->
            insertOrders(dataSource, REDEEMED_ORDER_ID)
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(insertPromotionSql(id = 1, code = "Winter10"))
                    statement.execute(insertRedemptionSql(orderId = REDEEMED_ORDER_ID))
                }

                assertRedemptionForeignKeyRestrictsDelete(connection)
                assertOneRedemptionPerOrder(connection)
                assertCaseInsensitiveDuplicateCodeIsRejected(connection)
                assertCheckConstraintsRejectInvalidRows(connection)
                assertIndexes(connection)
            }
        }
    }

    /**
     * The reservation table of `V18`, on the same empty database. Each rule is tripped by a row
     * that can violate only that rule: the promotion, the cart, and the customer of a reservation
     * each answer a deletion differently, and a cart holds at most one reservation.
     */
    @Test
    fun `flyway creates the reservation constraints and indexes on an empty database`() {
        migratedDataSource("promotion-reservation-schema-integration-test").use { dataSource ->
            insertCarts(dataSource, RESERVING_CART, SECOND_RESERVING_CART)
            insertUsers(dataSource, RESERVING_USER)
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM voenix.promotion_reservations")
                    statement.execute(insertPromotionSql(id = RESERVED_PROMOTION, code = "Held10"))
                    statement.execute(
                        insertReservationSql(cartId = RESERVING_CART, userId = RESERVING_USER)
                    )
                }

                assertOneReservationPerCart(connection)
                assertReservationRequiresPromotionAndCart(connection)
                assertReservedPromotionCannotBeDeleted(connection)
                assertDeletingTheCustomerKeepsTheReservation(connection)
                assertDeletingTheCartTakesTheReservationWithIt(connection)
                assertReservationIndexes(connection)
            }
        }
    }

    private fun assertOneReservationPerCart(connection: Connection) {
        val duplicate =
            assertFailsWith<SQLException> {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(insertReservationSql(cartId = RESERVING_CART))
                }
            }
        assertEquals("23505", duplicate.sqlState)
    }

    private fun assertReservationRequiresPromotionAndCart(connection: Connection) {
        listOf(
                insertReservationSql(cartId = SECOND_RESERVING_CART, promotionId = null),
                insertReservationSql(cartId = null),
            )
            .forEach { sql ->
                val missing =
                    assertFailsWith<SQLException> {
                        connection.createStatement().use { statement ->
                            statement.executeUpdate(sql)
                        }
                    }
                assertEquals("23502", missing.sqlState)
            }
    }

    /** A promotion somebody is checking out with right now must not vanish underneath them. */
    private fun assertReservedPromotionCannotBeDeleted(connection: Connection) {
        val blocked =
            assertFailsWith<SQLException> {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "DELETE FROM voenix.promotions WHERE id = $RESERVED_PROMOTION"
                    )
                }
            }
        assertEquals("23503", blocked.sqlState)
    }

    /**
     * The capacity stays held after the account behind it is deleted, exactly like a redemption.
     */
    private fun assertDeletingTheCustomerKeepsTheReservation(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("DELETE FROM voenix.users WHERE id = $RESERVING_USER")
        }
        connection
            .prepareStatement("SELECT user_id FROM voenix.promotion_reservations WHERE cart_id = ?")
            .use { statement ->
                statement.setLong(1, RESERVING_CART)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next(), "The reservation survives its customer")
                    rows.getLong("user_id")
                    assertTrue(rows.wasNull(), "The deleted customer leaves an empty reference")
                }
            }
    }

    private fun assertDeletingTheCartTakesTheReservationWithIt(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("DELETE FROM voenix.carts WHERE id = $RESERVING_CART")
        }
        connection.prepareStatement("SELECT count(*) FROM voenix.promotion_reservations").use {
            statement ->
            statement.executeQuery().use { rows ->
                assertTrue(rows.next(), "count() always returns a row")
                assertEquals(0L, rows.getLong(1))
            }
        }
    }

    private fun assertReservationIndexes(connection: Connection) {
        val names =
            connection
                .prepareStatement(
                    """
                    SELECT indexname
                    FROM pg_indexes
                    WHERE schemaname = 'voenix'
                      AND tablename = 'promotion_reservations'
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        buildSet { while (rows.next()) add(rows.getString("indexname")) }
                    }
                }
        assertTrue("ix_promotion_reservations_promotion_id" in names)
        assertTrue("ix_promotion_reservations_promotion_id_user_id" in names)
    }

    private fun insertReservationSql(
        cartId: Long?,
        userId: Long? = null,
        promotionId: Int? = RESERVED_PROMOTION,
    ): String =
        """
        INSERT INTO voenix.promotion_reservations (promotion_id, cart_id, user_id, created_at)
        VALUES (${promotionId ?: "NULL"}, ${cartId ?: "NULL"}, ${userId ?: "NULL"},
                '2026-02-01T10:00:00Z')
        """
            .trimIndent()

    private fun assertRedemptionForeignKeyRestrictsDelete(connection: Connection) {
        val foreignKeys = buildMap {
            connection.metaData.getImportedKeys(null, "voenix", "promotion_redemptions").use { rows
                ->
                while (rows.next()) {
                    put(
                        rows.getString("FKCOLUMN_NAME"),
                        rows.getString("PKTABLE_NAME") to rows.getInt("DELETE_RULE"),
                    )
                }
            }
        }
        // Since the Order migration a redemption belongs to an order as strictly as it belongs to a
        // promotion, and neither of the two may be deleted out from under it.
        assertEquals(
            mapOf(
                "promotion_id" to ("promotions" to DatabaseMetaData.importedKeyRestrict),
                "order_id" to ("orders" to DatabaseMetaData.importedKeyRestrict),
            ),
            foreignKeys,
        )

        val deleteFailure =
            assertFailsWith<SQLException> {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM voenix.promotions WHERE id = 1")
                }
            }
        assertEquals("23503", deleteFailure.sqlState)
    }

    /** The unique `order_id`: an order pays for a promotion once or not at all. */
    private fun assertOneRedemptionPerOrder(connection: Connection) {
        val duplicate =
            assertFailsWith<SQLException> {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(insertRedemptionSql(orderId = REDEEMED_ORDER_ID))
                }
            }
        assertEquals("23505", duplicate.sqlState)

        val orderless =
            assertFailsWith<SQLException> {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(insertRedemptionSql(orderId = null))
                }
            }
        assertEquals("23502", orderless.sqlState)
    }

    private fun insertRedemptionSql(orderId: Long?): String =
        """
        INSERT INTO voenix.promotion_redemptions
            (promotion_id, user_id, order_id, redeemed_at)
        VALUES (1, NULL, ${orderId ?: "NULL"}, '2026-02-01T10:00:00Z')
        """
            .trimIndent()

    private fun assertCaseInsensitiveDuplicateCodeIsRejected(connection: Connection) {
        val duplicate =
            assertFailsWith<SQLException> {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(insertPromotionSql(id = 2, code = "wINTER10"))
                }
            }
        assertEquals("23505", duplicate.sqlState)
    }

    private fun assertCheckConstraintsRejectInvalidRows(connection: Connection) {
        val invalidOverrides =
            listOf(
                "discount_type" to "'INVALID'",
                "discount_value" to "0",
                "discount_value" to "-1",
                "usage_limit_total" to "0",
                "usage_limit_per_user" to "0",
            )
        invalidOverrides.forEachIndexed { index, (column, value) ->
            val exception =
                assertFailsWith<SQLException> {
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            insertPromotionSql(
                                id = index + 10,
                                code = "Code${index + 10}",
                                column to value,
                            )
                        )
                    }
                }
            assertEquals("23514", exception.sqlState)
        }
    }

    private fun assertIndexes(connection: Connection) {
        val names =
            connection
                .prepareStatement(
                    """
                    SELECT indexname
                    FROM pg_indexes
                    WHERE schemaname = 'voenix'
                      AND tablename IN ('promotions', 'promotion_redemptions')
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        buildSet { while (rows.next()) add(rows.getString("indexname")) }
                    }
                }
        assertTrue("ix_promotions_name" in names)
        assertTrue("ix_promotion_redemptions_promotion_id" in names)
        assertTrue("ix_promotion_redemptions_promotion_id_user_id" in names)
        assertTrue("ux_promotion_redemptions_order" in names)
    }

    private fun insertPromotionSql(
        id: Int,
        code: String,
        override: Pair<String, String>? = null,
    ): String {
        val values =
            mutableMapOf(
                "name" to "'Promotion $id'",
                "discount_type" to "'PERCENTAGE'",
                "discount_value" to "10.00",
                "coupon_code" to "'$code'",
                "coupon_code_normalized" to "upper('$code')",
                "starts_at" to "NULL",
                "ends_at" to "NULL",
                "usage_limit_total" to "NULL",
                "usage_limit_per_user" to "NULL",
                "is_active" to "TRUE",
            )
        if (override != null) {
            check(values.replace(override.first, override.second) != null) {
                "Unknown promotion column: ${override.first}"
            }
        }

        return """
        INSERT INTO voenix.promotions (
            id, ${values.keys.joinToString(", ")}
        ) VALUES (
            $id, ${values.values.joinToString(", ")}
        )
        """
            .trimIndent()
    }

    private companion object {
        /** The order every redemption of this test belongs to; seeded before the redemption. */
        const val REDEEMED_ORDER_ID = 1L

        /** The reservation fixture: one promotion, two carts, and the customer holding it. */
        const val RESERVED_PROMOTION = 100
        const val RESERVING_CART = 101L
        const val SECOND_RESERVING_CART = 102L
        const val RESERVING_USER = 42L
    }
}
