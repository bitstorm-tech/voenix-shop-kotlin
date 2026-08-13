package shop.voenix.production.fulfillment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bounded carrier list and the links the shop builds from it (decision J2 of issue #119).
 *
 * The names are pinned against the database CHECK of `production_jobs`: the two lists have to stay
 * the same, or a perfectly valid carrier would be refused by the column it is written into.
 */
internal class ShippingCarrierTest {
    @Test
    fun `the carrier names are exactly the ones the database allows`() {
        assertEquals(
            listOf("DHL", "DPD", "GLS", "HERMES", "UPS", "DEUTSCHE_POST", "OTHER"),
            ShippingCarrier.entries.map { carrier -> carrier.name },
        )
    }

    @Test
    fun `every carrier but OTHER builds a link that carries the number`() {
        ShippingCarrier.entries
            .filter { carrier -> carrier != ShippingCarrier.OTHER }
            .forEach { carrier ->
                val url = carrier.trackingUrl("00340434161094042557")
                assertTrue(
                    url != null && url.startsWith("https://"),
                    "${carrier.name} must offer an HTTPS tracking page",
                )
                assertTrue(
                    url.endsWith("00340434161094042557"),
                    "${carrier.name} must point at the number: $url",
                )
                assertTrue(carrier.displayName != null, "${carrier.name} needs a customer name")
            }
    }

    @Test
    fun `OTHER has no link and no name, so the mail shows the number as text`() {
        assertNull(ShippingCarrier.OTHER.trackingUrl("12345"))
        assertNull(ShippingCarrier.OTHER.displayName)
    }

    @Test
    fun `a number that is missing or blank never produces a link`() {
        assertNull(ShippingCarrier.DHL.trackingUrl(null))
        assertNull(ShippingCarrier.DHL.trackingUrl("   "))
    }

    @Test
    fun `a number with URL syntax is encoded instead of changing the link`() {
        assertEquals(
            "https://www.ups.com/track?loc=de_DE&tracknum=1Z%26loc%3Devil",
            ShippingCarrier.UPS.trackingUrl("1Z&loc=evil"),
        )
    }

    /**
     * A space is where form encoding and URL encoding disagree: `URLEncoder` alone would write `+`,
     * which is a literal plus in DPD's path segment and in Hermes' fragment — a number the carrier
     * cannot find. `%20` is the space everywhere.
     */
    @Test
    fun `a number with a space becomes %20 and never a plus`() {
        assertEquals(
            "https://tracking.dpd.de/status/de_DE/parcel/01234%20567",
            ShippingCarrier.DPD.trackingUrl("01234 567"),
        )
        assertEquals(
            "https://www.myhermes.de/empfangen/sendungsverfolgung/sendungsinformation/#01234%20567",
            ShippingCarrier.HERMES.trackingUrl("01234 567"),
        )
        assertEquals(
            "https://www.ups.com/track?loc=de_DE&tracknum=01234%20567",
            ShippingCarrier.UPS.trackingUrl("01234 567"),
        )
    }

    @Test
    fun `only an exact stored name is a carrier`() {
        assertEquals(ShippingCarrier.DEUTSCHE_POST, ShippingCarrier.of("DEUTSCHE_POST"))
        assertNull(ShippingCarrier.of("dhl"))
        assertNull(ShippingCarrier.of("POST_AG"))
        assertNull(ShippingCarrier.of(null))
    }
}
