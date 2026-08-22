package shop.voenix.production.delivery

import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.read
import shop.voenix.db.write
import shop.voenix.email.EmailOutbox
import shop.voenix.email.QueuedEmailReference
import shop.voenix.production.spod.SpodEnvironment

/**
 * Persistence of the delivery state of production jobs.
 *
 * Delivered/open state derives from the nullable `delivered_at` timestamp, exactly like the request
 * and job repositories: there is no in-progress status to strand. Every update guards on
 * `delivered_at IS NULL`, so a delivered row is immutable. [openDeliveries] only returns deliveries
 * whose job artifact exists — a delivery can never be attempted before there are immutable bytes to
 * ship.
 *
 * [completeDelivery] holds the [emailOutbox] because "delivered + notification enqueued" must be
 * one database commit: if the destination configures a notification address, the producer email job
 * is enqueued in the very transaction that sets `delivered_at` — both happen or neither does.
 */
internal class ProductionDeliveryRepository(
    private val database: Database,
    private val emailOutbox: EmailOutbox,
) {
    internal suspend fun openDeliveries(): List<OpenProductionDelivery> = database.read {
        ProductionDeliveries.join(
                ProductionJobs,
                JoinType.INNER,
                onColumn = ProductionDeliveries.productionJobId,
                otherColumn = ProductionJobs.id,
            )
            .select(
                ProductionDeliveries.id,
                ProductionDeliveries.productionJobId,
                ProductionDeliveries.destinationId,
                ProductionJobs.fileName,
                ProductionJobs.contentSha256,
                ProductionDeliveries.attemptCount,
            )
            .where {
                ProductionDeliveries.deliveredAt.isNull() and ProductionJobs.generatedAt.isNotNull()
            }
            .orderBy(ProductionDeliveries.id to SortOrder.ASC)
            .map { row ->
                OpenProductionDelivery(
                    id = row[ProductionDeliveries.id],
                    jobId = row[ProductionDeliveries.productionJobId],
                    destinationId = row[ProductionDeliveries.destinationId],
                    fileName = row[ProductionJobs.fileName],
                    contentSha256 = checkNotNull(row[ProductionJobs.contentSha256]),
                    attemptCount = row[ProductionDeliveries.attemptCount],
                )
            }
    }

    /**
     * Reads the destination of a delivery — the only destination read that includes the channel's
     * secret, because the adapter must authenticate. See [ProductionDeliveryDestination].
     *
     * The base row names the channel, and the detail table of that channel holds the rest. A base
     * row without its detail row answers `null`, which the worker treats as a missing destination:
     * a retryable bounded code, never an exception on the delivery path.
     *
     * That channel is always SFTP: a `production_deliveries` row is only ever created for an SFTP
     * job (print-on-demand jobs go through `SpodOrderSubmitter` instead), and a destination's
     * channel cannot change after it was created. Any other channel here is a broken database.
     */
    internal suspend fun destination(destinationId: Long): ProductionDeliveryDestination? =
        database.read {
            val base =
                ProductionDestinations.select(
                        ProductionDestinations.id,
                        ProductionDestinations.channel,
                        ProductionDestinations.enabled,
                    )
                    .where { ProductionDestinations.id eq destinationId }
                    .singleOrNull() ?: return@read null
            val enabled = base[ProductionDestinations.enabled]
            when (val channel = base[ProductionDestinations.channel]) {
                ProductionChannels.SFTP -> sftpDestination(destinationId, enabled)
                else -> error("Delivery destination $destinationId has channel $channel")
            }
        }

    private fun sftpDestination(id: Long, enabled: Boolean): ProductionDeliveryDestination.Sftp? =
        ProductionDestinationSftp.selectAll()
            .where { ProductionDestinationSftp.id eq id }
            .singleOrNull()
            ?.let { row ->
                ProductionDeliveryDestination.Sftp(
                    id = id,
                    enabled = enabled,
                    host = row[ProductionDestinationSftp.host],
                    port = row[ProductionDestinationSftp.port],
                    username = row[ProductionDestinationSftp.username],
                    password = row[ProductionDestinationSftp.password],
                    hostKeyFingerprint = row[ProductionDestinationSftp.hostKeyFingerprint],
                    remotePath = row[ProductionDestinationSftp.remotePath],
                    timeoutSeconds = row[ProductionDestinationSftp.timeoutSeconds],
                )
            }

    /**
     * Reads everything the producer-notification resolver needs for one delivery: the identity of
     * the delivered file plus the destination's current label and notification address. Returns
     * `null` when the delivery or its destination does not exist.
     */
    internal suspend fun notificationContext(deliveryId: Long): ProducerNotificationContext? =
        database.read {
            val delivery =
                ProductionDeliveries.join(
                        ProductionJobs,
                        JoinType.INNER,
                        onColumn = ProductionDeliveries.productionJobId,
                        otherColumn = ProductionJobs.id,
                    )
                    .join(
                        ProductionRequests,
                        JoinType.INNER,
                        onColumn = ProductionJobs.requestId,
                        otherColumn = ProductionRequests.id,
                    )
                    .select(
                        ProductionDeliveries.destinationId,
                        ProductionJobs.supplierId,
                        ProductionJobs.fileName,
                        ProductionRequests.orderId,
                    )
                    .where { ProductionDeliveries.id eq deliveryId }
                    .singleOrNull() ?: return@read null
            val destination =
                ProductionDestinations.select(
                        ProductionDestinations.label,
                        ProductionDestinations.notificationEmail,
                        ProductionDestinations.notificationName,
                    )
                    .where {
                        ProductionDestinations.id eq delivery[ProductionDeliveries.destinationId]
                    }
                    .singleOrNull() ?: return@read null
            ProducerNotificationContext(
                orderId = delivery[ProductionRequests.orderId],
                supplierId = delivery[ProductionJobs.supplierId],
                fileName = delivery[ProductionJobs.fileName],
                destinationLabel = destination[ProductionDestinations.label],
                notificationEmail = destination[ProductionDestinations.notificationEmail],
                notificationName = destination[ProductionDestinations.notificationName],
            )
        }

    internal suspend fun startAttempt(deliveryId: Long): Boolean = database.write {
        updateOpenDelivery(deliveryId) { statement ->
            statement[ProductionDeliveries.attemptCount] = ProductionDeliveries.attemptCount + 1
        }
    }

    internal suspend fun recordFailure(deliveryId: Long, code: String): Boolean = database.write {
        updateOpenDelivery(deliveryId) { statement ->
            statement[ProductionDeliveries.lastErrorCode] = code
        }
    }

    /**
     * Sets `delivered_at`, closes the delivery, and — when the destination configures a
     * notification address at this moment — enqueues the producer notification through the public
     * email outbox in the same transaction. The update only touches an open row, so the timestamp
     * of a confirmed delivery can never be overwritten by a racing attempt and the notification is
     * enqueued at most once per delivery; the email outbox additionally deduplicates by reference.
     */
    internal suspend fun completeDelivery(deliveryId: Long, destinationId: Long): Boolean =
        database.write {
            val closed =
                updateOpenDelivery(deliveryId) { statement ->
                    statement[ProductionDeliveries.deliveredAt] = CurrentTimestampWithTimeZone
                    statement[ProductionDeliveries.lastErrorCode] = null
                }
            if (closed && hasNotificationEmail(destinationId)) {
                emailOutbox.enqueue(QueuedEmailReference.ProducerPdfNotification(deliveryId))
            }
            closed
        }

    private fun hasNotificationEmail(destinationId: Long): Boolean =
        ProductionDestinations.select(ProductionDestinations.notificationEmail)
            .where { ProductionDestinations.id eq destinationId }
            .singleOrNull()
            ?.get(ProductionDestinations.notificationEmail)
            .isNullOrBlank()
            .not()

    /**
     * Updates the delivery only while it is still open and reports whether a row was touched. Runs
     * inside the caller's transaction so [completeDelivery] can pair it with the notification
     * enqueue in one commit.
     */
    private fun updateOpenDelivery(
        deliveryId: Long,
        body: ProductionDeliveries.(UpdateStatement) -> Unit,
    ): Boolean =
        ProductionDeliveries.update(
            where = {
                (ProductionDeliveries.id eq deliveryId) and
                    ProductionDeliveries.deliveredAt.isNull()
            },
            body = body,
        ) > 0
}

