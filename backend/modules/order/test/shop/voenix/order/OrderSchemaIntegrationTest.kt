package shop.voenix.order

import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Every order invariant PostgreSQL is responsible for, each violated by a statement that can only
 * trip the one rule under test.
 *
 * These are not decoration next to the service tests: the service *relies* on them. Placing an
 * order twice is prevented by the partial unique index and nothing else, the money in an order adds
 * up because the CHECK refuses anything else, and a snapshot survives the deletion of the article
 * it names only because no foreign key points at the catalog.
 */
internal class OrderSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a status outside the three known values is refused`() =
        withSchema("status") { dataSource ->
            // One cart per accepted status, so the partial unique index cannot interfere.
            OrderStatus.entries.forEachIndexed { index, status ->
                insertOrder(dataSource, id = index + 1L, cartId = index + 1L, status = status.name)
            }

            // SHIPPED existed in the legacy status set and was never written; it is gone. Cart 3
            // carries only the cancelled order, so nothing but the CHECK can refuse these.
            assertEquals(
                CHECK_VIOLATION,
                failure { insertOrder(dataSource, id = 9, cartId = 3, status = "SHIPPED") },
            )
            assertEquals(
                CHECK_VIOLATION,
                failure { insertOrder(dataSource, id = 10, cartId = 3, status = "pending") },
            )
        }

    @Test
    fun `an order that belongs to neither a guest nor a user is refused`() =
        withSchema("owner") { dataSource ->
            assertEquals(
                CHECK_VIOLATION,
                failure { insertOrder(dataSource, id = 1, cartId = 1, token = "NULL") },
            )

            insertOrder(dataSource, id = 2, cartId = 1, token = "NULL", userId = USER_ID.toString())
            insertOrder(dataSource, id = 3, cartId = 2, token = "'guest'")
        }

    @Test
    fun `the four amounts must be non-negative and add up`() =
        withSchema("amounts") { dataSource ->
            assertEquals(CHECK_VIOLATION, failure { insertAmounts(dataSource, subtotal = -1) })
            assertEquals(CHECK_VIOLATION, failure { insertAmounts(dataSource, shipping = -1) })
            assertEquals(CHECK_VIOLATION, failure { insertAmounts(dataSource, discount = -1) })

            // A discount larger than everything it discounts. The three other amounts are
            // non-negative and the sum is consistent, so only the total's own CHECK can refuse it.
            assertEquals(
                CHECK_VIOLATION,
                failure {
                    insertAmounts(
                        dataSource,
                        subtotal = 0,
                        shipping = 0,
                        discount = 5,
                        total = -5,
                    )
                },
            )

            // Consistent on its own terms, but not the sum of the other three.
            assertEquals(
                CHECK_VIOLATION,
                failure {
                    insertAmounts(
                        dataSource,
                        subtotal = 1_000,
                        shipping = 490,
                        discount = 0,
                        total = 1_000,
                    )
                },
            )

            // A hundred-percent coupon: everything but the shipping is discounted away.
            insertAmounts(
                dataSource,
                subtotal = 1_000,
                shipping = 490,
                discount = 1_000,
                total = 490,
            )
        }

    @Test
    fun `a cart can carry one live order, but any number of cancelled ones`() =
        withSchema("live-cart") { dataSource ->
            insertOrder(dataSource, id = 1, cartId = 1, status = "PENDING")

            assertEquals(
                UNIQUE_VIOLATION,
                failure { insertOrder(dataSource, id = 2, cartId = 1, status = "PENDING") },
            )
            assertEquals(
                UNIQUE_VIOLATION,
                failure { insertOrder(dataSource, id = 3, cartId = 1, status = "PAID") },
            )

            // A cancelled placement leaves the cart free to be checked out again.
            execute(dataSource, "UPDATE voenix.orders SET status = 'CANCELLED' WHERE id = 1")
            insertOrder(dataSource, id = 4, cartId = 1, status = "CANCELLED")
            insertOrder(dataSource, id = 5, cartId = 1, status = "PENDING")

            assertEquals(3, count(dataSource, "SELECT count(*) FROM voenix.orders"))
        }

    /**
     * The access token is what a confirmation mail links to, so the database has to guarantee two
     * things about it: every order has one, and no two orders share one. The repository's collision
     * retry depends on the index *existing*, not on its name — a placement whose generated token
     * collides is repaired by the 23505 this index raises, and any 23505 is handled generically.
     * The name is pinned here only so a rewrite of `V16` cannot silently drop the index.
     */
    @Test
    fun `every order carries an access token, and no two share one`() =
        withSchema("access-token") { dataSource ->
            assertEquals(
                NOT_NULL_VIOLATION,
                failure { insertOrder(dataSource, id = 1, cartId = 1, accessToken = "NULL") },
            )

            insertOrder(dataSource, id = 2, cartId = 1, accessToken = "'shared-token'")
            assertEquals(
                UNIQUE_VIOLATION,
                failure {
                    insertOrder(dataSource, id = 3, cartId = 2, accessToken = "'shared-token'")
                },
            )

            // Cancelled or not: the token is unique over *all* orders, unlike the live-cart index.
            execute(dataSource, "UPDATE voenix.orders SET status = 'CANCELLED' WHERE id = 2")
            assertEquals(
                UNIQUE_VIOLATION,
                failure {
                    insertOrder(dataSource, id = 4, cartId = 1, accessToken = "'shared-token'")
                },
            )

            assertEquals(
                1,
                count(
                    dataSource,
                    "SELECT count(*) FROM pg_indexes WHERE schemaname = 'voenix' " +
                        "AND indexname = 'ux_orders_access_token'",
                ),
                "The collision retry depends on this index existing, not on its name; the name " +
                    "is pinned so a rewrite of V16 cannot silently drop the index",
            )
        }

    @Test
    fun `the cart and the promotion of an order cannot be deleted`() =
        withSchema("restrict") { dataSource ->
            insertOrder(dataSource, id = 1, cartId = 1, promotionId = PROMOTION_ID.toString())

            assertEquals(
                FOREIGN_KEY_VIOLATION,
                failure { execute(dataSource, "DELETE FROM voenix.carts WHERE id = 1") },
            )
            assertEquals(
                FOREIGN_KEY_VIOLATION,
                failure {
                    execute(dataSource, "DELETE FROM voenix.promotions WHERE id = $PROMOTION_ID")
                },
            )
        }

    @Test
    fun `deleting a user empties the reference instead of the order`() =
        withSchema("user-set-null") { dataSource ->
            insertOrder(dataSource, id = 1, cartId = 1, userId = USER_ID.toString())

            execute(dataSource, "DELETE FROM voenix.users WHERE id = $USER_ID")

            assertEquals(1, count(dataSource, "SELECT count(*) FROM voenix.orders"))
            assertNull(singleLong(dataSource, "SELECT user_id FROM voenix.orders WHERE id = 1"))
        }

    @Test
    fun `a line quantity outside one to ninety-nine is refused`() =
        withSchema("quantity") { dataSource ->
            insertOrder(dataSource, id = 1, cartId = 1)

            assertEquals(CHECK_VIOLATION, failure { insertLine(dataSource, quantity = 0) })
            assertEquals(CHECK_VIOLATION, failure { insertLine(dataSource, quantity = 100) })
            insertLine(dataSource, quantity = 99)
        }

    @Test
    fun `two lines of one order cannot share a position`() =
        withSchema("position") { dataSource ->
            insertOrder(dataSource, id = 1, cartId = 1)
            insertLine(dataSource, position = 1)

            assertEquals(UNIQUE_VIOLATION, failure { insertLine(dataSource, position = 1) })
            assertEquals(CHECK_VIOLATION, failure { insertLine(dataSource, position = 0) })
            insertLine(dataSource, position = 2)
        }

    @Test
    fun `a line price and a prompt price below zero are refused`() =
        withSchema("line-price") { dataSource ->
            insertOrder(dataSource, id = 1, cartId = 1)

            // The two prices are charged together but checked separately, so each is violated on
            // its own while the other stays valid.
            assertEquals(CHECK_VIOLATION, failure { insertLine(dataSource, priceCents = -1) })
            assertEquals(CHECK_VIOLATION, failure { insertLine(dataSource, promptPriceCents = -1) })
            insertLine(dataSource, priceCents = 0, promptPriceCents = 0)
        }

    @Test
    fun `a measurement is either unknown or a real length`() =
        withSchema("measurements") { dataSource ->
            insertOrder(dataSource, id = 1, cartId = 1)

            assertEquals(CHECK_VIOLATION, failure { insertLine(dataSource, printWidthMm = "0") })
            assertEquals(CHECK_VIOLATION, failure { insertLine(dataSource, printWidthMm = "-5") })
            insertLine(dataSource, printWidthMm = "NULL")
            insertLine(dataSource, printWidthMm = "239")
        }

    @Test
    fun `deleting an order takes its lines with it`() =
        withSchema("cascade") { dataSource ->
            insertOrder(dataSource, id = 1, cartId = 1)
            insertLine(dataSource)

            execute(dataSource, "DELETE FROM voenix.orders WHERE id = 1")

            assertEquals(0, count(dataSource, "SELECT count(*) FROM voenix.order_items"))
        }

    @Test
    fun `an ordered print image cannot be deleted, its prompt can`() =
        withSchema("line-references") { dataSource ->
            insertOrder(dataSource, id = 1, cartId = 1)
            insertLine(
                dataSource,
                promptId = PROMPT_ID.toString(),
                printImageId = "$PRINT_IMAGE_ID",
            )

            assertEquals(
                FOREIGN_KEY_VIOLATION,
                failure {
                    execute(
                        dataSource,
                        "DELETE FROM voenix.print_images WHERE id = $PRINT_IMAGE_ID",
                    )
                },
            )

            execute(dataSource, "DELETE FROM voenix.prompts WHERE id = $PROMPT_ID")
            assertEquals(1, count(dataSource, "SELECT count(*) FROM voenix.order_items"))
            assertNull(singleLong(dataSource, "SELECT prompt_id FROM voenix.order_items"))
        }

    @Test
    fun `deleting the article a line names leaves the snapshot untouched`() =
        withSchema("catalog-independence") { dataSource ->
            insertOrder(dataSource, id = 1, cartId = 1)
            insertLine(dataSource)

            execute(dataSource, "DELETE FROM voenix.article_identities WHERE id = $ARTICLE_ID")

            assertEquals(
                ARTICLE_ID,
                singleLong(dataSource, "SELECT article_id FROM voenix.order_items"),
            )
        }

    @Test
    fun `a redemption exists only for one order, and keeps that order alive`() =
        withSchema("redemption") { dataSource ->
            insertOrder(dataSource, id = 1, cartId = 1)

            assertEquals(
                NOT_NULL_VIOLATION,
                failure { insertRedemption(dataSource, orderId = "NULL") },
            )

            insertRedemption(dataSource, orderId = "1")
            assertEquals(UNIQUE_VIOLATION, failure { insertRedemption(dataSource, orderId = "1") })
            assertEquals(
                FOREIGN_KEY_VIOLATION,
                failure { execute(dataSource, "DELETE FROM voenix.orders WHERE id = 1") },
            )
        }

    @Test
    fun `a production request points at an order that really exists`() =
        withSchema("production-request") { dataSource ->
            insertOrder(dataSource, id = 1, cartId = 1)

            assertEquals(FOREIGN_KEY_VIOLATION, failure { insertProductionRequest(dataSource, 99) })

            insertProductionRequest(dataSource, orderId = 1)
            assertEquals(
                FOREIGN_KEY_VIOLATION,
                failure { execute(dataSource, "DELETE FROM voenix.orders WHERE id = 1") },
            )
        }

    private fun withSchema(
        name: String,
        test: (HikariDataSource) -> Unit,
    ) {
        migratedDataSource("order-schema-$name").use { dataSource ->
            seed(dataSource)
            nextPosition = 1
            nextAccessToken = 1
            test(dataSource)
        }
    }

    /**
     * Empties every table an order test writes and re-seeds the master data the order's foreign
     * keys need: three carts to place orders from, one user, one promotion, one prompt, one print
     * image, and the article variant a line names (which it deliberately does not reference).
     */
    private fun seed(dataSource: DataSource) {
        execute(
            dataSource,
            "TRUNCATE voenix.order_items, voenix.orders, voenix.promotion_redemptions, " +
                "voenix.production_requests, voenix.cart_items, voenix.carts, " +
                "voenix.print_images, voenix.prompts, voenix.prompt_categories, " +
                "voenix.promotions, voenix.users, voenix.article_identities, " +
                "voenix.article_variant_identities RESTART IDENTITY CASCADE",
            "INSERT INTO voenix.users (id, email, password_hash) " +
                "VALUES ($USER_ID, 'customer@example.com', 'hash')",
            "INSERT INTO voenix.carts (id, guest_session_token, status) " +
                "VALUES (1, 'guest-1', 'CHECKED_OUT'), (2, 'guest-2', 'CHECKED_OUT'), " +
                "(3, 'guest-3', 'CHECKED_OUT')",
            "INSERT INTO voenix.promotions (id, name, discount_type, discount_value, " +
                "coupon_code, coupon_code_normalized, is_active) " +
                "VALUES ($PROMOTION_ID, 'Summer', 'PERCENTAGE', 10, 'SAVE10', 'SAVE10', TRUE)",
            "INSERT INTO voenix.prompt_categories (id, name, position) VALUES (1, 'Fun', 1)",
            "INSERT INTO voenix.prompts " +
                "(id, position, title, prompt_text, category_id, active, archived) " +
                "VALUES ($PROMPT_ID, 1, 'Watercolor', 'as a watercolor', 1, TRUE, FALSE)",
            "INSERT INTO voenix.print_images (id, filename, guest_session_token) " +
                "VALUES ($PRINT_IMAGE_ID, 'print.webp', 'guest-1')",
            "INSERT INTO voenix.article_identities (id, article_type) VALUES ($ARTICLE_ID, 'MUG')",
            "INSERT INTO voenix.article_variant_identities (id, article_id, article_type) " +
                "VALUES ($VARIANT_ID, $ARTICLE_ID, 'MUG')",
        )
    }

    private fun insertOrder(
        dataSource: DataSource,
        id: Long,
        cartId: Long,
        status: String = "PENDING",
        token: String = "'guest-1'",
        userId: String = "NULL",
        promotionId: String = "NULL",
        accessToken: String = "'access-token-$id'",
    ) {
        execute(
            dataSource,
            "INSERT INTO voenix.orders " +
                "(id, cart_id, guest_session_token, user_id, promotion_id, access_token, " +
                "status, $ADDRESS_COLUMNS, subtotal_cents, shipping_cost_cents, discount_cents, " +
                "total_cents) " +
                "VALUES ($id, $cartId, $token, $userId, $promotionId, $accessToken, '$status', " +
                "$ADDRESS_VALUES, 1000, 490, 0, 1490)",
        )
    }

    private fun insertAmounts(
        dataSource: DataSource,
        subtotal: Int = 1_000,
        shipping: Int = 490,
        discount: Int = 0,
        total: Int = subtotal + shipping - discount,
    ) {
        execute(
            dataSource,
            "INSERT INTO voenix.orders " +
                "(cart_id, guest_session_token, access_token, status, $ADDRESS_COLUMNS, " +
                "subtotal_cents, shipping_cost_cents, discount_cents, total_cents) " +
                "VALUES (1, 'guest-1', 'amount-token-${nextAccessToken++}', 'CANCELLED', " +
                "$ADDRESS_VALUES, " +
                "$subtotal, $shipping, $discount, $total)",
        )
    }

    private fun insertLine(
        dataSource: DataSource,
        quantity: Int = 1,
        position: Int = nextPosition++,
        priceCents: Int = 1_490,
        promptPriceCents: Int = 0,
        printWidthMm: String = "NULL",
        promptId: String = "NULL",
        printImageId: String = "NULL",
    ) {
        execute(
            dataSource,
            "INSERT INTO voenix.order_items " +
                "(order_id, position, article_id, variant_id, article_name, variant_name, " +
                "print_template_width_mm, quantity, price_cents, prompt_price_cents, " +
                "prompt_id, print_image_id) " +
                "VALUES (1, $position, $ARTICLE_ID, $VARIANT_ID, 'Classic mug', 'White', " +
                "$printWidthMm, $quantity, $priceCents, $promptPriceCents, " +
                "$promptId, $printImageId)",
        )
    }

    private fun insertRedemption(
        dataSource: DataSource,
        orderId: String,
    ) {
        execute(
            dataSource,
            "INSERT INTO voenix.promotion_redemptions " +
                "(promotion_id, user_id, redeemed_at, order_id) " +
                "VALUES ($PROMOTION_ID, $USER_ID, CURRENT_TIMESTAMP, $orderId)",
        )
    }

    private fun insertProductionRequest(
        dataSource: DataSource,
        orderId: Long,
    ) {
        execute(
            dataSource,
            "INSERT INTO voenix.production_requests (order_id) VALUES ($orderId)",
        )
    }

    private fun execute(
        dataSource: DataSource,
        vararg statements: String,
    ) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach(statement::executeUpdate)
            }
        }
    }

    private fun count(
        dataSource: DataSource,
        sql: String,
    ): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
        }

    private fun singleLong(
        dataSource: DataSource,
        sql: String,
    ): Long? =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getLong(1).takeIf { !rows.wasNull() }
                }
            }
        }

    private fun failure(statement: () -> Unit): String? =
        assertFailsWith<SQLException> { statement() }.sqlState

    private var nextPosition = 1
    private var nextAccessToken = 1

    private companion object {
        const val USER_ID = 7L
        const val PROMOTION_ID = 3L
        const val PROMPT_ID = 5L
        const val PRINT_IMAGE_ID = 11L
        const val ARTICLE_ID = 10L
        const val VARIANT_ID = 20L

        const val CHECK_VIOLATION = "23514"
        const val NOT_NULL_VIOLATION = "23502"
        const val UNIQUE_VIOLATION = "23505"
        const val FOREIGN_KEY_VIOLATION = "23503"

        /** The address snapshot every order carries; no test varies it. */
        const val ADDRESS_COLUMNS =
            "shipping_first_name, shipping_last_name, shipping_street, shipping_house_number, " +
                "shipping_postal_code, shipping_city, shipping_country, " +
                "billing_first_name, billing_last_name, billing_street, billing_house_number, " +
                "billing_postal_code, billing_city, billing_country, email"
        const val ADDRESS_VALUES =
            "'Ada', 'Lovelace', 'Hauptstrasse', '1', '10115', 'Berlin', 'DE', " +
                "'Ada', 'Lovelace', 'Hauptstrasse', '1', '10115', 'Berlin', 'DE', " +
                "'customer@example.com'"
    }
}
