package shop.voenix.production.fulfillment

import java.time.OffsetDateTime
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.read
import shop.voenix.db.write
import shop.voenix.email.EmailOutbox
import shop.voenix.email.QueuedEmailReference
import shop.voenix.production.delivery.ProductionJobItems
import shop.voenix.production.delivery.ProductionJobs
import shop.voenix.production.delivery.ProductionRequests
import shop.voenix.production.delivery.spod.ProductionSpodOrders

/**
 * The read side of fulfillment: production jobs as the supplier and admin lists show them.
 *
 * Two things are deliberate here. The supplier scope is a `WHERE` clause and not a filter applied
 * afterwards, so a foreign job never leaves the database in the first place. And the item lines of
 * a page are read with one query for every job of that page ([items]), never one query per job —
 * the same rule the order headers and the supplier names follow one level up.
 *
 * The jobs of a page and their items are read in two transactions. That is not a consistency
 * problem: a job's item lines are written with the job itself, in the split transaction, and never
 * change again — so the second read sees either the same rows or none at all.
 *
 * [ship] is the one write of this class, and it holds the [emailOutbox] for the same reason
 * `ProductionDeliveryRepository.completeDelivery` does: "shipped + customer notified" must be one
 * commit.
 */
internal class FulfillmentRepository(
    private val database: Database,
    private val emailOutbox: EmailOutbox,
) {
    /**
     * The jobs of one list page.
     *
     * `OPEN` is everything not yet shipped, oldest first — a supplier works its queue front to
     * back. `SHIPPED` is the newest first and capped at [SHIPPED_PAGE_SIZE] rows, because the
     * shipped list is a recent-history view and not an archive; paging is deferred until someone
     * needs the older rows (plan default of issue #119).
     *
     * The shipped order is `shipped_at DESC, id DESC` and not `shipped_at DESC` alone: two jobs
     * reported in the same transaction carry the same timestamp, and a cap on a non-total order
     * cuts at an arbitrary row — the same page could drop a job and show another one twice. The id
     * breaks every tie, so the cut is the same on every read.
     */
    suspend fun jobs(
        status: FulfillmentJobStatus,
        supplierId: Long?,
    ): List<StoredFulfillmentJob> = database.read {
        val query =
            ProductionJobs.withOrderAndRemoteOrder().select(JOB_COLUMNS).where {
                val shipped =
                    when (status) {
                        FulfillmentJobStatus.OPEN -> ProductionJobs.shippedAt.isNull()
                        FulfillmentJobStatus.SHIPPED -> ProductionJobs.shippedAt.isNotNull()
                    }
                if (supplierId == null) {
                    shipped
                } else {
                    shipped and (ProductionJobs.supplierId eq supplierId)
                }
            }
        when (status) {
            FulfillmentJobStatus.OPEN -> query.orderBy(ProductionJobs.id to SortOrder.ASC)
            FulfillmentJobStatus.SHIPPED ->
                query
                    .orderBy(
                        ProductionJobs.shippedAt to SortOrder.DESC,
                        ProductionJobs.id to SortOrder.DESC,
                    )
                    .limit(SHIPPED_PAGE_SIZE)
        }
        query.map { row -> row.toStoredJob() }
    }

    /**
     * One job by id, narrowed to [supplierScope] when the caller is a supplier.
     *
     * A job of another supplier is answered exactly like an unknown one — `null` — because the
     * scope is part of the query and not a check the caller could forget.
     */
    suspend fun job(jobId: Long, supplierScope: Long?): StoredFulfillmentJob? = database.read {
        ProductionJobs.withOrderAndRemoteOrder()
            .select(JOB_COLUMNS)
            .where {
                val byId = ProductionJobs.id eq jobId
                if (supplierScope == null) {
                    byId
                } else {
                    byId and (ProductionJobs.supplierId eq supplierScope)
                }
            }
            .singleOrNull()
            ?.toStoredJob()
    }

    /** The snapshotted item lines of every job in [jobIds], in printing order, in one query. */
    suspend fun items(jobIds: Set<Long>): Map<Long, List<StoredFulfillmentJob.Item>> {
        if (jobIds.isEmpty()) return emptyMap()
        return database.read {
            ProductionJobItems.select(
                    ProductionJobItems.productionJobId,
                    ProductionJobItems.position,
                    ProductionJobItems.articleName,
                    ProductionJobItems.variantName,
                    ProductionJobItems.supplierArticleNumber,
                    ProductionJobItems.quantity,
                )
                .where { ProductionJobItems.productionJobId inList jobIds }
                .orderBy(
                    ProductionJobItems.productionJobId to SortOrder.ASC,
                    ProductionJobItems.position to SortOrder.ASC,
                )
                .groupBy(
                    keySelector = { row -> row[ProductionJobItems.productionJobId] },
                    valueTransform = { row ->
                        StoredFulfillmentJob.Item(
                            position = row[ProductionJobItems.position],
                            articleName = row[ProductionJobItems.articleName],
                            variantName = row[ProductionJobItems.variantName],
                            supplierArticleNumber = row[ProductionJobItems.supplierArticleNumber],
                            quantity = row[ProductionJobItems.quantity],
                        )
                    },
                )
        }
    }

    /**
     * Reports one job as shipped and queues the customer's notification — one transaction, one
     * commit, or neither.
     *
     * The `WHERE` clause carries the whole business rule: the row must exist, must not be shipped
     * yet, must be prepared (decision J1 of issue #119, made channel-neutral by ADR 0002 — the
     * generated PDF of an SFTP job, the confirmed remote order of a SPOD one), and — for a supplier
     * caller — must belong to that supplier. So the update decides, not a read the caller could
     * race against: two concurrent ships of one job end as one [ShipWriteResult.SHIPPED] and one
     * [ShipWriteResult.ALREADY_SHIPPED], and exactly one mail is queued. The email module's unique
     * `(kind, source_id)` rule deduplicates on top of that.
     *
     * A failing enqueue rolls the shipment back with it: nobody should see a shipped job whose
     * customer was never told.
     *
     * When nothing was touched, the row is read back **inside this transaction** to say why. That
     * read is what separates "not yours or not there" from "already gone" and "no document yet".
     *
     * [actor] is who reported the shipment — a signed-in user or the fulfillment channel that
     * called back. It writes exactly one of the two reporter columns, and a database CHECK holds
     * the same rule from below, so no path can store a shipment nobody reported or one two parties
     * did.
     *
     * That actor is also the one place where the two ships differ, and the difference is the
     * `prepared_at` guard. A human ship keeps it: pressing the button on a job whose document does
     * not exist yet is a mistake, and `NOT_READY` says so. A channel ship does not, because the
     * shipment *is* the proof that the remote order exists and was confirmed — the partner does not
     * ship what it never produced. Refusing it would lose the shipment for good: the webhook is
     * acknowledged either way, so nothing redelivers it, and the customer would never be told. The
     * one case is real — a job quarantined as `OUTCOME_UNKNOWN` whose order an operator adopted by
     * hand — so the update sets `prepared_at = COALESCE(prepared_at, now())` in the same statement,
     * which is both what the shipping-consistency CHECK requires and the truth about the job.
     */
    suspend fun ship(
        jobId: Long,
        actor: ShipActor,
        supplierScope: Long?,
        shipment: Shipment,
    ): ShipWriteResult = database.write {
        val shipped =
            ProductionJobs.update(
                where = {
                    var shippable =
                        (ProductionJobs.id eq jobId) and ProductionJobs.shippedAt.isNull()
                    if (actor is ShipActor.User) {
                        shippable = shippable and ProductionJobs.preparedAt.isNotNull()
                    }
                    if (supplierScope == null) {
                        shippable
                    } else {
                        shippable and (ProductionJobs.supplierId eq supplierScope)
                    }
                }
            ) { statement ->
                statement[ProductionJobs.shippedAt] = CurrentTimestampWithTimeZone
                statement[ProductionJobs.preparedAt] =
                    Coalesce(ProductionJobs.preparedAt, CurrentTimestampWithTimeZone)
                statement[ProductionJobs.shippedByUserId] = (actor as? ShipActor.User)?.userId
                statement[ProductionJobs.shippedByChannel] = (actor as? ShipActor.Channel)?.channel
                statement[ProductionJobs.shippingCarrier] = shipment.carrier?.name
                statement[ProductionJobs.shippingCarrierReported] = shipment.reportedCarrierName
                statement[ProductionJobs.trackingNumber] = shipment.trackingNumber
            } > 0
        if (!shipped) return@write refusal(jobId, supplierScope)
        emailOutbox.enqueue(QueuedEmailReference.ShippingNotification(jobId))
        ShipWriteResult.SHIPPED
    }

    /** Why the guarded update touched no row, read in the transaction that ran the update. */
    private fun refusal(jobId: Long, supplierScope: Long?): ShipWriteResult {
        val row =
            ProductionJobs.select(ProductionJobs.shippedAt, ProductionJobs.preparedAt)
                .where {
                    val byId = ProductionJobs.id eq jobId
                    if (supplierScope == null) {
                        byId
                    } else {
                        byId and (ProductionJobs.supplierId eq supplierScope)
                    }
                }
                .singleOrNull() ?: return ShipWriteResult.NOT_FOUND
        return when {
            row[ProductionJobs.shippedAt] != null -> ShipWriteResult.ALREADY_SHIPPED
            row[ProductionJobs.preparedAt] == null -> ShipWriteResult.NOT_READY
            // The row satisfies every guard the update just refused: impossible, and a silent
            // "not found" would hide the bug behind a plausible answer.
            else -> error("Production job $jobId is shippable but was not shipped")
        }
    }

    private companion object {
        /** The shipped list is a recent-history view: the 100 most recent rows, no paging. */
        const val SHIPPED_PAGE_SIZE = 100

        val JOB_COLUMNS =
            listOf(
                ProductionRequests.orderId,
                ProductionSpodOrders.externalReference,
                ProductionSpodOrders.remoteState,
                ProductionJobs.id,
                ProductionJobs.supplierId,
                ProductionJobs.fulfillmentChannel,
                ProductionJobs.fileName,
                ProductionJobs.contentSha256,
                ProductionJobs.generatedAt,
                ProductionJobs.preparedAt,
                ProductionJobs.generationAttemptCount,
                ProductionJobs.lastGenerationErrorCode,
                ProductionJobs.shippedAt,
                ProductionJobs.shippedByUserId,
                ProductionJobs.shippedByChannel,
                ProductionJobs.shippingCarrier,
                ProductionJobs.shippingCarrierReported,
                ProductionJobs.trackingNumber,
            )
    }
}

