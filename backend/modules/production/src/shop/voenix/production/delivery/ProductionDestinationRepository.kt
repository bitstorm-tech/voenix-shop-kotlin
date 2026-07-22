package shop.voenix.production.delivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite
import shop.voenix.production.ProductionDestinationInput

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

    internal suspend fun insert(
        input: ProductionDestinationInput
    ): ProductionDestinationWriteResult =
        executePostgresWrite(
            foreignKeyViolation = ProductionDestinationWriteResult.SupplierNotFound
        ) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    val id =
                        ProductionDestinations.insertAndGetId { statement ->
                                statement.copyFrom(input)
                                statement[password] = checkNotNull(input.password)
                            }
                            .value
                    ProductionDestinationWriteResult.Stored(checkNotNull(findInTransaction(id)))
                }
            }
        }

    internal suspend fun update(
        id: Long,
        input: ProductionDestinationInput,
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
                            statement.copyFrom(input)
                            input.password?.let { newPassword ->
                                statement[ProductionDestinations.password] = newPassword
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

    private fun UpdateBuilder<*>.copyFrom(input: ProductionDestinationInput) {
        this[ProductionDestinations.supplierId] = checkNotNull(input.supplierId)
        this[ProductionDestinations.channel] = checkNotNull(input.channel)
        this[ProductionDestinations.label] = checkNotNull(input.label)
        this[ProductionDestinations.enabled] = checkNotNull(input.enabled)
        this[ProductionDestinations.host] = checkNotNull(input.host)
        this[ProductionDestinations.port] = checkNotNull(input.port)
        this[ProductionDestinations.username] = checkNotNull(input.username)
        this[ProductionDestinations.hostKeyFingerprint] = checkNotNull(input.hostKeyFingerprint)
        this[ProductionDestinations.remotePath] = checkNotNull(input.remotePath)
        this[ProductionDestinations.timeoutSeconds] = checkNotNull(input.timeoutSeconds)
        this[ProductionDestinations.notificationEmail] = input.notificationEmail
        this[ProductionDestinations.notificationName] = input.notificationName
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
