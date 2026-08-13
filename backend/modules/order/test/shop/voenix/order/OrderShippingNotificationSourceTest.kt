package shop.voenix.order

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.http.FrontendBaseUrl
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The order module as the shipping notification sees it: whom to write to, how to greet them, and
 * the permanent link — and nothing else.
 *
 * The two rules pinned here are the ones the type alone cannot state: every value is read again per
 * attempt, so a corrected address reaches the next send, and the access token stays inside this
 * module — production receives the finished link, never the credential it is built from.
 */
internal class OrderShippingNotificationSourceTest : PostgresIntegrationTest() {
    @Test
    fun `the customer, the greeting name, and the permanent link come from the stored order`() =
        withFixture("values") { fixture ->
            val orderId = fixture.place(cartId = 1)

            val order = fixture.load(orderId) ?: fail("Expected the placed order")

            assertEquals(OrderTestSupport.EMAIL, order.recipientEmail)
            assertEquals("Ada", order.customerFirstName)
            assertTrue(
                order.orderUrl.value.startsWith("${OrderTestSupport.FRONTEND_BASE_URL}/order/"),
                "the permanent order page, not an API path: ${order.orderUrl.value}",
            )
            assertEquals(
                "EmailActionUrl([REDACTED])",
                order.orderUrl.toString(),
                "the link redacts itself, so a trace of the mail cannot print it",
            )
        }

    @Test
    fun `a corrected address reaches the next attempt and an unknown order is retryable`() =
        withFixture("fresh") { fixture ->
            val orderId = fixture.place(cartId = 1)

            fixture.setEmail("neue-adresse@example.com")

            assertEquals(
                "neue-adresse@example.com",
                fixture.load(orderId)?.recipientEmail,
                "every attempt reads the address again",
            )
            assertNull(fixture.load(404L), "an unknown order is null, which the worker retries")
        }

    private fun withFixture(
        name: String,
        test: suspend CoroutineScope.(Fixture) -> Unit,
    ) {
        migratedDataSource("order-shipping-notification-$name").use { dataSource ->
            OrderTestSupport.seed(dataSource)
            val database = Database.connect(dataSource)
            val articles =
                OrderTestSupport.FakeArticles(
                    mapOf(OrderTestSupport.REFERENCE to OrderTestSupport.variant())
                )
            // The module handle is what the application hands to production, so the fixture reads
            // through the exported port instead of calling the service behind it.
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

        suspend fun load(orderId: Long) = module.shippingNotificationOrders.load(orderId)

        fun setEmail(email: String) {
            OrderTestSupport.execute(dataSource, "UPDATE voenix.orders SET email = '$email'")
        }
    }
}
