package shop.voenix.production.delivery.spod

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.read
import shop.voenix.db.write
import shop.voenix.email.EmailOutbox
import shop.voenix.email.QueuedEmailReference
import shop.voenix.production.delivery.ProductionChannels
import shop.voenix.production.delivery.ProductionDeliveryDestination
import shop.voenix.production.delivery.ProductionDestinationSpod
import shop.voenix.production.delivery.ProductionDestinations
import shop.voenix.production.delivery.ProductionJobItems
import shop.voenix.production.delivery.ProductionJobs
import shop.voenix.production.delivery.ProductionRequests
import shop.voenix.production.spod.SpodEnvironment

/**
 * Persistence of the remote lifecycle of print-on-demand jobs: the order row of
 * `production_spod_orders`, the uploaded designs of `production_spod_designs`, and the two
 * destination reads the submission stage needs.
 *
 * Open/finished state derives from the job's own `prepared_at`, exactly like the other production
 * repositories derive theirs from a nullable timestamp: a confirmed job is prepared, and a prepared
 * job is never scanned again. There is no in-progress status to strand.
 *
 * The one method that matters more than the others is [recordCreatedOrder]. The partner has no
 * idempotency mechanism and no way to find an order by our reference, so the id it answers with is
 * the *only* handle that order will ever have. It is therefore written in a transaction of its own,
 * before the confirmation is even attempted: a crash between creation and confirmation then costs a
 * confirm call on the next scan, not an untraceable order.
 */
