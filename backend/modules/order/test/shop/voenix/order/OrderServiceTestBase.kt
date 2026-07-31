package shop.voenix.order

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The stage every order service test runs on: a migrated and seeded PostgreSQL database, the
 * service wired to the fakes from [OrderTestSupport], the two reader handles the module exports,
 * and an appender that captures everything the module logs.
 *
 * The placement, payment, and access tests all need the same wiring, so it lives here once instead
 * of in each of them. What a single slice asks of the database stays with that slice: the helpers
 * below are the vocabulary all three share.
 */
internal abstract class OrderServiceTestBase : PostgresIntegrationTest() {
    protected fun OrderWriteResult.expectStored(): OrderView =
        when (this) {
            is OrderWriteResult.Stored -> order
            else -> fail("Expected a stored order but got $this")
        }

    protected fun <T> OperationResult<T>.expectSuccess(): T =
        when (this) {
            is OperationResult.Success -> value
            else -> fail("Expected a success but got $this")
        }

    protected fun withFixture(
        name: String,
        test: suspend CoroutineScope.(Fixture) -> Unit,
    ) {
        migratedDataSource("order-service-$name").use { dataSource ->
            OrderTestSupport.seed(dataSource)
            val events = ListAppender<ILoggingEvent>().apply { start() }
            // Everything this module logs, and only that: Exposed's own statement trace prints
            // whatever is in the WHERE clause and is not what the module is responsible for.
            val moduleLogger = LoggerFactory.getLogger("shop.voenix.order") as Logger
            moduleLogger.addAppender(events)
            try {
                val database = Database.connect(dataSource)
                val repository = OrderRepository(database)
                val articles =
                    OrderTestSupport.FakeArticles(
                        mapOf(
                            OrderTestSupport.REFERENCE to OrderTestSupport.variant(),
                            OrderTestSupport.OTHER_REFERENCE to
                                OrderTestSupport.variant(articleName = "Travel mug"),
                        )
                    )
                val promotions = OrderTestSupport.FakePromotions()
                val production = OrderTestSupport.FakeProductionOutbox()
                val email = OrderTestSupport.FakeEmailOutbox()
                val fixture =
                    Fixture(
                        dataSource = dataSource,
                        database = database,
                        articles = articles,
                        promotions = promotions,
                        production = production,
                        email = email,
                        service =
                            OrderService(
                                repository,
                                articles,
                                promotions,
                                production,
                                email,
                                OrderTestSupport.FakePrintImages(),
                            ),
                        guestData = OrderGuestData(repository),
                        orderItems =
                            OrderItemReader { orderItemId, userId, guestToken ->
                                repository.orderItem(orderItemId, userId, guestToken)
                            },
                        events = events,
                    )
                runBlocking { test(fixture) }
            } finally {
                moduleLogger.detachAppender(events)
            }
        }
    }

    /** One test's own database, its own service, its own fakes, and its own captured log. */
    protected class Fixture(
        val dataSource: HikariDataSource,
        val database: Database,
        val articles: OrderTestSupport.FakeArticles,
        val promotions: OrderTestSupport.FakePromotions,
        val production: OrderTestSupport.FakeProductionOutbox,
        val email: OrderTestSupport.FakeEmailOutbox,
        val service: OrderService,
        val guestData: OrderGuestData,
        val orderItems: OrderItemReader,
        val events: ListAppender<ILoggingEvent>,
    ) {
        fun orderCount(): Int = count("voenix.orders")

        fun count(table: String): Int =
            OrderTestSupport.count(dataSource, "SELECT count(*) FROM $table")

        fun status(orderId: Long): String? =
            OrderTestSupport.singleString(
                dataSource,
                "SELECT status FROM voenix.orders WHERE id = $orderId",
            )

        fun messages(): List<String> = events.list.map(ILoggingEvent::getFormattedMessage)

        /** A WARN naming [orderId] and [detail] — the trace an unusual payment outcome owes. */
        fun warnedAbout(
            orderId: Long,
            detail: String,
        ): Boolean =
            events.list.any { event ->
                event.level == Level.WARN &&
                    event.formattedMessage.contains("$orderId") &&
                    event.formattedMessage.contains(detail)
            }
    }
}
