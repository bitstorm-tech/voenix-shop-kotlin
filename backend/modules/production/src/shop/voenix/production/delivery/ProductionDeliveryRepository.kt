package shop.voenix.production.delivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Persistence of the delivery state of production jobs.
 *
 * Delivered/open state derives from the nullable `delivered_at` timestamp, exactly like the request
 * and job repositories: there is no in-progress status to strand. Every update guards on
 * `delivered_at IS NULL`, so a delivered row is immutable. [openDeliveries] only returns deliveries
 * whose job artifact exists — a delivery can never be attempted before there are immutable bytes to
 * ship.
 */
internal class ProductionDeliveryRepository(private val database: Database) {
    internal suspend fun openDeliveries(): List<OpenProductionDelivery> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
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
                        ProductionDeliveries.deliveredAt.isNull() and
                            ProductionJobs.generatedAt.isNotNull()
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
        }

    /**
     * Reads the destination of a delivery — the only destination read that includes the password,
     * because the adapter must authenticate. See [ProductionDeliveryDestination].
     */
    internal suspend fun destination(destinationId: Long): ProductionDeliveryDestination? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                ProductionDestinations.select(
                        ProductionDestinations.id,
                        ProductionDestinations.channel,
                        ProductionDestinations.enabled,
                        ProductionDestinations.host,
                        ProductionDestinations.port,
                        ProductionDestinations.username,
                        ProductionDestinations.password,
                        ProductionDestinations.hostKeyFingerprint,
                        ProductionDestinations.remotePath,
                        ProductionDestinations.timeoutSeconds,
                    )
                    .where { ProductionDestinations.id eq destinationId }
                    .singleOrNull()
                    ?.let { row ->
                        ProductionDeliveryDestination(
                            id = row[ProductionDestinations.id].value,
                            channel = row[ProductionDestinations.channel],
                            enabled = row[ProductionDestinations.enabled],
                            host = row[ProductionDestinations.host],
                            port = row[ProductionDestinations.port],
                            username = row[ProductionDestinations.username],
                            password = row[ProductionDestinations.password],
                            hostKeyFingerprint = row[ProductionDestinations.hostKeyFingerprint],
                            remotePath = row[ProductionDestinations.remotePath],
                            timeoutSeconds = row[ProductionDestinations.timeoutSeconds],
                        )
                    }
            }
        }

    internal suspend fun startAttempt(deliveryId: Long): Boolean =
        updateOpenDelivery(
            """
            UPDATE production_deliveries
            SET attempt_count = attempt_count + 1
            WHERE id = $deliveryId AND delivered_at IS NULL
            RETURNING id
            """
                .trimIndent()
        )

    internal suspend fun recordFailure(deliveryId: Long, code: String): Boolean =
        updateOpenDelivery(
            """
            UPDATE production_deliveries
            SET last_error_code = '${code.sqlLiteral()}'
            WHERE id = $deliveryId AND delivered_at IS NULL
            RETURNING id
            """
                .trimIndent()
        )

    /**
     * Sets `delivered_at` and closes the delivery — only while it is still open, so the timestamp
     * of a confirmed delivery can never be overwritten by a racing attempt.
     */
    internal suspend fun completeDelivery(deliveryId: Long): Boolean =
        updateOpenDelivery(
            """
            UPDATE production_deliveries
            SET delivered_at = CURRENT_TIMESTAMP,
                last_error_code = NULL
            WHERE id = $deliveryId AND delivered_at IS NULL
            RETURNING id
            """
                .trimIndent()
        )

    private suspend fun updateOpenDelivery(sql: String): Boolean =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                exec(
                    sql,
                    explicitStatementType = StatementType.SELECT,
                ) { rows ->
                    rows.next()
                } ?: false
            }
        }
}

private fun String.sqlLiteral(): String = replace("'", "''")
