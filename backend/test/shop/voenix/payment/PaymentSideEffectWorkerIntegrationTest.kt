package shop.voenix.payment

import shop.voenix.customer.CustomerRepository
import shop.voenix.customer.NewCustomer
import shop.voenix.db.DatabaseFactory
import shop.voenix.order.NewOrder
import shop.voenix.order.OrderStatus
import shop.voenix.testing.PostgresIntegrationTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentSideEffectWorkerIntegrationTest : PostgresIntegrationTest() {
    private var databaseFactory: DatabaseFactory? = null

    @AfterTest
    fun closeDatabase() {
        databaseFactory?.close()
    }

    @Test
    fun `worker runs email and sftp side effects with retry and idempotent completion`() {
        val factory = DatabaseFactory(postgresSettings(poolName = "voenix-shop-worker-test"))
        databaseFactory = factory
        val database = factory.connectAndMigrate()
        val customers = CustomerRepository(database)
        val payments = PaymentProofRepository(database)
        val molliePaymentId = "tr_${System.nanoTime()}"
        val customer =
            customers.create(
                NewCustomer(
                    email = "worker-${System.nanoTime()}@example.test",
                    displayName = null,
                    notes = null,
                    initialOrder =
                        NewOrder(
                            status = OrderStatus.Draft,
                            customerReference = null,
                        ),
                ),
            )
        val orderId = customer.orders.single().id
        payments.attachMolliePayment(orderId, molliePaymentId)
        payments.markOrderPaidFromMollie(
            MolliePayment(id = molliePaymentId, status = MolliePaymentStatus.Paid),
            nowEpochSeconds = 100,
        )
        val sink = FlakySink(failFirstSftp = true)
        val worker =
            PaymentSideEffectWorker(
                repository = payments,
                sink = sink,
                retryDelaySeconds = 10,
            )

        assertEquals(2, worker.runDueBatch(nowEpochSeconds = 100))
        assertEquals(listOf("email:$orderId"), sink.completed)
        assertEquals(0, worker.runDueBatch(nowEpochSeconds = 109))
        assertEquals(1, worker.runDueBatch(nowEpochSeconds = 110))
        assertEquals(listOf("email:$orderId", "sftp:$orderId"), sink.completed)
        assertEquals(0, worker.runDueBatch(nowEpochSeconds = 120))
        assertEquals(
            listOf(SideEffectStatus.Succeeded, SideEffectStatus.Succeeded),
            payments.listSideEffectsForOrder(orderId).map { it.status },
        )
    }

    private class FlakySink(
        private var failFirstSftp: Boolean,
    ) : PaymentSideEffectSink {
        val completed = mutableListOf<String>()

        override fun sendOrderPaidEmail(command: PaymentSideEffectCommand) {
            completed += "email:${command.orderId}"
        }

        override fun uploadOrderPaidSftp(command: PaymentSideEffectCommand) {
            if (failFirstSftp) {
                failFirstSftp = false
                error("temporary SFTP failure")
            }

            completed += "sftp:${command.orderId}"
        }
    }
}
