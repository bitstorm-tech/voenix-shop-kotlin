package shop.voenix.production.delivery

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite
import shop.voenix.db.read
import shop.voenix.db.write
import shop.voenix.production.spod.SpodEnvironment

/**
 * Destination persistence across the base table and the per-channel detail tables
 * (`docs/adr/0002-production-fulfillment-channels.md`, decision 3).
 *
 * A destination is always two rows: the base row with identity, supplier, channel, label, enabled,
 * and the notification fields, plus exactly one detail row in the table of its channel. Both are
 * written in one transaction, and the composite foreign key `(id, channel)` means the database
 * refuses any combination where the two disagree. Reads left-join both detail tables, so one query
 * answers with the channel-shaped [StoredProductionDestination].
 *
 * The secret of a channel — the SFTP password, the SPOD access token — is never part of the write
 * model and never selected by a read; see [ProductionDestinationWrite].
 */
internal class ProductionDestinationRepository(private val database: Database) {
    internal suspend fun list(): List<StoredProductionDestination> = database.read {
        withDetails()
            .select(visibleColumns)
            .orderBy(
                ProductionDestinations.supplierId to SortOrder.ASC,
                ProductionDestinations.id to SortOrder.ASC,
            )
            .map(::toStoredDestination)
    }

    internal suspend fun find(id: Long): StoredProductionDestination? = database.read {
        findInTransaction(id)
    }

    /** Stores a new destination. The secret is a separate argument: creating one requires it. */
    internal suspend fun insert(
        write: ProductionDestinationWrite,
        secret: String,
    ): ProductionDestinationWriteResult =
        executePostgresWrite(
            uniqueViolation = ProductionDestinationWriteResult.EnabledSpodExists,
            foreignKeyViolation = ProductionDestinationWriteResult.SupplierNotFound,
        ) {
            database.write {
                val id =
                    ProductionDestinations.insertAndGetId { statement -> statement.copyFrom(write) }
                        .value
                insertDetail(id, write.detail, secret)
                ProductionDestinationWriteResult.Stored(checkNotNull(findInTransaction(id)))
            }
        }

    /**
     * Replaces a destination, including its channel: the detail row of any other channel is removed
     * first, because the base row's channel cannot change while a detail row still pins it.
     *
     * A `null` [newSecret] keeps the stored one. That is only possible when a detail row of the
     * target channel already exists — a destination that switches its channel has no stored secret
     * for the new one and answers [ProductionDestinationWriteResult.SecretRequired].
     */
    internal suspend fun update(
        id: Long,
        write: ProductionDestinationWrite,
        newSecret: String?,
    ): ProductionDestinationWriteResult =
        executePostgresWrite(
            uniqueViolation = ProductionDestinationWriteResult.EnabledSpodExists,
            foreignKeyViolation = ProductionDestinationWriteResult.SupplierNotFound,
        ) {
            database.write { updateInTransaction(id, write, newSecret) }
        }

    internal suspend fun delete(id: Long): ProductionDestinationDeleteResult =
        executePostgresWrite(foreignKeyViolation = ProductionDestinationDeleteResult.InUse) {
            database.write {
                val deleted = ProductionDestinations.deleteWhere { ProductionDestinations.id eq id }
                if (deleted == 0) {
                    ProductionDestinationDeleteResult.NotFound
                } else {
                    ProductionDestinationDeleteResult.Deleted
                }
            }
        }

    /**
     * Both decisions the replace depends on are taken before anything is written: the destination
     * must exist, and a channel it has no detail row for needs a new secret. Only then does the
     * transaction touch a row, so no half-written destination can ever be committed.
     */
    private fun updateInTransaction(
        id: Long,
        write: ProductionDestinationWrite,
        newSecret: String?,
    ): ProductionDestinationWriteResult {
        if (!exists(id)) return ProductionDestinationWriteResult.NotFound
        val keepsDetail = detailExists(id, write.channel)
        if (!keepsDetail && newSecret == null) {
            return ProductionDestinationWriteResult.SecretRequired
        }

        deleteDetailsOfOtherChannels(id, write.channel)
        ProductionDestinations.update({ ProductionDestinations.id eq id }) { statement ->
            statement.copyFrom(write)
            statement[ProductionDestinations.updatedAt] = CurrentTimestampWithTimeZone
        }
        if (keepsDetail) {
            updateDetail(id, write.detail, newSecret)
        } else {
            insertDetail(id, write.detail, checkNotNull(newSecret))
        }
        return ProductionDestinationWriteResult.Stored(checkNotNull(findInTransaction(id)))
    }

