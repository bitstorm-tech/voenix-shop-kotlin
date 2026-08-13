package shop.voenix.production.fulfillment

import java.time.Instant
import kotlinx.serialization.Serializable
import shop.voenix.json.InstantIso8601Serializer

/**
 * What a supplier sees of one of its jobs: the package to build, the address to put on it, and the
 * document to print.
 *
 * The type *is* the data minimization of this feature. There is no field for an e-mail address, a
 * phone number, a price, a total, or an order access token, so no read path can leak one by
 * forgetting a filter — a pin test asserts that none of those names ever appears in the JSON.
 *
 * [orderDate] is the ISO `yyyy-MM-dd` Berlin order date, the same day the PDF prints.
 * [pdfAvailable] is `false` while the artifact is still being generated; the job is listed anyway,
 * with an empty [items] list, because a job that cannot produce its PDF must be visible rather than
 * silently absent. The three shipping fields are `null` until the job is shipped.
 */
@Serializable
internal data class SupplierJobView(
    val jobId: Long,
    val orderId: Long,
    val orderDate: String,
    val customerFirstName: String,
    val customerLastName: String,
    val shippingStreet: String,
    val shippingHouseNumber: String,
    val shippingPostalCode: String,
    val shippingCity: String,
    val shippingCountry: String,
    val items: List<FulfillmentItemView>,
    val pdfAvailable: Boolean,
    @Serializable(with = InstantIso8601Serializer::class) val shippedAt: Instant?,
    val shippingCarrier: String?,
    val trackingNumber: String?,
)