internal class SpodOrderRepository(
    private val database: Database,
    private val emailOutbox: EmailOutbox,
) {
    /**
     * The print-on-demand jobs the submission stage still has to prepare, in ascending id order.
     *
     * Three filters make the list: the job's snapshotted channel (an SFTP job has no remote order),
     * its `prepared_at` (a confirmed job is done), and the creation state — a job quarantined as
     * `OUTCOME_UNKNOWN` is deliberately left out, because the whole point of the quarantine is that
     * no further automatic call may be made until a human has looked at the partner's backoffice.
     */
    suspend fun openJobs(): List<OpenSpodJob> = database.read {
        ProductionJobs.join(
                ProductionRequests,
                JoinType.INNER,
                onColumn = ProductionJobs.requestId,
                otherColumn = ProductionRequests.id,
            )
            .join(
                ProductionSpodOrders,
                JoinType.LEFT,
                onColumn = ProductionJobs.id,
                otherColumn = ProductionSpodOrders.productionJobId,
            )
            .select(
                ProductionJobs.id,
                ProductionRequests.orderId,
                ProductionJobs.supplierId,
                ProductionSpodOrders.createState,
                ProductionSpodOrders.externalReference,
                ProductionSpodOrders.attemptCount,
            )
            .where {
                // `<> 'OUTCOME_UNKNOWN'` alone would drop the job that has no order row yet, so
                // the never-attempted case is spelled out: SQL comparisons with NULL are unknown.
                val notQuarantined =
                    ProductionSpodOrders.createState.isNull() or
                        (ProductionSpodOrders.createState neq SpodCreateStates.OUTCOME_UNKNOWN)
                (ProductionJobs.fulfillmentChannel eq ProductionChannels.SPOD) and
                    ProductionJobs.preparedAt.isNull() and
                    notQuarantined
            }
            .orderBy(ProductionJobs.id to SortOrder.ASC)
            .map { row ->
                OpenSpodJob(
                    id = row[ProductionJobs.id],
                    orderId = row[ProductionRequests.orderId],
                    supplierId = row[ProductionJobs.supplierId],
                    createState =
                        row.getOrNull(ProductionSpodOrders.createState) ?: SpodCreateStates.PENDING,
                    spodOrderId = row.getOrNull(ProductionSpodOrders.externalReference),
                    attemptCount = row.getOrNull(ProductionSpodOrders.attemptCount) ?: 0,
                )
            }
    }

    /**
     * Claims the job for this scan: creates its order row if this is the first attempt ever, and
     * counts the attempt. The insert ignores a duplicate, so the row is created exactly once
     * however often a scan is repeated.
     */
    suspend fun startAttempt(jobId: Long): Boolean = database.write {
        ProductionSpodOrders.insertIgnore { statement -> statement[productionJobId] = jobId }
        updateOpenOrder(jobId) { statement ->
            statement[ProductionSpodOrders.attemptCount] = ProductionSpodOrders.attemptCount + 1
        }
    }

    suspend fun recordFailure(jobId: Long, code: String): Boolean = database.write {
        updateOpenOrder(jobId) { statement -> statement[ProductionSpodOrders.lastErrorCode] = code }
    }

    /** The design ids already uploaded for this job, by the item position they belong to. */
    suspend fun designs(jobId: Long): Map<Int, String> = database.read {
        ProductionSpodDesigns.select(ProductionSpodDesigns.position, ProductionSpodDesigns.designId)
            .where { ProductionSpodDesigns.productionJobId eq jobId }
            .associate { row ->
                row[ProductionSpodDesigns.position] to row[ProductionSpodDesigns.designId]
            }
    }

    /**
     * Records the design of one item position. Written per position rather than per upload, because
     * that is what a re-scan reads back to decide which uploads it may skip; two positions printing
     * the same image simply record the same design id.
     */
    suspend fun recordDesigns(jobId: Long, designIdByPosition: Map<Int, String>): Unit =
        database.write {
            designIdByPosition.forEach { (itemPosition, uploadedDesignId) ->
                ProductionSpodDesigns.insertIgnore { statement ->
                    statement[productionJobId] = jobId
                    statement[position] = itemPosition
                    statement[designId] = uploadedDesignId
                }
            }
        }

    /** The variant names this job was split with, by item position — the snapshot, not today's. */
    suspend fun itemVariantNames(jobId: Long): Map<Int, String> = database.read {
        ProductionJobItems.select(ProductionJobItems.position, ProductionJobItems.variantName)
            .where { ProductionJobItems.productionJobId eq jobId }
            .associate { row ->
                row[ProductionJobItems.position] to row[ProductionJobItems.variantName]
            }
    }

    /**
     * Persists the id the partner answered the creation with, in its own transaction and before
     * anything else happens. Guarded on `PENDING`, so a second creation can never overwrite the id
     * of an order that is already ours.
     */
    suspend fun recordCreatedOrder(jobId: Long, spodOrderId: String): Boolean = database.write {
        ProductionSpodOrders.update(
            where = {
                (ProductionSpodOrders.productionJobId eq jobId) and
                    (ProductionSpodOrders.createState eq SpodCreateStates.PENDING)
            }
        ) { statement ->
            statement[ProductionSpodOrders.externalReference] = spodOrderId
            statement[ProductionSpodOrders.createState] = SpodCreateStates.CREATED
            statement[ProductionSpodOrders.lastErrorCode] = null
        } > 0
    }

    /**
     * Counts one creation whose outcome nobody knows and answers the new count.
     *
     * The second one quarantines the job. An order created but never confirmed is inert, so the
     * first ambiguity is affordable — the next scan simply creates again and the orphan costs
     * nothing. A second one would mean two possible orphans plus a third attempt on the way, and at
     * that point the cheaper answer is to stop and let a human read the partner's backoffice.
     */
    suspend fun recordAmbiguousCreate(jobId: Long, code: String): Int = database.write {
        val count = ambiguousCount(jobId) + 1
        val quarantined = count >= MAX_AMBIGUOUS_CREATES
        updateOpenOrder(jobId) { statement ->
            statement[ProductionSpodOrders.createAmbiguousCount] = count
            statement[ProductionSpodOrders.lastErrorCode] = code
            if (quarantined) {
                statement[ProductionSpodOrders.createState] = SpodCreateStates.OUTCOME_UNKNOWN
            }
        }
        // The quarantine and the mail that reports it are one commit, for the same reason a
        // shipment and its notification are: a job nobody may retry and nobody was told about is
        // the one state this pipeline must not be able to reach.
        if (quarantined) emailOutbox.enqueue(QueuedEmailReference.SpodOpsAlert(jobId))
        count
    }

    /**
     * Records the state the partner reported for this job's order and makes sure an operator hears
     * about it — one transaction, like every other state change that ends in a mail.
     *
     * The write is unconditional and the enqueue is idempotent, which together are the whole
     * at-least-once handling of the webhook: the partner may redeliver the same event any number of
     * times, and a cancellation may be followed by a needs-action event, and the outbox's unique
     * `(kind, source_id)` rule still leaves exactly one alert mail for this job.
     *
     * `false` means this job has no remote order row at all, which is the caller's cue that the
     * reference belonged to nothing here.
     */
    suspend fun recordRemoteState(jobId: Long, state: String): Boolean = database.write {
        val updated =
            ProductionSpodOrders.update(
                where = { ProductionSpodOrders.productionJobId eq jobId }
            ) { statement ->
                statement[ProductionSpodOrders.remoteState] = state
            } > 0
        if (updated) emailOutbox.enqueue(QueuedEmailReference.SpodOpsAlert(jobId))
        updated
    }

    /**
     * The job behind the partner's own order id, or `null` when this shop knows no such order.
     *
     * It is the webhook's first lookup, and it is a unique index read: `external_reference` is
     * unique where it is set, so the partner's id can only ever name one job of this shop.
     */
    suspend fun jobIdOfExternalReference(externalReference: String): Long? = database.read {
        ProductionSpodOrders.select(ProductionSpodOrders.productionJobId)
            .where { ProductionSpodOrders.externalReference eq externalReference }
            .singleOrNull()
            ?.get(ProductionSpodOrders.productionJobId)
    }

    /**
     * Everything the ops alert mail is built from, or `null` when the job has no remote order.
     *
     * It is read freshly per send attempt, like every other queued mail's content, so an alert sent
     * after an operator already resolved the job still describes the job as it is now.
     */
    suspend fun alertContext(jobId: Long): SpodAlertContext? = database.read {
        ProductionSpodOrders.join(
                ProductionJobs,
                JoinType.INNER,
                onColumn = ProductionSpodOrders.productionJobId,
                otherColumn = ProductionJobs.id,
            )
            .join(
                ProductionRequests,
                JoinType.INNER,
                onColumn = ProductionJobs.requestId,
                otherColumn = ProductionRequests.id,
            )
            .select(
                ProductionRequests.orderId,
                ProductionSpodOrders.externalReference,
                ProductionSpodOrders.createState,
                ProductionSpodOrders.remoteState,
            )
            .where { ProductionSpodOrders.productionJobId eq jobId }
            .singleOrNull()
            ?.let { row ->
                SpodAlertContext(
                    jobId = jobId,
                    orderId = row[ProductionRequests.orderId],
                    externalReference = row[ProductionSpodOrders.externalReference],
                    createState = row[ProductionSpodOrders.createState],
                    remoteState = row[ProductionSpodOrders.remoteState],
                )
            }
    }

    /**
     * Closes the job in one transaction: the remote order is confirmed, and the job is ready to be
     * shipped. `prepared_at` is the channel-neutral column the guarded ship update reads, so this
     * single commit is this channel's counterpart of "the PDF exists" on the SFTP side.
     */
    suspend fun completeConfirmation(jobId: Long): Boolean = database.write {
        val confirmed =
            updateOpenOrder(jobId) { statement ->
                statement[ProductionSpodOrders.confirmedAt] = CurrentTimestampWithTimeZone
                statement[ProductionSpodOrders.remoteState] = SpodRemoteStates.CONFIRMED
                statement[ProductionSpodOrders.lastErrorCode] = null
            }
        if (confirmed) {
            ProductionJobs.update(
                where = { (ProductionJobs.id eq jobId) and ProductionJobs.preparedAt.isNull() }
            ) { statement ->
                statement[ProductionJobs.preparedAt] = CurrentTimestampWithTimeZone
            }
        }
        confirmed
    }

    /**
     * The print-on-demand destination of a supplier, with its access token — the one destination
     * read that carries a secret, because the submission has to authenticate.
     *
     * At most one enabled SPOD destination exists per supplier (a partial unique index enforces
     * it), so the pick is never ambiguous. A supplier with only disabled ones answers
     * [SpodDestinationLookup.Disabled], which is a different fix than having none at all.
     */
    suspend fun destination(supplierId: Long): SpodDestinationLookup = database.read {
        val rows =
            ProductionDestinations.join(
                    ProductionDestinationSpod,
                    JoinType.INNER,
                    onColumn = ProductionDestinations.id,
                    otherColumn = ProductionDestinationSpod.id,
                )
                .selectAll()
                .where {
                    (ProductionDestinations.supplierId eq supplierId) and
                        (ProductionDestinations.channel eq ProductionChannels.SPOD)
                }
                .orderBy(ProductionDestinations.id to SortOrder.ASC)
                .toList()
        val enabled = rows.firstOrNull { row -> row[ProductionDestinations.enabled] }
        val environment = enabled?.let { row ->
            SpodEnvironment.ofStoredValue(row[ProductionDestinationSpod.environment])
        }
        when {
            rows.isEmpty() -> SpodDestinationLookup.Missing
            enabled == null -> SpodDestinationLookup.Disabled
            environment == null -> SpodDestinationLookup.Missing
            else ->
                SpodDestinationLookup.Found(
                    ProductionDeliveryDestination.Spod(
                        id = enabled[ProductionDestinations.id].value,
                        enabled = true,
                        environment = environment,
                        accessToken = enabled[ProductionDestinationSpod.accessToken],
                        timeoutSeconds = enabled[ProductionDestinationSpod.timeoutSeconds],
                    )
                )
        }
    }

    private fun ambiguousCount(jobId: Long): Int =
        ProductionSpodOrders.select(ProductionSpodOrders.createAmbiguousCount)
            .where { ProductionSpodOrders.productionJobId eq jobId }
            .singleOrNull()
            ?.get(ProductionSpodOrders.createAmbiguousCount) ?: 0

    /**
     * Updates the job's order row and reports whether it existed. Runs inside the caller's
     * transaction, so [completeConfirmation] can pair it with the job's `prepared_at` in one
     * commit.
     *
     * It needs no "still open" guard of its own: [openJobs] hands out unprepared jobs only, and the
     * one write that closes a job — the `prepared_at` update in [completeConfirmation] — carries
     * the guard itself.
     */
    private fun updateOpenOrder(
        jobId: Long,
        body: ProductionSpodOrders.(UpdateStatement) -> Unit,
    ): Boolean =
        ProductionSpodOrders.update(
            where = { ProductionSpodOrders.productionJobId eq jobId },
            body = body,
        ) > 0

    private companion object {
        /** One automatic re-create is allowed; the second ambiguity quarantines the job. */
        const val MAX_AMBIGUOUS_CREATES = 2
    }
}