/**
 * One production job as the fulfillment lists read it back: its identity, its generation state, and
 * its shipping state.
 *
 * Everything a supplier additionally sees comes from two other places — the order header from the
 * [FulfillmentOrderSource], the item lines from the job's own snapshot — so this type stays what
 * the `production_jobs` row is and nothing more.
 *
 * [contentSha256] and [generatedAt] are `NULL` together (a database CHECK guarantees it): both set
 * means the immutable artifact exists and may be downloaded, both `null` means the PDF is still in
 * preparation and [lastGenerationErrorCode] says why the last attempt did not produce one. On a
 * `SPOD` job they stay `null` forever, because that channel produces no document at all.
 *
 * [fulfillmentChannel] is how the job is produced, decided at split time, and [preparedAt] is the
 * channel-neutral "ready to ship" of that lifecycle — the generated PDF for `SFTP`, the confirmed
 * remote order for `SPOD`. The ship guard reads the latter, never [generatedAt].
 *
 * [shippedByUserId] and [shippedByChannel] are the two mutually exclusive reporters of a shipment,
 * and [shippingCarrierReported] is the carrier name a channel sent verbatim — an operator's detail,
 * next to the bounded [shippingCarrier] the customer's mail is built from.
 *
 * [externalReference] and [remoteState] come from the job's remote order and are `null` on every
 * SFTP job: the partner's order id, and the last state the partner reported for it.
 */