internal object ProductionDeliveries : Table("production_deliveries") {
    val id = long("id").autoIncrement()
    val productionJobId = long("production_job_id")
    val destinationId = long("destination_id")
    val attemptCount = integer("attempt_count")
    val lastErrorCode = varchar("last_error_code", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val deliveredAt = timestampWithTimeZone("delivered_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/**
 * One open delivery as the worker scans it: the job's artifact identity (file name plus recorded
 * digest) and the destination to push it to. Only deliveries whose job artifact exists are scanned,
 * so [contentSha256] is always present.
 */
internal data class OpenProductionDelivery(
    val id: Long,
    val jobId: Long,
    val destinationId: Long,
    val fileName: String,
    val contentSha256: String,
    val attemptCount: Int,
)

/**
 * A destination as the delivery stage reads it — the only read model that carries a channel's
 * secret, because an adapter must authenticate. It exists solely on the worker path: it is never
 * serialized, never returned by any API, and every variant's [toString] redacts its secret so an
 * accidental log statement cannot leak it.
 *
 * One variant per channel, mirroring the detail tables: what an SFTP adapter needs is not what a
 * SPOD adapter needs, and neither can be handed the other's configuration.
 */
internal sealed interface ProductionDeliveryDestination {
    val id: Long
    val channel: String
    val enabled: Boolean

    data class Sftp(
        override val id: Long,
        override val enabled: Boolean,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val hostKeyFingerprint: String,
        val remotePath: String,
        val timeoutSeconds: Int,
    ) : ProductionDeliveryDestination {
        override val channel: String
            get() = ProductionChannels.SFTP

        override fun toString(): String =
            "ProductionDeliveryDestination.Sftp(id=$id, enabled=$enabled, host=$host, " +
                "port=$port, username=$username, password=[redacted], " +
                "hostKeyFingerprint=$hostKeyFingerprint, remotePath=$remotePath, " +
                "timeoutSeconds=$timeoutSeconds)"
    }

    data class Spod(
        override val id: Long,
        override val enabled: Boolean,
        val environment: SpodEnvironment,
        val accessToken: String,
        val timeoutSeconds: Int,
    ) : ProductionDeliveryDestination {
        override val channel: String
            get() = ProductionChannels.SPOD

        override fun toString(): String =
            "ProductionDeliveryDestination.Spod(id=$id, enabled=$enabled, " +
                "environment=$environment, accessToken=[redacted], " +
                "timeoutSeconds=$timeoutSeconds)"
    }
}

/**
 * Notification values of one delivery, read together in
 * [ProductionDeliveryRepository.notificationContext].
 */
internal data class ProducerNotificationContext(
    val orderId: Long,
    val supplierId: Long,
    val fileName: String,
    val destinationLabel: String,
    val notificationEmail: String?,
    val notificationName: String?,
)
