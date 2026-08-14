package shop.voenix.vat

import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite

internal class VatRepository(private val database: Database) : VatReader {
    override suspend fun list(): List<Vat> = read {
        ValueAddedTaxes.selectAll()
            .orderBy(
                ValueAddedTaxes.name to SortOrder.ASC,
                ValueAddedTaxes.id to SortOrder.ASC,
            )
            .map(::toVat)
    }

    internal suspend fun findById(id: Long): Vat? = read { findInTransaction(id) }

    override suspend fun find(ids: Set<Long>): Map<Long, Vat> {
        if (ids.isEmpty()) return emptyMap()
        return read {
            ValueAddedTaxes.selectAll()
                .where { ValueAddedTaxes.id inList ids }
                .associate { row -> row[ValueAddedTaxes.id].value to toVat(row) }
        }
    }

    internal suspend fun insert(write: VatWrite): VatWriteResult =
        executePostgresWrite(uniqueViolation = VatWriteResult.Conflict) {
            serializableTransaction {
                if (write.isDefault) demoteCurrentDefault()
                val id =
                    ValueAddedTaxes.insertAndGetId {
                            it[ValueAddedTaxes.name] = write.name
                            it[ValueAddedTaxes.percent] = write.percent
                            it[ValueAddedTaxes.description] = write.description
                            it[ValueAddedTaxes.isDefault] = write.isDefault
                        }
                        .value
                VatWriteResult.Stored(write.toVat(id))
            }
        }

    internal suspend fun update(
        id: Long,
        write: VatWrite,
    ): VatWriteResult =
        executePostgresWrite(uniqueViolation = VatWriteResult.Conflict) {
            serializableTransaction {
                if (findInTransaction(id) == null) {
                    return@serializableTransaction VatWriteResult.NotFound
                }
                if (write.isDefault) demoteCurrentDefault(exceptId = id)
                ValueAddedTaxes.update({ ValueAddedTaxes.id eq id }) {
                    it[ValueAddedTaxes.name] = write.name
                    it[ValueAddedTaxes.percent] = write.percent
                    it[ValueAddedTaxes.description] = write.description
                    it[ValueAddedTaxes.isDefault] = write.isDefault
                }
                VatWriteResult.Stored(write.toVat(id))
            }
        }

    internal suspend fun delete(id: Long): VatDeleteResult =
        executePostgresWrite(foreignKeyViolation = VatDeleteResult.InUse) {
            write {
                if (ValueAddedTaxes.deleteWhere { ValueAddedTaxes.id eq id } == 0) {
                    VatDeleteResult.NotFound
                } else {
                    VatDeleteResult.Deleted
                }
            }
        }

    private fun findInTransaction(id: Long): Vat? =
        ValueAddedTaxes.selectAll().where { ValueAddedTaxes.id eq id }.singleOrNull()?.let(::toVat)

    private fun demoteCurrentDefault(exceptId: Long? = null) {
        ValueAddedTaxes.update({
            if (exceptId == null) {
                ValueAddedTaxes.isDefault eq true
            } else {
                (ValueAddedTaxes.isDefault eq true) and (ValueAddedTaxes.id neq exceptId)
            }
        }) {
            it[ValueAddedTaxes.isDefault] = false
        }
    }

    private fun toVat(row: ResultRow): Vat =
        Vat(
            id = row[ValueAddedTaxes.id].value,
            name = row[ValueAddedTaxes.name],
            percent = row[ValueAddedTaxes.percent],
            description = row[ValueAddedTaxes.description],
            isDefault = row[ValueAddedTaxes.isDefault],
        )

    private fun VatWrite.toVat(id: Long): Vat =
        Vat(
            id = id,
            name = name,
            percent = percent,
            description = description,
            isDefault = isDefault,
        )

    private suspend fun <T> read(operation: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                operation()
            }
        }

    private suspend fun <T> write(operation: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                operation()
            }
        }

    /**
     * The default-isolation helpers above are not enough for `insert`/`update`: those hold the
     * "only one default VAT" rule, so they run at serializable isolation and may be retried.
     */
    private suspend fun <T> serializableTransaction(statement: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(
                db = database,
                transactionIsolation = Connection.TRANSACTION_SERIALIZABLE,
            ) {
                maxAttempts = MAXIMUM_TRANSACTION_ATTEMPTS
                statement()
            }
        }

    private companion object {
        const val MAXIMUM_TRANSACTION_ATTEMPTS = 3
    }
}

internal object ValueAddedTaxes : LongIdTable("value_added_taxes") {
    val name = varchar("name", length = 255)
    val percent = integer("percent")
    val description = text("description").nullable()
    val isDefault = bool("is_default")
}

internal data class VatWrite(
    val name: String,
    val percent: Int,
    val description: String?,
    val isDefault: Boolean,
)

internal sealed interface VatWriteResult {
    data class Stored(val vat: Vat) : VatWriteResult

    data object NotFound : VatWriteResult

    data object Conflict : VatWriteResult
}

internal sealed interface VatDeleteResult {
    data object Deleted : VatDeleteResult

    data object NotFound : VatDeleteResult

    data object InUse : VatDeleteResult
}
