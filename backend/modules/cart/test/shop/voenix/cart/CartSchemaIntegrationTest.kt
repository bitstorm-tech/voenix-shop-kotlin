package shop.voenix.cart

import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Every cart invariant PostgreSQL is responsible for, each violated by a statement that can only
 * trip the one rule under test.
 *
 * These are not "extra" checks next to the service tests: the service *relies* on them. The
 * find-or-create is only safe because a second active cart is impossible, and the merge only has to
 * cap the quantity because the column refuses 100 anyway.
 */
internal class CartSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a guest can have only one active cart, but any number of checked-out ones`() =
        withSchema("active-cart") { dataSource ->
            insertCart(dataSource, id = 1, token = "guest", status = "ACTIVE")

            assertEquals(
                UNIQUE_VIOLATION,
                failure { insertCart(dataSource, id = 2, token = "guest", status = "ACTIVE") },
            )

            insertCart(dataSource, id = 3, token = "guest", status = "CHECKED_OUT")
            insertCart(dataSource, id = 4, token = "guest", status = "CHECKED_OUT")
            assertEquals(
                3,
                CartTestSupport.count(dataSource, "SELECT count(*) FROM voenix.carts"),
            )
        }

    @Test
    fun `a cart status outside the two known values is refused`() =
        withSchema("status") { dataSource ->
            assertEquals(
                CHECK_VIOLATION,
                failure { insertCart(dataSource, id = 1, token = "guest", status = "active") },
            )
        }

    @Test
    fun `a line quantity outside one to ninety-nine is refused`() =
        withSchema("quantity") { dataSource ->
            insertCart(dataSource, id = 1, token = "guest", status = "ACTIVE")

            assertEquals(CHECK_VIOLATION, failure { insertLine(dataSource, quantity = 0) })
            assertEquals(CHECK_VIOLATION, failure { insertLine(dataSource, quantity = 100) })
            insertLine(dataSource, quantity = 99)
        }

    @Test
    fun `a variant of another article cannot be put on a line`() =
        withSchema("variant") { dataSource ->
            insertCart(dataSource, id = 1, token = "guest", status = "ACTIVE")

            assertEquals(
                FOREIGN_KEY_VIOLATION,
                failure {
                    insertLine(
                        dataSource,
                        articleId = CartTestSupport.ARTICLE_ID,
                        variantId = CartTestSupport.OTHER_VARIANT_ID,
                    )
                },
            )
        }

    @Test
    fun `two lines of one cart cannot share a position`() =
        withSchema("position") { dataSource ->
            insertCart(dataSource, id = 1, token = "guest", status = "ACTIVE")
            insertLine(dataSource, position = 1)

            assertEquals(UNIQUE_VIOLATION, failure { insertLine(dataSource, position = 1) })
            assertEquals(CHECK_VIOLATION, failure { insertLine(dataSource, position = 0) })
            insertLine(dataSource, position = 2)
        }

    @Test
    fun `a print image without any owner is refused`() =
        withSchema("image-owner") { dataSource ->
            assertEquals(
                CHECK_VIOLATION,
                failure { insertPrintImage(dataSource, id = 1, token = "NULL", userId = "NULL") },
            )
            insertPrintImage(dataSource, id = 2, token = "'guest'", userId = "NULL")
            insertPrintImage(
                dataSource,
                id = 3,
                token = "NULL",
                userId = CartTestSupport.USER_ID.toString(),
            )
        }

    @Test
    fun `a print image a cart line still points at cannot be deleted`() =
        withSchema("image-restrict") { dataSource ->
            insertCart(dataSource, id = 1, token = "guest", status = "ACTIVE")
            insertPrintImage(dataSource, id = 1, token = "'guest'", userId = "NULL")
            insertLine(dataSource, printImageId = "1")

            assertEquals(
                FOREIGN_KEY_VIOLATION,
                failure {
                    CartTestSupport.execute(
                        dataSource,
                        "DELETE FROM voenix.print_images WHERE id = 1",
                    )
                },
            )
        }

    @Test
    fun `deleting a promotion or a user empties the reference instead of the cart`() =
        withSchema("set-null") { dataSource ->
            CartTestSupport.seedPromotion(dataSource, id = 3, code = "SAVE10")
            CartTestSupport.execute(
                dataSource,
                "INSERT INTO voenix.carts " +
                    "(id, guest_session_token, user_id, status, promotion_id) " +
                    "VALUES (1, 'guest', ${CartTestSupport.USER_ID}, 'ACTIVE', 3)",
                "INSERT INTO voenix.print_images (id, filename, guest_session_token, user_id) " +
                    "VALUES (1, 'a.webp', 'guest', ${CartTestSupport.USER_ID})",
                "DELETE FROM voenix.promotions WHERE id = 3",
                "DELETE FROM voenix.users WHERE id = ${CartTestSupport.USER_ID}",
            )

            assertEquals(1, CartTestSupport.count(dataSource, "SELECT count(*) FROM voenix.carts"))
            assertNull(
                CartTestSupport.singleLong(
                    dataSource,
                    "SELECT promotion_id FROM voenix.carts WHERE id = 1",
                )
            )
            assertNull(
                CartTestSupport.singleLong(
                    dataSource,
                    "SELECT user_id FROM voenix.carts WHERE id = 1",
                )
            )
            assertNull(
                CartTestSupport.singleLong(
                    dataSource,
                    "SELECT user_id FROM voenix.print_images WHERE id = 1",
                )
            )
        }

    @Test
    fun `deleting a cart takes its lines with it`() =
        withSchema("cascade") { dataSource ->
            insertCart(dataSource, id = 1, token = "guest", status = "ACTIVE")
            insertLine(dataSource)

            CartTestSupport.execute(dataSource, "DELETE FROM voenix.carts WHERE id = 1")

            assertEquals(
                0,
                CartTestSupport.count(dataSource, "SELECT count(*) FROM voenix.cart_items"),
            )
        }

    private fun withSchema(
        name: String,
        test: (HikariDataSource) -> Unit,
    ) {
        migratedDataSource("cart-schema-$name").use { dataSource ->
            CartTestSupport.seed(dataSource)
            test(dataSource)
        }
    }

    private fun insertCart(
        dataSource: HikariDataSource,
        id: Long,
        token: String,
        status: String,
    ) {
        CartTestSupport.execute(
            dataSource,
            "INSERT INTO voenix.carts (id, guest_session_token, status) " +
                "VALUES ($id, '$token', '$status')",
        )
    }

    private fun insertLine(
        dataSource: HikariDataSource,
        articleId: Long = CartTestSupport.ARTICLE_ID,
        variantId: Long = CartTestSupport.VARIANT_ID,
        quantity: Int = 1,
        position: Int = nextPosition++,
        printImageId: String = "NULL",
    ) {
        CartTestSupport.execute(
            dataSource,
            "INSERT INTO voenix.cart_items " +
                "(cart_id, article_id, variant_id, quantity, price_cents, position, " +
                "print_image_id) " +
                "VALUES (1, $articleId, $variantId, $quantity, 1490, $position, $printImageId)",
        )
    }

    private fun insertPrintImage(
        dataSource: HikariDataSource,
        id: Long,
        token: String,
        userId: String,
    ) {
        CartTestSupport.execute(
            dataSource,
            "INSERT INTO voenix.print_images (id, filename, guest_session_token, user_id) " +
                "VALUES ($id, 'image-$id.webp', $token, $userId)",
        )
    }

    private fun failure(statement: () -> Unit): String? =
        assertFailsWith<SQLException> { statement() }.sqlState

    private var nextPosition = 1

    private companion object {
        const val CHECK_VIOLATION = "23514"
        const val UNIQUE_VIOLATION = "23505"
        const val FOREIGN_KEY_VIOLATION = "23503"
    }
}
