package shop.voenix.order

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The confirmation mail of a paid order, as the mail worker asks for it.
 *
 * The rule this file exists for is "per attempt": the mail is not built once when the order is paid
 * and then kept, it is built again from the stored order every time the worker tries to send it. So
 * a corrected recipient reaches the customer on the next attempt, while everything they paid for —
 * the addresses, the lines, the amounts — stays what it was when they paid.
 */
internal class OrderConfirmationMailTest : PostgresIntegrationTest() {
    @Test
    fun `the mail is built from the stored order`() =
        withFixture("stored") { fixture ->
            val orderId = fixture.placeOrder()

            val mail = fixture.resolve(orderId)

            assertEquals("Customer@Example.com", mail.recipient.value)
            assertEquals(orderId, mail.orderId)
            assertEquals("Ada", mail.customerFirstName)
            assertEquals(
                QueuedEmail.OrderConfirmation.Address(
                    firstName = "Ada",
                    lastName = "Lovelace",
                    street = "Hauptstrasse",
                    houseNumber = "1",
                    city = "Berlin",
                    postalCode = "10115",
                    country = "DE",
                ),
                mail.shippingAddress,
            )
            assertEquals(
                mail.shippingAddress,
                mail.billingAddress,
                "a placement without a billing address bills where it ships",
            )
            assertEquals(3_980L, mail.subtotalInCents)
            assertEquals(490L, mail.shippingCostInCents)
            assertEquals(0L, mail.discountInCents)
            assertEquals(4_470L, mail.totalInCents)

            val item = mail.items.single()
            assertEquals("Classic mug", item.articleName)
            assertEquals("White", item.variantName)
            assertEquals(2, item.quantity)
            assertEquals(
                1_990L,
                item.unitPriceInCents,
                "the line price the cart charged: article price plus prompt price",
            )
            assertEquals(
                mail.subtotalInCents,
                mail.items.sumOf { line -> line.unitPriceInCents * line.quantity },
                "so the printed lines add up to the printed subtotal",
            )
        }

    @Test
    fun `a recipient corrected between two attempts reaches the customer`() =
        withFixture("recipient") { fixture ->
            val orderId = fixture.placeOrder()
            assertEquals("Customer@Example.com", fixture.resolve(orderId).recipient.value)

            OrderTestSupport.execute(
                fixture.dataSource,
                "UPDATE voenix.orders SET email = 'corrected@example.com'",
            )

            assertEquals("corrected@example.com", fixture.resolve(orderId).recipient.value)
        }

    @Test
    fun `the order date is the Berlin calendar day on both sides of midnight`() =
        withFixture("order-date") { fixture ->
            val orderId = fixture.placeOrder()

            fixture.setCreatedAt("2026-07-30 22:30:00+00")
            assertEquals("2026-07-31", fixture.resolve(orderId).orderDate.toString())

            fixture.setCreatedAt("2026-01-15 23:30:00+00")
            assertEquals("2026-01-16", fixture.resolve(orderId).orderDate.toString())
        }

    @Test
    fun `an order paid entirely with a coupon still produces a mail`() =
        withFixture("full-discount") { fixture ->
            val orderId =
                fixture.placeOrder(
                    OrderTestSupport.placeOrderInput(
                        subtotalCents = 3_980,
                        shippingCostCents = 490,
                        discountCents = 4_470,
                    )
                )

            val mail = fixture.resolve(orderId)

            assertEquals(0L, mail.totalInCents)
            assertEquals(4_470L, mail.discountInCents)
            assertEquals(
                3_980L,
                mail.subtotalInCents,
                "the subtotal is the stored one, not the total minus shipping",
            )
        }

    @Test
    fun `an order that is gone is left for a later scan`() =
        withFixture("unknown") { fixture -> assertNull(fixture.resolveOrNull(404)) }

    @Test
    fun `a foreign reference kind is a wiring bug`() =
        withFixture("foreign") { fixture ->
            assertFailsWith<IllegalArgumentException> {
                runBlocking {
                    fixture.module.orderConfirmations.resolve(
                        QueuedEmailReference.ProducerPdfNotification(1)
                    )
                }
            }
        }

    private fun withFixture(
        name: String,
        test: suspend CoroutineScope.(Fixture) -> Unit,
    ) {
        migratedDataSource("order-confirmation-mail-$name").use { dataSource ->
            OrderTestSupport.seed(dataSource)
            val database = Database.connect(dataSource)
            val articles =
                OrderTestSupport.FakeArticles(
                    mapOf(OrderTestSupport.REFERENCE to OrderTestSupport.variant())
                )
            val module =
                createOrderModule(
                    database = database,
                    articles = articles,
                    promotions = OrderTestSupport.FakePromotions(),
                    productionOutbox = OrderTestSupport.FakeProductionOutbox(),
                    emailOutbox = OrderTestSupport.FakeEmailOutbox(),
                    printImages = OrderTestSupport.FakePrintImages(),
                )
            val service =
                OrderService(
                    repository = OrderRepository(database),
                    articles = articles,
                    promotions = OrderTestSupport.FakePromotions(),
                    productionOutbox = OrderTestSupport.FakeProductionOutbox(),
                    emailOutbox = OrderTestSupport.FakeEmailOutbox(),
                    printImages = OrderTestSupport.FakePrintImages(),
                )
            runBlocking { test(Fixture(dataSource, service, module)) }
        }
    }

    private class Fixture(
        val dataSource: HikariDataSource,
        val service: OrderService,
        val module: OrderModule,
    ) {
        suspend fun placeOrder(input: PlaceOrderInput = OrderTestSupport.placeOrderInput()): Long =
            when (val result = service.place(input)) {
                is OrderWriteResult.Stored -> result.order.orderId
                else -> fail("Expected a stored order but got $result")
            }

        /** Resolved through the exported capability, which is what the application binds. */
        suspend fun resolveOrNull(orderId: Long): QueuedEmail? =
            module.orderConfirmations.resolve(QueuedEmailReference.OrderConfirmation(orderId))

        suspend fun resolve(orderId: Long): QueuedEmail.OrderConfirmation =
            when (val mail = resolveOrNull(orderId)) {
                is QueuedEmail.OrderConfirmation -> mail
                else -> fail("Expected an order confirmation but got $mail")
            }

        fun setCreatedAt(timestamp: String) {
            OrderTestSupport.execute(
                dataSource,
                "UPDATE voenix.orders SET created_at = '$timestamp'",
            )
        }
    }
}
