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
import shop.voenix.article.tshirt.SpodCatalogSource
import shop.voenix.db.executePostgresWrite
import shop.voenix.db.read
import shop.voenix.db.write
import shop.voenix.spod.SpodAccess
import shop.voenix.spod.SpodEnvironment

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

    /**
     * What a t-shirt sync of this destination reads with: the supplier its shirts belong to and the
     * SPOD access of the row, access token included. It is the second destination read that carries
     * a secret — a sync has to authenticate, exactly like the order submission does — which is why
     * it selects the token column the other reads deliberately leave out.
     *
     * `enabled` is not part of the query: a disabled destination may still sync (ADR 0003, decision
     * 4). Only the channel is checked, and a destination that has no catalog answers
     * [ProductionDestinationCatalogSource.NotSyncable] rather than nothing at all, so the caller
     * can tell it apart from an unknown id.
     */
    internal suspend fun catalogSource(id: Long): ProductionDestinationCatalogSource =
        database.read {
            val row =
                withDetails()
                    .select(
                        ProductionDestinations.supplierId,
                        ProductionDestinations.channel,
                        ProductionDestinationSpod.environment,
                        ProductionDestinationSpod.accessToken,
                        ProductionDestinationSpod.timeoutSeconds,
                    )
                    .where { ProductionDestinations.id eq id }
                    .singleOrNull() ?: return@read ProductionDestinationCatalogSource.NotFound
            if (row[ProductionDestinations.channel] != ProductionChannels.SPOD) {
                return@read ProductionDestinationCatalogSource.NotSyncable
            }
            ProductionDestinationCatalogSource.Found(
                SpodCatalogSource(
                    supplierId = row[ProductionDestinations.supplierId],
                    access =
                        SpodAccess(
                            destinationId = id,
                            environment =
                                SpodEnvironment.ofStoredValue(
                                    row[ProductionDestinationSpod.environment]
                                ),
                            accessToken = row[ProductionDestinationSpod.accessToken],
                            timeoutSeconds = row[ProductionDestinationSpod.timeoutSeconds],
                        ),
                )
            )
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
     * A `null` [newSecret] keeps the stored one. The channel is not replaceable: a body that names
     * a different one answers [ProductionDestinationWriteResult.ChannelImmutable].
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
     * must exist, and it must still be the channel it was stored with. Only then does the
     * transaction touch a row, so no half-written destination can ever be committed.
     */
    private fun updateInTransaction(
        id: Long,
        write: ProductionDestinationWrite,
        newSecret: String?,
    ): ProductionDestinationWriteResult {
        val stored = findInTransaction(id) ?: return ProductionDestinationWriteResult.NotFound
        if (write.channel != stored.channel) {
            return ProductionDestinationWriteResult.ChannelImmutable
        }
        if (write.supplierId != stored.supplierId) {
            return ProductionDestinationWriteResult.SupplierImmutable
        }

        ProductionDestinations.update({ ProductionDestinations.id eq id }) { statement ->
            statement.copyFrom(write)
            statement[ProductionDestinations.updatedAt] = CurrentTimestampWithTimeZone
        }
        updateDetail(id, write.detail, newSecret)
        return ProductionDestinationWriteResult.Stored(checkNotNull(findInTransaction(id)))
    }

    private fun insertDetail(
        id: Long,
        detail: ProductionDestinationDetail,
        secret: String,
    ) {
        when (detail) {
            is ProductionDestinationDetail.Sftp ->
                ProductionDestinationSftp.insert { statement ->
                    statement[ProductionDestinationSftp.id] = id
                    statement[ProductionDestinationSftp.channel] = detail.channel
                    statement.copyFrom(detail)
                    statement[ProductionDestinationSftp.password] = secret
                }
            is ProductionDestinationDetail.Spod ->
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
        detail: ProductionDestinationDetail,
        newSecret: String?,
    ) {
        when (detail) {
            is ProductionDestinationDetail.Sftp ->
                ProductionDestinationSftp.update({ ProductionDestinationSftp.id eq id }) { statement
                    ->
                    statement.copyFrom(detail)
                    newSecret?.let { value ->
                        statement[ProductionDestinationSftp.password] = value
                    }
                }
            is ProductionDestinationDetail.Spod ->
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

private fun UpdateBuilder<*>.copyFrom(detail: ProductionDestinationDetail.Sftp) {
    this[ProductionDestinationSftp.host] = detail.host
    this[ProductionDestinationSftp.port] = detail.port
    this[ProductionDestinationSftp.username] = detail.username
    this[ProductionDestinationSftp.hostKeyFingerprint] = detail.hostKeyFingerprint
    this[ProductionDestinationSftp.remotePath] = detail.remotePath
    this[ProductionDestinationSftp.timeoutSeconds] = detail.timeoutSeconds
}

private fun UpdateBuilder<*>.copyFrom(detail: ProductionDestinationDetail.Spod) {
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
private fun ResultRow.toDetail(): ProductionDestinationDetail {
    val channel = this[ProductionDestinations.channel]
    return when (channel) {
        ProductionChannels.SFTP ->
            ProductionDestinationDetail.Sftp(
                host = requireDetail(channel, getOrNull(ProductionDestinationSftp.host)),
                port = this[ProductionDestinationSftp.port],
                username = this[ProductionDestinationSftp.username],
                hostKeyFingerprint = this[ProductionDestinationSftp.hostKeyFingerprint],
                remotePath = this[ProductionDestinationSftp.remotePath],
                timeoutSeconds = this[ProductionDestinationSftp.timeoutSeconds],
            )
        ProductionChannels.SPOD ->
            ProductionDestinationDetail.Spod(
                environment =
                    SpodEnvironment.ofStoredValue(
                        requireDetail(channel, getOrNull(ProductionDestinationSpod.environment))
                    ),
                timeoutSeconds = this[ProductionDestinationSpod.timeoutSeconds],
            )
        else -> error("Unknown production destination channel $channel")
    }
}

private fun <T : Any> requireDetail(channel: String, value: T?): T =
    checkNotNull(value) { "Production destination row without its $channel detail row" }

/**
 * A destination as read from the database: the shared fields plus the [detail] of its channel. No
 * secret is present, for the reason [ProductionDestinationDetail] gives.
 */
internal data class StoredProductionDestination(
    val id: Long,
    val supplierId: Long,
    val label: String,
    val enabled: Boolean,
    val notificationEmail: String?,
    val notificationName: String?,
    val detail: ProductionDestinationDetail,
) {
    val channel: String
        get() = detail.channel
}

/**
 * The channel-shaped half of a destination, secret excluded — the same shape on the way in and on
 * the way out.
 *
 * Reads never select the password or access-token column, and on a write the channel's secret
 * travels as a separate argument of [ProductionDestinationRepository.insert] and
 * [ProductionDestinationRepository.update]. That way neither direction can carry a secret into a
 * response, a log line, or an error message, and the two write calls express by type when a secret
 * is required (create) and when it is optional (replace).
 */
internal sealed interface ProductionDestinationDetail {
    val channel: String

    data class Sftp(
        val host: String,
        val port: Int,
        val username: String,
        val hostKeyFingerprint: String,
        val remotePath: String,
        val timeoutSeconds: Int,
    ) : ProductionDestinationDetail {
        override val channel: String
            get() = ProductionChannels.SFTP
    }

    data class Spod(val environment: SpodEnvironment, val timeoutSeconds: Int) :
        ProductionDestinationDetail {
        override val channel: String
            get() = ProductionChannels.SPOD
    }
}

/**
 * Everything an admin write stores in a destination, already validated and normalized by
 * [shop.voenix.production.ProductionDestinationService]: the base row plus the [detail] of its
 * channel. The channel's secret is deliberately not a property here, for the reason
 * [ProductionDestinationDetail] gives.
 */
internal data class ProductionDestinationWrite(
    val supplierId: Long,
    val label: String,
    val enabled: Boolean,
    val notificationEmail: String?,
    val notificationName: String?,
    val detail: ProductionDestinationDetail,
) {
    val channel: String
        get() = detail.channel
}

internal sealed interface ProductionDestinationWriteResult {
    data class Stored(val destination: StoredProductionDestination) :
        ProductionDestinationWriteResult

    data object NotFound : ProductionDestinationWriteResult

    data object SupplierNotFound : ProductionDestinationWriteResult

    /** The supplier already has an enabled SPOD destination; the partial unique index refused. */
    data object EnabledSpodExists : ProductionDestinationWriteResult

    /**
     * The replace names a channel other than the stored one. A destination's channel is fixed at
     * creation: open `production_deliveries` rows point at it, and nothing would invalidate them.
     */
    data object ChannelImmutable : ProductionDestinationWriteResult

    /**
     * The replace names a different supplier. A destination belongs to the supplier it was created
     * for: its open `production_deliveries` rows and — since ADR 0003 — every t-shirt a sync of it
     * created carry that supplier, and moving the destination would leave all of them behind.
     */
    data object SupplierImmutable : ProductionDestinationWriteResult
}

/** The catalog source of a destination, or why it has none. */
internal sealed interface ProductionDestinationCatalogSource {
    data class Found(val source: SpodCatalogSource) : ProductionDestinationCatalogSource

    data object NotFound : ProductionDestinationCatalogSource

    /** The destination exists but is not a print-on-demand one, so there is no catalog to read. */
    data object NotSyncable : ProductionDestinationCatalogSource
}

internal sealed interface ProductionDestinationDeleteResult {
    data object Deleted : ProductionDestinationDeleteResult

    data object NotFound : ProductionDestinationDeleteResult

    data object InUse : ProductionDestinationDeleteResult
}
