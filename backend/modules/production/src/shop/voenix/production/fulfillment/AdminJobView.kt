package shop.voenix.production.fulfillment

import java.time.Instant
import kotlinx.serialization.Serializable
import shop.voenix.json.InstantIso8601Serializer

/**
 * What an admin sees of one production job: everything the supplier sees, plus the two things only
 * an operator needs.
 *
 * The first is [supplier] — the admin list spans every supplier, so each row has to say whose job
 * it is. The second is the generation state: [generationAttemptCount] and [lastGenerationErrorCode]
 * make a job that never produced its PDF diagnosable instead of merely late, which is the whole
 * reason un-generated jobs are listed at all.
 *
 * It is deliberately not the supplier view plus extras in the type system: an admin answer is its
 * own contract, and nesting the supplier one would have tempted a later change to widen the
 * supplier view to serve both.
 */
@Serializable
internal data class AdminJobView(
    val jobId: Long,
    val orderId: Long,
    val orderDate: String,
    val supplier: Supplier,
    val customerFirstName: String,
    val customerLastName: String,
    val shippingStreet: String,
    val shippingHouseNumber: String,
    val shippingPostalCode: String,
    val shippingCity: String,
    val shippingCountry: String,
    val items: List<FulfillmentItemView>,
    val pdfAvailable: Boolean,
    val generationAttemptCount: Int,
    val lastGenerationErrorCode: String?,
    @Serializable(with = InstantIso8601Serializer::class) val shippedAt: Instant?,
    val shippedByUserId: Long?,
    val shippingCarrier: String?,
    val trackingNumber: String?,
) {
    /**
     * The supplier a job belongs to. [name] is `null` only when the supplier module no longer knows
     * the id, which the foreign key on the job row prevents — the row still names its supplier
     * rather than dropping out of the list.
     */
    @Serializable
    data class Supplier(
        val id: Long,
        val name: String?,
    )
}