internal data class StoredFulfillmentJob(
    val id: Long,
    val orderId: Long,
    val supplierId: Long,
    val fulfillmentChannel: String,
    val fileName: String,
    val contentSha256: String?,
    val generatedAt: OffsetDateTime?,
    val preparedAt: OffsetDateTime?,
    val generationAttemptCount: Int,
    val lastGenerationErrorCode: String?,
    val shippedAt: OffsetDateTime?,
    val shippedByUserId: Long?,
    val shippedByChannel: String?,
    val shippingCarrier: String?,
    val shippingCarrierReported: String?,
    val trackingNumber: String?,
    val externalReference: String?,
    val remoteState: String?,
) {
    /**
     * One snapshotted item line of the job's artifact.
     *
     * [position] is the 1-based place of the line inside the supplier's share of the order, not a
     * page number: the renderer prints one page per physical unit, so a line with [quantity] 2
     * spans two printed pages.
     */
    data class Item(
        val position: Int,
        val articleName: String,
        val variantName: String,
        val supplierArticleNumber: String?,
        val quantity: Int,
    )
}

/**
 * What the guarded ship update did: it closed the job, or it touched nothing — and in that case,
 * which of the three reasons the re-read inside the same transaction found.
 *
 * It is the repository's answer, one level below [ShipResult]: no view, no HTTP meaning, just the
 * state of the row.
 */
