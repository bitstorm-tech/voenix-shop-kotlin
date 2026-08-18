package shop.voenix.production.delivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite

internal class ProductionDestinationRepository(private val database: Database) {
    internal suspend fun list(): List<StoredProductionDestination> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                ProductionDestinations.select(visibleColumns)
                    .orderBy(
                        ProductionDestinations.supplierId to SortOrder.ASC,
                        ProductionDestinations.id to SortOrder.ASC,
                    )
                    .map(::toStoredDestination)
            }
        }

    internal suspend fun find(id: Long): StoredProductionDestination? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                findInTransaction(id)
            }
        }

    /** Stores a new destination. The password is a separate argument: creating one requires it. */
    internal suspend fun insert(
        write: ProductionDestinationWrite,
        password: String,
    ): ProductionDestinationWriteResult =
        executePostgresWrite(
            foreignKeyViolation = ProductionDestinationWriteResult.SupplierNotFound
        ) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    val id =
                        ProductionDestinations.insertAndGetId { statement ->
                                statement.copyFrom(write)
                                // Qualified: the `password` parameter shadows the column here.
                                statement[ProductionDestinations.password] = password
                            }
                            .value
                    ProductionDestinationWriteResult.Stored(checkNotNull(findInTransaction(id)))
                }
            }
        }

    /** Replaces a destination. A `null` [newPassword] keeps the stored one. */
    internal suspend fun update(
        id: Long,
        write: ProductionDestinationWrite,
        newPassword: String?,
    ): ProductionDestinationWriteResult =
        executePostgresWrite(
            foreignKeyViolation = ProductionDestinationWriteResult.SupplierNotFound
        ) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    val updated =
                        ProductionDestinations.update({ ProductionDestinations.id eq id }) {
                            statement ->
                            statement.copyFrom(write)
                            newPassword?.let { value ->
                                statement[ProductionDestinations.password] = value
                            }
                            statement[ProductionDestinations.updatedAt] =
                                CurrentTimestampWithTimeZone
                        }
                    if (updated == 0) {
                        ProductionDestinationWriteResult.NotFound
                    } else {
                        ProductionDestinationWriteResult.Stored(checkNotNull(findInTransaction(id)))
                    }
                }
            }
        }

    internal suspend fun delete(id: Long): ProductionDestinationDeleteResult =
        executePostgresWrite(foreignKeyViolation = ProductionDestinationDeleteResult.InUse) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    val deleted = ProductionDestinations.deleteWhere {
                        ProductionDestinations.id eq id
                    }
                    if (deleted == 0) {
                        ProductionDestinationDeleteResult.NotFound
                    } else {
                        ProductionDestinationDeleteResult.Deleted
                    }
                }
            }
        }

    private fun findInTransaction(id: Long): StoredProductionDestination? =
        ProductionDestinations.select(visibleColumns)
            .where { ProductionDestinations.id eq id }
            .singleOrNull()
            ?.let(::toStoredDestination)

    private fun toStoredDestination(row: ResultRow): StoredProductionDestination =
        StoredProductionDestination(
            id = row[ProductionDestinations.id].value,
            supplierId = row[ProductionDestinations.supplierId],
            channel = row[ProductionDestinations.channel],
            label = row[ProductionDestinations.label],
            enabled = row[ProductionDestinations.enabled],
            host = row[ProductionDestinations.host],
            port = row[ProductionDestinations.port],
            username = row[ProductionDestinations.username],
            hostKeyFingerprint = row[ProductionDestinations.hostKeyFingerprint],
            remotePath = row[ProductionDestinations.remotePath],
            timeoutSeconds = row[ProductionDestinations.timeoutSeconds],
            notificationEmail = row[ProductionDestinations.notificationEmail],
            notificationName = row[ProductionDestinations.notificationName],
        )

    private fun UpdateBuilder<*>.copyFrom(write: ProductionDestinationWrite) {
        this[ProductionDestinations.supplierId] = write.supplierId
        this[ProductionDestinations.channel] = write.channel
        this[ProductionDestinations.label] = write.label
        this[ProductionDestinations.enabled] = write.enabled
        this[ProductionDestinations.host] = write.host
        this[ProductionDestinations.port] = write.port
        this[ProductionDestinations.username] = write.username
        this[ProductionDestinations.hostKeyFingerprint] = write.hostKeyFingerprint
        this[ProductionDestinations.remotePath] = write.remotePath
        this[ProductionDestinations.timeoutSeconds] = write.timeoutSeconds
        this[ProductionDestinations.notificationEmail] = write.notificationEmail
        this[ProductionDestinations.notificationName] = write.notificationName
    }

    private companion object {
        /** Every readable column — the password column is deliberately never selected. */
        val visibleColumns: List<Expression<*>> =
            listOf(
                ProductionDestinations.id,
                ProductionDestinations.supplierId,
                ProductionDestinations.channel,
                ProductionDestinations.label,
                ProductionDestinations.enabled,
                ProductionDestinations.host,
                ProductionDestinations.port,
                ProductionDestinations.username,
                ProductionDestinations.hostKeyFingerprint,
                ProductionDestinations.remotePath,
                ProductionDestinations.timeoutSeconds,
                ProductionDestinations.notificationEmail,
                ProductionDestinations.notificationName,
            )
    }
}

