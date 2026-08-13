package shop.voenix.production

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import shop.voenix.email.EmailActionUrl
import shop.voenix.email.EmailRecipient
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.QueuedEmailSource

/** Production's one queued-email branch: two kinds of its own, and nothing of anybody else's. */
internal class ProductionQueuedEmailsTest {
    @Test
    fun `each of the two kinds reaches its own resolver`() = runBlocking {
        val producerMail = producerNotification()
        val shippingMail = shippingNotification()
        val emails = ProductionQueuedEmails(QueuedEmailSource { producerMail })
        emails.bindShippingNotifications(QueuedEmailSource { shippingMail })

        assertEquals(
            producerMail,
            emails.resolve(QueuedEmailReference.ProducerPdfNotification(7)),
        )
        assertEquals(shippingMail, emails.resolve(QueuedEmailReference.ShippingNotification(7)))
    }

    @Test
    fun `an unbound shipping branch fails retryably instead of losing the job`() {
        val emails = ProductionQueuedEmails(QueuedEmailSource { null })

        assertFailsWith<IllegalStateException> {
            runBlocking { emails.resolve(QueuedEmailReference.ShippingNotification(7)) }
        }
    }

    @Test
    fun `a second binding of the shipping branch is a wiring bug`() {
        val emails = ProductionQueuedEmails(QueuedEmailSource { null })
        emails.bindShippingNotifications(QueuedEmailSource { null })

        assertFailsWith<IllegalStateException> {
            emails.bindShippingNotifications(QueuedEmailSource { null })
        }
    }

    @Test
    fun `an order confirmation is not production's mail`() {
        val emails = ProductionQueuedEmails(QueuedEmailSource { null })

        assertFailsWith<IllegalArgumentException> {
            runBlocking { emails.resolve(QueuedEmailReference.OrderConfirmation(42)) }
        }
    }

    private fun producerNotification(): QueuedEmail =
        QueuedEmail.ProducerPdfNotification(
            recipient = EmailRecipient("producer@example.com"),
            orderId = 42,
            fileName = "ORD-42.pdf",
            destinationLabel = "Producer inbox",
            orderDate = LocalDate.of(2026, 7, 16),
            itemCount = 2,
        )

    private fun shippingNotification(): QueuedEmail =
        QueuedEmail.ShippingNotification(
            recipient = EmailRecipient("kundin@example.com"),
            orderId = 42,
            customerFirstName = "Erika",
            items =
                listOf(
                    QueuedEmail.ShippingNotification.Item(
                        articleName = "Zaubertasse",
                        variantName = "Blau",
                        quantity = 2,
                    )
                ),
            orderUrl = EmailActionUrl("https://shop.example/order/token"),
        )
}
