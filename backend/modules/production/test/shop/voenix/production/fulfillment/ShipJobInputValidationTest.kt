package shop.voenix.production.fulfillment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two optional fields of a ship request: what passes, what is refused, what becomes `null`. */
internal class ShipJobInputValidationTest {
    @Test
    fun `an empty body is valid and ships without carrier or number`() {
        val input = ShipJobInput()

        assertEquals(emptyMap(), input.validate())
        assertEquals(Shipment(carrier = null, trackingNumber = null), input.toShipment())
    }

    @Test
    fun `the two fields are independent`() {
        assertEquals(
            Shipment(carrier = ShippingCarrier.DHL, trackingNumber = null),
            ShipJobInput(carrier = "DHL").toShipment(),
        )
        assertEquals(
            Shipment(carrier = null, trackingNumber = "0034"),
            ShipJobInput(trackingNumber = "0034").toShipment(),
        )
    }

    @Test
    fun `blank text is the same as absent and values are trimmed`() {
        val input = ShipJobInput(carrier = "   ", trackingNumber = "  ")

        assertEquals(emptyMap(), input.validate())
        assertNull(input.toShipment().carrier)
        assertNull(input.toShipment().trackingNumber)
        assertEquals("0034", ShipJobInput(trackingNumber = " 0034 ").toShipment().trackingNumber)
    }

    @Test
    fun `an unknown carrier is a field error naming the allowed set`() {
        val errors = ShipJobInput(carrier = "POST_AG").validate()

        assertEquals(setOf("carrier"), errors.keys)
        assertTrue(errors.getValue("carrier").single().contains("DEUTSCHE_POST"))
    }

    @Test
    fun `a tracking number is bounded by its column and free of control characters`() {
        assertEquals(
            setOf("trackingNumber"),
            ShipJobInput(trackingNumber = "9".repeat(129)).validate().keys,
        )
        assertEquals(emptyMap(), ShipJobInput(trackingNumber = "9".repeat(128)).validate())
        assertEquals(
            setOf("trackingNumber"),
            ShipJobInput(trackingNumber = "0034\u0007").validate().keys,
        )
    }
}