/** The values the `create_state` check constraint allows. */
internal object SpodCreateStates {
    const val PENDING: String = "PENDING"
    const val CREATED: String = "CREATED"
    const val OUTCOME_UNKNOWN: String = "OUTCOME_UNKNOWN"
}

/**
 * The values the `remote_state` check constraint allows: the confirmation this backend performs,
 * and the two the partner reports through the webhook.
 */
internal object SpodRemoteStates {
    const val CONFIRMED: String = "CONFIRMED"
    const val NEEDS_ACTION: String = "NEEDS_ACTION"
    const val CANCELLED: String = "CANCELLED"
}

/** What the ops alert mail of one job is built from, read per send attempt. */
internal data class SpodAlertContext(
    val jobId: Long,
    val orderId: Long,
    val externalReference: String?,
    val createState: String,
    val remoteState: String?,
)

internal object ProductionSpodOrders : Table("production_spod_orders") {
    val productionJobId = long("production_job_id")

    /** The partner's order id — the only handle by which this order can ever be read again. */
    val externalReference = varchar("external_reference", 128).nullable()
    val createState = varchar("create_state", 32)
    val createAmbiguousCount = integer("create_ambiguous_count")
    val attemptCount = integer("attempt_count")
    val lastErrorCode = varchar("last_error_code", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val confirmedAt = timestampWithTimeZone("confirmed_at").nullable()
    val remoteState = varchar("remote_state", 32).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(productionJobId)
}

internal object ProductionSpodDesigns : Table("production_spod_designs") {
    val productionJobId = long("production_job_id")
    val position = integer("position")
    val designId = varchar("design_id", 128)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey: PrimaryKey = PrimaryKey(productionJobId, position)
}

/** One print-on-demand job as the submission stage scans it. */
internal data class OpenSpodJob(
    val id: Long,
    val orderId: Long,
    val supplierId: Long,
    /** One of [SpodCreateStates]; `PENDING` for a job that has never been attempted. */
    val createState: String,
    /** The partner's order id, present exactly when [createState] is `CREATED`. */
    val spodOrderId: String?,
    val attemptCount: Int,
)

/** Why a supplier's print-on-demand destination could not be used, or the destination itself. */
internal sealed interface SpodDestinationLookup {
    data class Found(val destination: ProductionDeliveryDestination.Spod) : SpodDestinationLookup

    data object Missing : SpodDestinationLookup

    data object Disabled : SpodDestinationLookup
}
