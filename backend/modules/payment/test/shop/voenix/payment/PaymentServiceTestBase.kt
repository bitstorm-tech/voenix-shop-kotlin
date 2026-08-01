package shop.voenix.payment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import shop.voenix.order.OrderPaymentGateway
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The stage every payment service test runs on: a migrated and seeded PostgreSQL database, a
 * factory for the service under test, and an appender that captures everything this module logs.
 *
 * The log matters more here than in most modules. Half of what this module promises is a *log line*
 * rather than a status: a mismatching amount, a paid cancelled order, a payment overtaken by a
 * newer one, and an unknown payment id are all answered `200` and settled by a human afterwards, so
 * a test that does not read the log cannot tell those apart from doing nothing.
 */
internal abstract class PaymentServiceTestBase : PostgresIntegrationTest() {
    protected fun withFixture(
        name: String,
        test: suspend CoroutineScope.(Fixture) -> Unit,
    ) {
        migratedDataSource("payment-$name").use { dataSource ->
            PaymentTestSupport.seed(dataSource)
            val events = ListAppender<ILoggingEvent>().apply { start() }
            // Everything this module logs, and only that: Exposed's own statement trace prints
            // whatever is in the WHERE clause and is not what the module is responsible for.
            val moduleLogger = LoggerFactory.getLogger("shop.voenix.payment") as Logger
            moduleLogger.addAppender(events)
            try {
                runBlocking { test(Fixture(dataSource, Database.connect(dataSource), events)) }
            } finally {
                moduleLogger.detachAppender(events)
            }
        }
    }

    /** One test's own database, its own service factory, and its own captured log. */
    protected class Fixture(
        val dataSource: HikariDataSource,
        val database: Database,
        val events: ListAppender<ILoggingEvent>,
    ) {
        fun service(
            mollie: MolliePayments,
            orders: OrderPaymentGateway,
        ): PaymentService = PaymentService(PaymentRepository(database), mollie, orders)

        fun paymentCount(): Int =
            PaymentTestSupport.count(dataSource, "SELECT count(*) FROM voenix.payments")

        fun status(molliePaymentId: String): String? =
            PaymentTestSupport.singleString(
                dataSource,
                "SELECT status FROM voenix.payments " +
                    "WHERE mollie_payment_id = '$molliePaymentId'",
            )

        fun checkoutUrl(molliePaymentId: String): String? =
            PaymentTestSupport.singleString(
                dataSource,
                "SELECT checkout_url FROM voenix.payments " +
                    "WHERE mollie_payment_id = '$molliePaymentId'",
            )

        fun updatedAt(molliePaymentId: String): String? =
            PaymentTestSupport.singleString(
                dataSource,
                "SELECT updated_at::text FROM voenix.payments " +
                    "WHERE mollie_payment_id = '$molliePaymentId'",
            )

        fun molliePaymentIds(): List<String> = buildList {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery("SELECT mollie_payment_id FROM voenix.payments ORDER BY id")
                        .use { rows -> while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

        fun messages(): List<String> = events.list.map(ILoggingEvent::getFormattedMessage)

        /** A log line at [level] naming every one of [fragments] — the trace an anomaly owes. */
        fun logged(
            level: Level,
            vararg fragments: String,
        ): Boolean =
            events.list.any { event ->
                event.level == level &&
                    fragments.all { fragment -> event.formattedMessage.contains(fragment) }
            }
    }
}
