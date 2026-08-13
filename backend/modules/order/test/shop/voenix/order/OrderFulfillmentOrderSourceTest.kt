package shop.voenix.order

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.http.FrontendBaseUrl
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The order module as a supplier's fulfillment page sees it: order number, order date, recipient,
 * and shipping address — resolved for a whole page in one call.
 *
 * The port exists precisely so that this is *all* it can answer. What the type does not carry, the
 * supplier surface cannot show, and this test pins the two rules that are not the type's job: the
 * batching, and the Berlin calendar day that every surface of an order has to agree on.
 */
internal class OrderFulfillmentOrderSourceTest : PostgresIntegrationTest() {
    @Test
    fun `a page of orders is answered in one batch and unknown ids are simply absent`() =
        withFixture("batch") { fixture ->
            val first = fixture.place(cartId = 1)
            val second = fixture.place(cartId = 2)

            val headers = fixture.find(setOf(first, second, 404L))

            assertEquals(setOf(first, second), headers.keys, "an unknown id is absent, not null")
            val header = headers.getValue(first)
            assertEquals(first, header.orderId)
            assertEquals("Ada", header.customerFirstName)
            assertEquals("Lovelace", header.customerLastName)
            assertEquals("Hauptstrasse", header.shippingStreet)
            assertEquals("1", header.shippingHouseNumber)
            assertEquals("10115", header.shippingPostalCode)
            assertEquals("Berlin", header.shippingCity)
            assertEquals("DE", header.shippingCountry)

            assertTrue(fixture.find(emptySet()).isEmpty(), "an empty page reads nothing")
        }

    @Test
    fun `the order date is the Berlin calendar day the order and its PDF name`() =
        withFixture("order-date") { fixture ->
            val orderId = fixture.place(cartId = 1)

            // Summer time: 22:30 UTC is already the next day in Berlin.
            fixture.setCreatedAt("2026-07-30 22:30:00+00")
            assertEquals("2026-07-31", fixture.orderDate(orderId))

            // Winter time: the same is true one hour later.
            fixture.setCreatedAt("2026-01-15 23:30:00+00")
            assertEquals("2026-01-16", fixture.orderDate(orderId))
        }

    private fun withFixture(
        name: String,
        test: suspend CoroutineScope.(Fixture) -> Unit,
    ) {
        migratedDataSource("order-fulfillment-orders-$name").use { dataSource ->
            OrderTestSupport.seed(dataSource)
            val database = Database.connect(dataSource)
            val articles =
                OrderTestSupport.FakeArticles(
                    mapOf(OrderTestSupport.REFERENCE to OrderTestSupport.variant())
                )
            // The module handle is what the application hands to production, so the fixture reads
            // through the exported port instead of calling the repository behind it.
            val module =
                createOrderModule(
                    database = database,
                    frontendBaseUrl = FrontendBaseUrl(OrderTestSupport.FRONTEND_BASE_URL),
                    articles = articles,
                    promotions = OrderTestSupport.FakePromotions(),
                    productionOutbox = OrderTestSupport.FakeProductionOutbox(),
                    emailOutbox = OrderTestSupport.FakeEmailOutbox(),
                    printImages = OrderTestSupport.FakePrintImages(emptyMap()),
                    payments = OrderTestSupport.FakePaymentStatuses(),
                )
            runBlocking { test(Fixture(dataSource, module)) }
        }
    }

    private class Fixture(val dataSource: HikariDataSource, val module: OrderModule) {
        suspend fun place(cartId: Long): Long {
            val result =
                module.placement.place(
                    OrderTestSupport.placeOrderInput(
                        cartId = cartId,
                        subtotalCents = 3_980,
                        lines = listOf(OrderTestSupport.line()),
                    )
                )
            return when (result) {
                is OrderPlacementResult.Placed -> result.order.orderId
                else -> fail("Expected a stored order but got $result")
            }
        }

        suspend fun find(orderIds: Set<Long>) = module.fulfillmentOrders.find(orderIds)

        suspend fun orderDate(orderId: Long): String =
            find(setOf(orderId)).getValue(orderId).orderDate.toString()

        fun setCreatedAt(timestamp: String) {
            OrderTestSupport.execute(
                dataSource,
                "UPDATE voenix.orders SET created_at = '$timestamp'",
            )
        }
    }
}