internal enum class ShipWriteResult {
    SHIPPED,
    NOT_FOUND,
    ALREADY_SHIPPED,
    NOT_READY,
}

private fun ResultRow.toStoredJob(): StoredFulfillmentJob =
    StoredFulfillmentJob(
        id = this[ProductionJobs.id],
        orderId = this[ProductionRequests.orderId],
        supplierId = this[ProductionJobs.supplierId],
        fulfillmentChannel = this[ProductionJobs.fulfillmentChannel],
        fileName = this[ProductionJobs.fileName],
        contentSha256 = this[ProductionJobs.contentSha256],
        generatedAt = this[ProductionJobs.generatedAt],
        preparedAt = this[ProductionJobs.preparedAt],
        generationAttemptCount = this[ProductionJobs.generationAttemptCount],
        lastGenerationErrorCode = this[ProductionJobs.lastGenerationErrorCode],
        shippedAt = this[ProductionJobs.shippedAt],
        shippedByUserId = this[ProductionJobs.shippedByUserId],
        shippedByChannel = this[ProductionJobs.shippedByChannel],
        shippingCarrier = this[ProductionJobs.shippingCarrier],
        shippingCarrierReported = this[ProductionJobs.shippingCarrierReported],
        trackingNumber = this[ProductionJobs.trackingNumber],
        externalReference = this.getOrNull(ProductionSpodOrders.externalReference),
        remoteState = this.getOrNull(ProductionSpodOrders.remoteState),
    )

/**
 * The job rows with the two tables every fulfillment read needs beside them: the request, for the
 * order the job belongs to, and — only for a print-on-demand job — its remote order, for the two
 * columns the admin list shows. The remote join is a `LEFT` one, so an SFTP job is still a row.
 */
private fun ProductionJobs.withOrderAndRemoteOrder() =
    join(
            ProductionRequests,
            JoinType.INNER,
            onColumn = requestId,
            otherColumn = ProductionRequests.id,
        )
        .join(
            ProductionSpodOrders,
            JoinType.LEFT,
            onColumn = id,
            otherColumn = ProductionSpodOrders.productionJobId,
        )
