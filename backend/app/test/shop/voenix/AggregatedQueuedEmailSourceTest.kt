package shop.voenix

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import shop.voenix.email.EmailRecipient
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.email.QueuedEmailSource

internal class AggregatedQueuedEmailSourceTest {
    @Test
    fun `producer notifications resolve through the bound production source`() = runBlocking {
        val aggregate = AggregatedQueuedEmailSource()
        val resolvedByProduction = producerEmail()
        val seenReferences = mutableListOf<QueuedEmailReference>()
        aggregate.bindProducerNotifications { reference ->
            seenReferences += reference
            resolvedByProduction
        }

        val resolved = aggregate.resolve(QueuedEmailReference.ProducerPdfNotification(7))

        assertEquals(resolvedByProduction, resolved)
        assertEquals<List<QueuedEmailReference>>(
            listOf(QueuedEmailReference.ProducerPdfNotification(7)),
            seenReferences,
        )
    }

    @Test
    fun `an unbound producer source fails retryably instead of losing the job`() {
        val aggregate = AggregatedQueuedEmailSource()

        assertFailsWith<IllegalStateException> {
            runBlocking { aggregate.resolve(QueuedEmailReference.ProducerPdfNotification(7)) }
        }
    }

    @Test
    fun `order confirmations are not wired before the order migration`() {
        val aggregate = AggregatedQueuedEmailSource()
        aggregate.bindProducerNotifications { null }

        assertFailsWith<IllegalStateException> {
            runBlocking { aggregate.resolve(QueuedEmailReference.OrderConfirmation(7)) }
        }
    }

    @Test
    fun `a second producer binding is a wiring bug`() {
        val aggregate = AggregatedQueuedEmailSource()
        val source = QueuedEmailSource { null }
        aggregate.bindProducerNotifications(source)

        assertFailsWith<IllegalStateException> { aggregate.bindProducerNotifications(source) }
    }

    private fun producerEmail(): QueuedEmail =
        QueuedEmail.ProducerPdfNotification(
            recipient = EmailRecipient("producer@example.com"),
            orderId = 42,
            fileName = "ORD-42.pdf",
            destinationLabel = "Producer inbox",
            orderDate = LocalDate.of(2026, 7, 16),
            itemCount = 2,
            producerName = "Manufaktur Müller",
        )
}