    private fun exists(id: Long): Boolean =
        ProductionDestinations.select(ProductionDestinations.id)
            .where { ProductionDestinations.id eq id }
            .empty()
            .not()

    private fun detailExists(id: Long, channel: String): Boolean =
        when (channel) {
                ProductionChannels.SFTP ->
                    ProductionDestinationSftp.select(ProductionDestinationSftp.id).where {
                        ProductionDestinationSftp.id eq id
                    }
                else ->
                    ProductionDestinationSpod.select(ProductionDestinationSpod.id).where {
                        ProductionDestinationSpod.id eq id
                    }
            }
            .empty()
            .not()

    /** Removes the detail row a destination leaves behind when its channel changes. */
    private fun deleteDetailsOfOtherChannels(id: Long, channel: String) {
        if (channel != ProductionChannels.SFTP) {
            ProductionDestinationSftp.deleteWhere { ProductionDestinationSftp.id eq id }
        }
        if (channel != ProductionChannels.SPOD) {
            ProductionDestinationSpod.deleteWhere { ProductionDestinationSpod.id eq id }
        }
    }

    private fun insertDetail(
        id: Long,
        detail: ProductionDestinationDetailWrite,
        secret: String,
    ) {
        when (detail) {
            is ProductionDestinationDetailWrite.Sftp ->
                ProductionDestinationSftp.insert { statement ->
                    statement[ProductionDestinationSftp.id] = id
                    statement[ProductionDestinationSftp.channel] = detail.channel
                    statement.copyFrom(detail)
                    statement[ProductionDestinationSftp.password] = secret
                }
            is ProductionDestinationDetailWrite.Spod ->
                ProductionDestinationSpod.insert { statement ->
                    statement[ProductionDestinationSpod.id] = id
                    statement[ProductionDestinationSpod.channel] = detail.channel
                    statement.copyFrom(detail)
                    statement[ProductionDestinationSpod.accessToken] = secret
                }
        }
    }

    /**
     * Replaces the values of the existing detail row; a `null` [newSecret] keeps the stored one.
     */
    private fun updateDetail(
        id: Long,
        detail: ProductionDestinationDetailWrite,
        newSecret: String?,
    ) {
        when (detail) {
            is ProductionDestinationDetailWrite.Sftp ->
                ProductionDestinationSftp.update({ ProductionDestinationSftp.id eq id }) { statement
                    ->
                    statement.copyFrom(detail)
                    newSecret?.let { value ->
                        statement[ProductionDestinationSftp.password] = value
                    }
                }
            is ProductionDestinationDetailWrite.Spod ->
                ProductionDestinationSpod.update({ ProductionDestinationSpod.id eq id }) { statement
                    ->
                    statement.copyFrom(detail)
                    newSecret?.let { value ->
                        statement[ProductionDestinationSpod.accessToken] = value
                    }
                }
        }
    }

    private fun findInTransaction(id: Long): StoredProductionDestination? =
        withDetails()
            .select(visibleColumns)
            .where { ProductionDestinations.id eq id }
            .singleOrNull()
            ?.let(::toStoredDestination)

    private companion object {
        /**
         * Every readable column of the base table and both detail tables — the two secret columns
         * are deliberately never selected.
         */
        val visibleColumns: List<Expression<*>> =
            listOf(
                ProductionDestinations.id,
                ProductionDestinations.supplierId,
                ProductionDestinations.channel,
                ProductionDestinations.label,
                ProductionDestinations.enabled,
                ProductionDestinations.notificationEmail,
                ProductionDestinations.notificationName,
                ProductionDestinationSftp.host,
                ProductionDestinationSftp.port,
                ProductionDestinationSftp.username,
                ProductionDestinationSftp.hostKeyFingerprint,
                ProductionDestinationSftp.remotePath,
                ProductionDestinationSftp.timeoutSeconds,
                ProductionDestinationSpod.environment,
                ProductionDestinationSpod.timeoutSeconds,
            )
    }
}

