package shop.voenix.supplier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite

internal class SupplierRepository(private val database: Database) : SupplierReader {
    internal suspend fun list(): List<StoredSupplier> = read {
        Suppliers.selectAll()
            .orderBy(
                Suppliers.name to SortOrder.ASC,
                Suppliers.id to SortOrder.ASC,
            )
            .map(::toStoredSupplier)
    }

    internal suspend fun findById(id: Long): StoredSupplier? = read { findInTransaction(id) }

    override suspend fun find(ids: Set<Long>): Map<Long, SupplierSummary> {
        if (ids.isEmpty()) return emptyMap()
        return read {
            Suppliers.select(Suppliers.id, Suppliers.name)
                .where { Suppliers.id inList ids }
                .associate { row ->
                    val id = row[Suppliers.id].value
                    id to SupplierSummary(id = id, name = row[Suppliers.name])
                }
        }
    }

    internal suspend fun insert(input: SupplierInput): SupplierWriteResult =
        executePostgresWrite(foreignKeyViolation = SupplierWriteResult.CountryNotFound) {
            write {
                val id = Suppliers.insertAndGetId { statement -> statement.copyFrom(input) }.value
                SupplierWriteResult.Stored(checkNotNull(findInTransaction(id)))
            }
        }

    internal suspend fun update(
        id: Long,
        input: SupplierInput,
    ): SupplierWriteResult =
        executePostgresWrite(foreignKeyViolation = SupplierWriteResult.CountryNotFound) {
            write {
                val updated =
                    Suppliers.update({ Suppliers.id eq id }) { statement ->
                        statement.copyFrom(input)
                    }
                if (updated == 0) {
                    SupplierWriteResult.NotFound
                } else {
                    SupplierWriteResult.Stored(checkNotNull(findInTransaction(id)))
                }
            }
        }

    internal suspend fun delete(id: Long): SupplierDeleteResult =
        executePostgresWrite(foreignKeyViolation = SupplierDeleteResult.InUse) {
            write {
                if (Suppliers.deleteWhere { Suppliers.id eq id } == 0) {
                    SupplierDeleteResult.NotFound
                } else {
                    SupplierDeleteResult.Deleted
                }
            }
        }

    private fun findInTransaction(id: Long): StoredSupplier? =
        Suppliers.selectAll().where { Suppliers.id eq id }.singleOrNull()?.let(::toStoredSupplier)

    private fun toStoredSupplier(row: ResultRow): StoredSupplier =
        StoredSupplier(
            id = row[Suppliers.id].value,
            name = row[Suppliers.name],
            title = row[Suppliers.title],
            firstName = row[Suppliers.firstName],
            lastName = row[Suppliers.lastName],
            street = row[Suppliers.street],
            houseNumber = row[Suppliers.houseNumber],
            city = row[Suppliers.city],
            postalCode = row[Suppliers.postalCode],
            countryId = row[Suppliers.countryId],
            phoneNumber1 = row[Suppliers.phoneNumber1],
            phoneNumber2 = row[Suppliers.phoneNumber2],
            phoneNumber3 = row[Suppliers.phoneNumber3],
            email = row[Suppliers.email],
            website = row[Suppliers.website],
        )

    private fun UpdateBuilder<*>.copyFrom(input: SupplierInput) {
        this[Suppliers.name] = checkNotNull(input.name)
        this[Suppliers.title] = input.title
        this[Suppliers.firstName] = input.firstName
        this[Suppliers.lastName] = input.lastName
        this[Suppliers.street] = input.street
        this[Suppliers.houseNumber] = input.houseNumber
        this[Suppliers.city] = input.city
        this[Suppliers.postalCode] = input.postalCode
        this[Suppliers.countryId] = input.countryId
        this[Suppliers.phoneNumber1] = input.phoneNumber1
        this[Suppliers.phoneNumber2] = input.phoneNumber2
        this[Suppliers.phoneNumber3] = input.phoneNumber3
        this[Suppliers.email] = input.email
        this[Suppliers.website] = input.website
    }

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
}

internal object Suppliers : LongIdTable("suppliers") {
    val name = varchar("name", length = 255)
    val title = varchar("title", length = 255).nullable()
    val firstName = varchar("first_name", length = 255).nullable()
    val lastName = varchar("last_name", length = 255).nullable()
    val street = varchar("street", length = 255).nullable()
    val houseNumber = varchar("house_number", length = 255).nullable()
    val city = varchar("city", length = 255).nullable()
    val postalCode = varchar("postal_code", length = 20).nullable()
    val countryId = long("country_id").nullable()
    val phoneNumber1 = varchar("phone_number1", length = 255).nullable()
    val phoneNumber2 = varchar("phone_number2", length = 255).nullable()
    val phoneNumber3 = varchar("phone_number3", length = 255).nullable()
    val email = varchar("email", length = 255).nullable()
    val website = varchar("website", length = 255).nullable()
}

internal data class StoredSupplier(
    val id: Long,
    val name: String,
    val title: String?,
    val firstName: String?,
    val lastName: String?,
    val street: String?,
    val houseNumber: String?,
    val city: String?,
    val postalCode: String?,
    val countryId: Long?,
    val phoneNumber1: String?,
    val phoneNumber2: String?,
    val phoneNumber3: String?,
    val email: String?,
    val website: String?,
)

internal sealed interface SupplierWriteResult {
    data class Stored(val supplier: StoredSupplier) : SupplierWriteResult

    data object NotFound : SupplierWriteResult

    data object CountryNotFound : SupplierWriteResult
}

internal sealed interface SupplierDeleteResult {
    data object Deleted : SupplierDeleteResult

    data object NotFound : SupplierDeleteResult

    data object InUse : SupplierDeleteResult
}
