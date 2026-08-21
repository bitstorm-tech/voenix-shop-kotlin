package shop.voenix.production.fulfillment

import java.time.Instant
import kotlinx.serialization.Serializable
import shop.voenix.json.InstantIso8601Serializer

/**
 * What the fulfillment routes may ask for, and the seam the route tests stub.
 *
 * Every method takes the caller's scope as a parameter instead of reading it from a call: the
 * supplier id comes from the route protection, and an admin passes `null` where a supplier passes
 * its own id. Authorization stays an HTTP concern above this interface, and no implementation can
 * accidentally answer for a scope it was not given.
 */
internal interface FulfillmentOperations {
    /** The calling supplier's own identity, for the header of its surface. */
    suspend fun identity(supplierId: Long): SupplierIdentityView

    /** One supplier's jobs in the requested state; never another supplier's. */
    suspend fun supplierJobs(supplierId: Long, status: FulfillmentJobStatus): List<SupplierJobView>

    /** Every supplier's jobs in the requested state, optionally narrowed to one supplier. */
    suspend fun adminJobs(status: FulfillmentJobStatus, supplierId: Long?): List<AdminJobView>

    /**
     * The stored artifact of one job, verified against its recorded digest. [supplierScope] is the
     * supplier a supplier caller is bound to, or `null` for an admin who may read every job.
     */
    suspend fun artifact(jobId: Long, supplierScope: Long?): FulfillmentArtifactResult

    /**
     * Reports one of the calling supplier's own jobs as shipped. A job of another supplier answers
     * exactly like an unknown one, because [supplierId] is part of the write's condition and not a
     * check above it.
     *
     * [actorUserId] is the signed-in supplier login and is recorded as the one who shipped.
     */
    suspend fun shipAsSupplier(
        jobId: Long,
        supplierId: Long,
        actorUserId: Long,
        shipment: Shipment,
    ): ShipResult<SupplierJobView>

    /**
     * Ships any supplier's job on that supplier's behalf. Same path, same rules — only the scope is
     * missing, and [actorUserId] records the administrator who did it rather than the supplier.
     */
    suspend fun shipAsAdmin(
        jobId: Long,
        actorUserId: Long,
        shipment: Shipment,
    ): ShipResult<AdminJobView>
}

/**
 * The two lists a fulfillment surface has: what still has to go out, and what already went.
 *
 * The status is derived from `production_jobs.shipped_at` and stored nowhere, so there is no third
 * state to strand a job in. It is *not* the preparation state: an unprepared job is `OPEN` too and
 * appears in the list from the split on — with its item lines, which the split writes — marked as
 * having no PDF yet. A job stuck on a missing image must be visible, not invisible.
 */
internal enum class FulfillmentJobStatus {
    OPEN,
    SHIPPED,
}

/**
 * What is known about one package at the moment it is reported as shipped: nothing, a carrier, a
 * number, or both. Everything below the routes works with this value rather than with the request
 * body, so the carrier is an enum from here on and no blank string can travel further.
 */
internal data class Shipment(val carrier: ShippingCarrier?, val trackingNumber: String?)

/**
 * Who the calling supplier login acts for: the supplier the route protection resolved from
 * `users.supplier_id`, and its display name.
 *
 * The supplier surface reads it once to label its header, which is also why the name is not
 * repeated on every job row.
 */
@Serializable
internal data class SupplierIdentityView(
    val supplierId: Long,
    val supplierName: String,
)

/**
 * What a supplier sees of one of its jobs: the package to build, the address to put on it, and the
 * document to print.
 *
 * The type *is* the data minimization of this feature. There is no field for an e-mail address, a
 * phone number, a price, a total, or an order access token, so no read path can leak one by
 * forgetting a filter — a pin test asserts that none of those names ever appears in the JSON.
 *
 * [orderDate] is the ISO `yyyy-MM-dd` Berlin order date, the same day the PDF prints.
 * [pdfAvailable] is `false` while the artifact is still being generated, and stays `false` forever
 * on a job of the print-on-demand channel, which produces no document at all; the job is listed
 * either way, because a job without a printable document must be visible rather than silently
 * absent. The three shipping fields are `null` until the job is shipped.
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

/**
 * What an admin sees of one production job: everything the supplier sees, plus the two things only
 * an operator needs.
 *
 * The first is [supplier] — the admin list spans every supplier, so each row has to say whose job
 * it is. The second is the generation state: [generationAttemptCount] and [lastGenerationErrorCode]
 * make a job that never produced its PDF diagnosable instead of merely late, which is the whole
 * reason un-generated jobs are listed at all.
 *
 * [fulfillmentChannel] is what makes that state readable in the first place: an `SFTP` job without
 * a PDF is late, a `SPOD` job without one is normal — it is produced through the partner's API and
 * has no document to wait for.
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
    val fulfillmentChannel: String,
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

/**
 * One packing line of a job, read from the snapshot written when the job was split.
 *
 * There is no price here and no article id: a supplier packs what the PDF prints, and what the
 * customer paid is none of the packing station's business.
 */
@Serializable
internal data class FulfillmentItemView(
    val articleName: String,
    val variantName: String,
    val supplierArticleNumber: String?,
    val quantity: Int,
)

/**
 * Typed outcome of a fulfillment PDF download.
 *
 * The four failures are deliberately different things. [NotFound] is the *only* answer for a job id
 * the caller may not read — an unknown id and another supplier's id are indistinguishable, because
 * the difference is exactly what a probe is looking for. The other three are states of an existing,
 * readable job: its artifact does not exist yet, its file vanished from the artifact root, or its
 * bytes no longer hash to the digest recorded at generation time. None of them is the caller's
 * fault and none is a server bug, so each is a conflict with its own stable code.
 */
internal sealed interface FulfillmentArtifactResult {
    /** The verified artifact bytes plus the producer-facing file name to offer them under. */
    class Loaded(val fileName: String, val bytes: ByteArray) : FulfillmentArtifactResult

    /** No such job — or none this caller may read. */
    data object NotFound : FulfillmentArtifactResult

    /** The job exists but its artifact has not been generated yet. */
    data object NotGenerated : FulfillmentArtifactResult

    /** The digest says an artifact exists, but no file does. */
    data object Missing : FulfillmentArtifactResult

    /** The file exists but its bytes are not the generated ones; it is never served. */
    data object DigestMismatch : FulfillmentArtifactResult
}

/**
 * What a ship request can end as. The three failures are the three answers the guarded update's "no
 * row touched" can mean, told apart by a re-read inside the same transaction:
 *
 * - [NotFound] — the job does not exist, or it belongs to another supplier. Deliberately one
 *   answer: telling a supplier that a foreign job exists is already too much.
 * - [AlreadyShipped] — somebody (or a second click) shipped it first. The first shipment stands and
 *   no second mail goes out.
 * - [NotReady] — the job is not prepared yet: no PDF was generated for an `SFTP` job, no remote
 *   order was confirmed for a `SPOD` one, so there is nothing that could have been packed (decision
 *   J1 of issue #119, made channel-neutral by ADR 0002).
 *
 * [Shipped] carries the updated view of the surface that asked, which is why the type is generic: a
 * supplier gets a `SupplierJobView`, an admin an `AdminJobView`, and both come from the one service
 * path that performs the write.
 */
internal sealed interface ShipResult<out V> {
    data class Shipped<out V>(val job: V) : ShipResult<V>

    data object NotFound : ShipResult<Nothing>

    data object AlreadyShipped : ShipResult<Nothing>

    data object NotReady : ShipResult<Nothing>
}