private fun toStoredDestination(row: ResultRow): StoredProductionDestination =
    StoredProductionDestination(
        id = row[ProductionDestinations.id].value,
        supplierId = row[ProductionDestinations.supplierId],
        label = row[ProductionDestinations.label],
        enabled = row[ProductionDestinations.enabled],
        notificationEmail = row[ProductionDestinations.notificationEmail],
        notificationName = row[ProductionDestinations.notificationName],
        detail = row.toDetail(),
    )

private fun UpdateBuilder<*>.copyFrom(write: ProductionDestinationWrite) {
    this[ProductionDestinations.supplierId] = write.supplierId
    this[ProductionDestinations.channel] = write.channel
    this[ProductionDestinations.label] = write.label
    this[ProductionDestinations.enabled] = write.enabled
    this[ProductionDestinations.notificationEmail] = write.notificationEmail
    this[ProductionDestinations.notificationName] = write.notificationName
}

private fun UpdateBuilder<*>.copyFrom(detail: ProductionDestinationDetailWrite.Sftp) {
    this[ProductionDestinationSftp.host] = detail.host
    this[ProductionDestinationSftp.port] = detail.port
    this[ProductionDestinationSftp.username] = detail.username
    this[ProductionDestinationSftp.hostKeyFingerprint] = detail.hostKeyFingerprint
    this[ProductionDestinationSftp.remotePath] = detail.remotePath
    this[ProductionDestinationSftp.timeoutSeconds] = detail.timeoutSeconds
}

private fun UpdateBuilder<*>.copyFrom(detail: ProductionDestinationDetailWrite.Spod) {
    this[ProductionDestinationSpod.environment] = detail.environment.name
    this[ProductionDestinationSpod.timeoutSeconds] = detail.timeoutSeconds
}

/** The channel names the database CHECK constraints and the adapters agree on. */
internal object ProductionChannels {
    internal const val SFTP: String = "SFTP"
    internal const val SPOD: String = "SPOD"
}

internal object ProductionDestinations : LongIdTable("production_destinations") {
    val supplierId = long("supplier_id")
    val channel = varchar("channel", length = 32)
    val label = varchar("label", length = 255)
    val enabled = bool("enabled")
    val notificationEmail = varchar("notification_email", length = 255).nullable()
    val notificationName = varchar("notification_name", length = 255).nullable()
    val updatedAt = timestampWithTimeZone("updated_at")
}

internal object ProductionDestinationSftp : Table("production_destination_sftp") {
    val id = long("id")
    val channel = varchar("channel", length = 32).default(ProductionChannels.SFTP)
    val host = varchar("host", length = 255)
    val port = integer("port")
    val username = varchar("username", length = 255)
    val password = varchar("password", length = 255)
    val hostKeyFingerprint = varchar("host_key_fingerprint", length = 255)
    val remotePath = varchar("remote_path", length = 1024)
    val timeoutSeconds = integer("timeout_seconds")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

internal object ProductionDestinationSpod : Table("production_destination_spod") {
    val id = long("id")
    val channel = varchar("channel", length = 32).default(ProductionChannels.SPOD)
    val environment = varchar("environment", length = 32)
    val accessToken = varchar("access_token", length = 512)
    val timeoutSeconds = integer("timeout_seconds")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

/** The base table left-joined with both detail tables: one row per destination, either way. */
private fun withDetails(): Join =
    ProductionDestinations.join(
            ProductionDestinationSftp,
            JoinType.LEFT,
            onColumn = ProductionDestinations.id,
            otherColumn = ProductionDestinationSftp.id,
        )
        .join(
            ProductionDestinationSpod,
            JoinType.LEFT,
            onColumn = ProductionDestinations.id,
            otherColumn = ProductionDestinationSpod.id,
        )

/**
 * The detail of a joined destination row. The composite foreign key guarantees the detail row of
 * the row's channel exists, so a missing one is a broken database, not a case to handle.
 */
private fun ResultRow.toDetail(): StoredProductionDestinationDetail {
    val channel = this[ProductionDestinations.channel]
    return when (channel) {
        ProductionChannels.SFTP ->
            StoredProductionDestinationDetail.Sftp(
                host = requireDetail(channel, getOrNull(ProductionDestinationSftp.host)),
                port = this[ProductionDestinationSftp.port],
                username = this[ProductionDestinationSftp.username],
                hostKeyFingerprint = this[ProductionDestinationSftp.hostKeyFingerprint],
                remotePath = this[ProductionDestinationSftp.remotePath],
                timeoutSeconds = this[ProductionDestinationSftp.timeoutSeconds],
            )
        ProductionChannels.SPOD ->
            StoredProductionDestinationDetail.Spod(
                environment =
                    requireDetail(
                        channel,
                        getOrNull(ProductionDestinationSpod.environment)
                            ?.let(SpodEnvironment::ofStoredValue),
                    ),
                timeoutSeconds = this[ProductionDestinationSpod.timeoutSeconds],
            )
        else -> error("Unknown production destination channel $channel")
    }
}

private fun <T : Any> requireDetail(channel: String, value: T?): T =
    checkNotNull(value) { "Production destination row without its $channel detail row" }

/**
 * A destination as read from the database: the shared fields plus the [detail] of its channel.
 *
 * No secret is present: reads never select the password or access-token column, so neither can leak
 * into a response, a log line, or an error message.
 */
internal data class StoredProductionDestination(
    val id: Long,
    val supplierId: Long,
    val label: String,
    val enabled: Boolean,
    val notificationEmail: String?,
    val notificationName: String?,
    val detail: StoredProductionDestinationDetail,
) {
    val channel: String
        get() = detail.channel
}

/** The channel-shaped half of a stored destination, secrets excluded. */
internal sealed interface StoredProductionDestinationDetail {
    val channel: String

