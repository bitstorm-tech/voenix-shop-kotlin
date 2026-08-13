package shop.voenix.production.fulfillment

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * The body of a ship request: what the supplier optionally knows about the package it just handed
 * over.
 *
 * Both fields are optional and independent. A supplier that drops a package at a counter without
 * noting anything ships with an empty body, one that only knows the number sends only the number,
 * and blank text is the same as absent — a form that submits `""` must not store an empty string
 * the mail would then print.
 *
 * There is deliberately no `trackingUrl` field. The link of the notification mail is built by the
 * shop from [ShippingCarrier]; accepting one here would let anybody with a supplier login put an
 * arbitrary link into a mail sent under the shop's name.
 */
@Serializable
internal data class ShipJobInput(
    val carrier: String? = null,
    val trackingNumber: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        val carrierName = carrier.normalized()
        if (carrierName != null && ShippingCarrier.of(carrierName) == null) {
            put(
                "carrier",
                listOf(
                    "Carrier must be one of: " +
                        ShippingCarrier.entries.joinToString { entry -> entry.name }
                ),
            )
        }

        val number = trackingNumber.normalized()
        if (number != null && number.length > MAXIMUM_TRACKING_NUMBER_LENGTH) {
            put(
                "trackingNumber",
                listOf("TrackingNumber must be at most $MAXIMUM_TRACKING_NUMBER_LENGTH characters"),
            )
        } else if (number != null && number.any(Char::isISOControl)) {
            put("trackingNumber", listOf("TrackingNumber must not contain control characters"))
        }
    }

    /**
     * The validated body as the value the service ships with. Only call it on a body that passed
     * [validate]: an unknown carrier name is dropped here rather than stored as `null` silently.
     */
    fun toShipment(): Shipment =
        Shipment(
            carrier = ShippingCarrier.of(carrier.normalized()),
            trackingNumber = trackingNumber.normalized(),
        )
}

/** The width of `production_jobs.tracking_number`. */
private const val MAXIMUM_TRACKING_NUMBER_LENGTH = 128

/** Trimmed text, or `null` for both "absent" and "blank" — the shipping columns know one empty. */
private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)