internal object ProductionDestinations : LongIdTable("production_destinations") {
    val supplierId = long("supplier_id")
    val channel = varchar("channel", length = 32)
    val label = varchar("label", length = 255)
    val enabled = bool("enabled")
    val host = varchar("host", length = 255)
    val port = integer("port")
    val username = varchar("username", length = 255)
    val password = varchar("password", length = 255)
    val hostKeyFingerprint = varchar("host_key_fingerprint", length = 255)
    val remotePath = varchar("remote_path", length = 1024)
    val timeoutSeconds = integer("timeout_seconds")
    val notificationEmail = varchar("notification_email", length = 255).nullable()
    val notificationName = varchar("notification_name", length = 255).nullable()
    val updatedAt = timestampWithTimeZone("updated_at")
}

/**
 * A destination row as read from the database.
 *
 * The SFTP password is intentionally absent: reads never select the password column, so it can
 * never leak into responses, logs, or error messages.
 */
internal data class StoredProductionDestination(
    val id: Long,
    val supplierId: Long,
    val channel: String,
    val label: String,
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String,
    val hostKeyFingerprint: String,
    val remotePath: String,
    val timeoutSeconds: Int,
    val notificationEmail: String?,
    val notificationName: String?,
)

/**
 * Everything an admin write stores in a destination row, already validated and normalized by
 * [shop.voenix.production.ProductionDestinationService].
 *
 * The SFTP password is deliberately **not** a property here: it travels as a separate argument of
 * [ProductionDestinationRepository.insert] and [ProductionDestinationRepository.update]. That way
 * the write model can never carry a secret into a log line, and the two calls express by type when
 * a password is required (create) and when it is optional (replace).
 */
internal data class ProductionDestinationWrite(
    val supplierId: Long,
    val channel: String,
    val label: String,
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String,
    val hostKeyFingerprint: String,
    val remotePath: String,
    val timeoutSeconds: Int,
    val notificationEmail: String?,
    val notificationName: String?,
)

internal sealed interface ProductionDestinationWriteResult {
    data class Stored(val destination: StoredProductionDestination) :
        ProductionDestinationWriteResult

    data object NotFound : ProductionDestinationWriteResult

    data object SupplierNotFound : ProductionDestinationWriteResult
}

internal sealed interface ProductionDestinationDeleteResult {
    data object Deleted : ProductionDestinationDeleteResult

    data object NotFound : ProductionDestinationDeleteResult

    data object InUse : ProductionDestinationDeleteResult
}