    data class Sftp(
        val host: String,
        val port: Int,
        val username: String,
        val hostKeyFingerprint: String,
        val remotePath: String,
        val timeoutSeconds: Int,
    ) : StoredProductionDestinationDetail {
        override val channel: String
            get() = ProductionChannels.SFTP
    }

    data class Spod(val environment: SpodEnvironment, val timeoutSeconds: Int) :
        StoredProductionDestinationDetail {
        override val channel: String
            get() = ProductionChannels.SPOD
    }
}

/**
 * Everything an admin write stores in a destination, already validated and normalized by
 * [shop.voenix.production.ProductionDestinationService]: the base row plus the [detail] of its
 * channel.
 *
 * The channel's secret is deliberately **not** a property here: it travels as a separate argument
 * of [ProductionDestinationRepository.insert] and [ProductionDestinationRepository.update]. That
 * way the write model can never carry a secret into a log line, and the two calls express by type
 * when a secret is required (create) and when it is optional (replace).
 */
internal data class ProductionDestinationWrite(
    val supplierId: Long,
    val label: String,
    val enabled: Boolean,
    val notificationEmail: String?,
    val notificationName: String?,
    val detail: ProductionDestinationDetailWrite,
) {
    val channel: String
        get() = detail.channel
}

/** The channel-shaped half of a write, secret excluded. */
internal sealed interface ProductionDestinationDetailWrite {
    val channel: String

    data class Sftp(
        val host: String,
        val port: Int,
        val username: String,
        val hostKeyFingerprint: String,
        val remotePath: String,
        val timeoutSeconds: Int,
    ) : ProductionDestinationDetailWrite {
        override val channel: String
            get() = ProductionChannels.SFTP
    }

    data class Spod(val environment: SpodEnvironment, val timeoutSeconds: Int) :
        ProductionDestinationDetailWrite {
        override val channel: String
            get() = ProductionChannels.SPOD
    }
}

internal sealed interface ProductionDestinationWriteResult {
    data class Stored(val destination: StoredProductionDestination) :
        ProductionDestinationWriteResult

    data object NotFound : ProductionDestinationWriteResult

    data object SupplierNotFound : ProductionDestinationWriteResult

    /** The supplier already has an enabled SPOD destination; the partial unique index refused. */
    data object EnabledSpodExists : ProductionDestinationWriteResult

    /** A replace that switches the channel has no stored secret to keep for the new one. */
    data object SecretRequired : ProductionDestinationWriteResult
}

internal sealed interface ProductionDestinationDeleteResult {
    data object Deleted : ProductionDestinationDeleteResult

    data object NotFound : ProductionDestinationDeleteResult

    data object InUse : ProductionDestinationDeleteResult
}
