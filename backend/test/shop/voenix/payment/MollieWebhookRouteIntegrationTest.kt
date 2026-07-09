package shop.voenix.payment

import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import shop.voenix.db.DatabaseFactory
import shop.voenix.module
import shop.voenix.persistence.customer.CustomerRepository
import shop.voenix.persistence.customer.NewCustomer
import shop.voenix.persistence.order.NewOrder
import shop.voenix.persistence.order.OrderStatus
import shop.voenix.testing.PostgresIntegrationTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MollieWebhookRouteIntegrationTest : PostgresIntegrationTest() {
    private var databaseFactory: DatabaseFactory? = null

    @AfterTest
    fun closeDatabase() {
        databaseFactory?.close()
    }

    @Test
    fun `paid Mollie webhook verifies remote state marks order paid and enqueues side effects once`() = testApplication {
        val factory = DatabaseFactory(postgresSettings(poolName = "voenix-shop-mollie-route-test"))
        databaseFactory = factory
        val database = factory.connectAndMigrate()
        val customers = CustomerRepository(database)
        val payments = PaymentProofRepository(database)
        val molliePaymentId = "tr_${System.nanoTime()}"
        val customer =
            customers.create(
                NewCustomer(
                    email = "webhook-${System.nanoTime()}@example.test",
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

        environment {
            config = postgresConfig()
        }
        application {
            module(paymentStatusLookup = StaticMolliePaymentStatusLookup(molliePaymentId, MolliePaymentStatus.Paid))
        }

        val first = client.post(PaymentRoutes.MollieWebhookPath) { mollieWebhookBody(molliePaymentId) }
        val duplicate = client.post(PaymentRoutes.MollieWebhookPath) { mollieWebhookBody(molliePaymentId) }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, duplicate.status)
        val paid = assertNotNull(customers.findById(customer.id))
        assertEquals(OrderStatus.Paid, paid.orders.single().status)
        assertEquals(
            listOf(SideEffectType.Email, SideEffectType.SftpUpload),
            payments.listSideEffectsForOrder(orderId).map { it.type },
        )
    }

    @Test
    fun `Mollie webhook rejects missing payment id`() = testApplication {
        environment {
            config = postgresConfig()
        }
        application {
            module(paymentStatusLookup = StaticMolliePaymentStatusLookup("unused", MolliePaymentStatus.Paid))
        }

        val response = client.post(PaymentRoutes.MollieWebhookPath) {
            setBody(FormDataContent(Parameters.Empty))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.mollieWebhookBody(paymentId: String) {
        setBody(
            FormDataContent(
                Parameters.build {
                    append("id", paymentId)
                },
            ),
        )
    }

    private class StaticMolliePaymentStatusLookup(
        private val paymentId: String,
        private val status: MolliePaymentStatus,
    ) : MolliePaymentStatusLookup {
        override suspend fun fetch(paymentId: String): MolliePayment? =
            if (paymentId == this.paymentId) {
                MolliePayment(id = paymentId, status = status)
            } else {
                null
            }
    }
}
