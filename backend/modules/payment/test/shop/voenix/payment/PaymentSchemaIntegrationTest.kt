package shop.voenix.payment

import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import shop.voenix.payment.PaymentTestSupport.count
import shop.voenix.payment.PaymentTestSupport.execute
import shop.voenix.payment.PaymentTestSupport.seed
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Every payment invariant PostgreSQL is responsible for, each violated by a statement that can only
 * trip the one rule under test.
 *
 * These are not decoration next to the service tests: the service *relies* on them. That a
 * double-clicked checkout cannot charge twice is `ux_payments_live_order` and nothing else; that a
 * webhook cannot invent a status is the CHECK; and that an order somebody may have paid for cannot
 * be deleted is `ON DELETE RESTRICT`.
 */
internal class PaymentSchemaIntegrationTest : PostgresIntegrationTest() {
    /**
     * All seven Mollie words are accepted, each on an order of its own so the live index cannot be
     * what refuses one of them. The order module's `CANCELLED` — two Ls — is not one of them, which
     * is the spelling trap this CHECK exists to catch.
     */
    @Test
    fun `only the seven mollie status words are accepted`() =
        withSchema("status") { dataSource ->
            PaymentStatus.entries.forEachIndexed { index, status ->
                insertPayment(
                    dataSource,
                    orderId = index + 1L,
                    molliePaymentId = "tr_${status.name.lowercase()}",
                    status = status.name,
                )
            }

            assertEquals(
                CHECK_VIOLATION,
                failure { insertPayment(dataSource, 8, "tr_order_spelling", status = "CANCELLED") },
                "the order module's CANCELLED is not Mollie's CANCELED",
            )
            assertEquals(
                CHECK_VIOLATION,
                failure { insertPayment(dataSource, 8, "tr_lowercase", status = "paid") },
            )
            assertEquals(
                CHECK_VIOLATION,
                failure { insertPayment(dataSource, 8, "tr_invented", status = "REFUNDED") },
            )
        }

    @Test
    fun `a payment is for a positive amount`() =
        withSchema("amount") { dataSource ->
            assertEquals(
                CHECK_VIOLATION,
                failure { insertPayment(dataSource, 1, "tr_zero", amountCents = 0) },
            )
            assertEquals(
                CHECK_VIOLATION,
                failure { insertPayment(dataSource, 1, "tr_negative", amountCents = -1) },
            )

            insertPayment(dataSource, 1, "tr_one_cent", amountCents = 1)
        }

    /**
     * The provider's id is how a webhook finds its payment, so two rows must never share one. The
     * second row goes to a *different* order, so the live index cannot be what refuses it.
     */
    @Test
    fun `one mollie payment id belongs to one row`() =
        withSchema("mollie-id") { dataSource ->
            insertPayment(dataSource, 1, "tr_shared")

            assertEquals(
                UNIQUE_VIOLATION,
                failure { insertPayment(dataSource, 2, "tr_shared") },
            )
        }

    @Test
    fun `a payment names an order that really exists, and keeps it alive`() =
        withSchema("order-reference") { dataSource ->
            assertEquals(
                FOREIGN_KEY_VIOLATION,
                failure { insertPayment(dataSource, orderId = 99, molliePaymentId = "tr_orphan") },
            )

            insertPayment(dataSource, 1, "tr_kept")

            assertEquals(
                FOREIGN_KEY_VIOLATION,
                failure { execute(dataSource, "DELETE FROM voenix.orders WHERE id = 1") },
                "RESTRICT: an order somebody may have been charged for does not vanish",
            )
        }

    /**
     * The rule the whole module rests on. One order carries one live payment; a payment that ended
     * terminally falls out of the index, which is what makes a Wave-3 retry a *second* payment for
     * the same order rather than an impossibility.
     */
    @Test
    fun `an order can carry one live payment, but any number of dead ones`() =
        withSchema("live-order") { dataSource ->
            insertPayment(dataSource, 1, "tr_live", status = "OPEN")

            listOf("PENDING", "AUTHORIZED", "PAID").forEachIndexed { index, status ->
                assertEquals(
                    UNIQUE_VIOLATION,
                    failure { insertPayment(dataSource, 1, "tr_second_$index", status = status) },
                )
            }

            // A payment that failed leaves the order free to try again, and the failed rows pile up
            // next to each other without ever colliding.
            execute(dataSource, "UPDATE voenix.payments SET status = 'FAILED' WHERE id = 1")
            insertPayment(dataSource, 1, "tr_dead_cancelled", status = "CANCELED")
            insertPayment(dataSource, 1, "tr_dead_expired", status = "EXPIRED")
            insertPayment(dataSource, 1, "tr_retry", status = "OPEN")

            assertEquals(
                UNIQUE_VIOLATION,
                failure { insertPayment(dataSource, 1, "tr_third", status = "OPEN") },
            )
            assertEquals(4, count(dataSource, "SELECT count(*) FROM voenix.payments"))
        }

    /**
     * The other direction of the same index, and the one the webhook meets: a dead payment that
     * Mollie later reports as paid cannot move back into a slot a newer payment occupies.
     */
    @Test
    fun `a dead payment cannot come back to life next to a live one`() =
        withSchema("resurrection") { dataSource ->
            insertPayment(dataSource, 1, "tr_dead", status = "FAILED")
            insertPayment(dataSource, 1, "tr_live", status = "OPEN")

            assertEquals(
                UNIQUE_VIOLATION,
                failure {
                    execute(
                        dataSource,
                        "UPDATE voenix.payments SET status = 'PAID' " +
                            "WHERE mollie_payment_id = 'tr_dead'",
                    )
                },
            )
        }

    private fun withSchema(
        name: String,
        test: (HikariDataSource) -> Unit,
    ) {
        migratedDataSource("payment-schema-$name").use { dataSource ->
            seed(dataSource)
            test(dataSource)
        }
    }

    private fun insertPayment(
        dataSource: DataSource,
        orderId: Long,
        molliePaymentId: String,
        status: String = "OPEN",
        amountCents: Int = 4_070,
    ) {
        execute(
            dataSource,
            "INSERT INTO voenix.payments " +
                "(order_id, mollie_payment_id, status, amount_cents, checkout_url) VALUES " +
                "($orderId, '$molliePaymentId', '$status', $amountCents, " +
                "'https://checkout.mollie.com/pay/$molliePaymentId')",
        )
    }

    private fun failure(write: () -> Unit): String? =
        try {
            write()
            null
        } catch (exception: SQLException) {
            exception.sqlState
        }

    private companion object {
        const val CHECK_VIOLATION = "23514"
        const val UNIQUE_VIOLATION = "23505"
        const val FOREIGN_KEY_VIOLATION = "23503"
    }
}
