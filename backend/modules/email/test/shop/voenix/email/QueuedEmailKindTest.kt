package shop.voenix.email

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The persisted vocabulary of the outbox. The round trip pins both the exact strings the `V5` CHECK
 * constraint allows and the fact that forward and reverse mapping agree.
 */
internal class QueuedEmailKindTest {
    @Test
    fun `every reference keeps its persisted kind through a round trip`() {
        val references =
            mapOf(
                QueuedEmailReference.OrderConfirmation(1) to "ORDER_CONFIRMATION",
                QueuedEmailReference.ProducerPdfNotification(2) to "PRODUCER_PDF_NOTIFICATION",
                QueuedEmailReference.ShippingNotification(3) to "SHIPPING_NOTIFICATION",
            )

        references.forEach { (reference, expectedKind) ->
            assertEquals(expectedKind, reference.kind)
            assertEquals(reference, expectedKind.toQueuedEmailReference(reference.sourceId))
        }
    }

    @Test
    fun `an unknown persisted kind fails loudly`() {
        val failure =
            assertFailsWith<IllegalStateException> { "PIGEON_POST".toQueuedEmailReference(1) }

        assertEquals("Unsupported persisted email kind", failure.message)
    }
}
