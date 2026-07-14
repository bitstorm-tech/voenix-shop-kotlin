package shop.voenix.supplier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.country.Countries
import shop.voenix.country.Country
import shop.voenix.db.PostgresWrite.execute

class SupplierRepository(private val database: Database) {
    suspend fun list(): List<Supplier> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                supplierWithCountry
                    .selectAll()
                    .orderBy(
                        Suppliers.name to SortOrder.ASC,
                        Suppliers.id to SortOrder.ASC,
                    )
                    .map(::toSupplier)
            }
        }

    suspend fun find(id: Long): Supplier? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                findInTransaction(id)
            }
        }

    internal suspend fun insert(input: SupplierInput): SupplierWriteResult =
        execute(foreignKeyViolation = SupplierWriteResult.CountryNotFound) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    val id =
                        Suppliers.insertAndGetId { statement -> statement.copyFrom(input) }.value
                    SupplierWriteResult.Stored(checkNotNull(findInTransaction(id)))
                }
            }
        }

    internal suspend fun update(
        id: Long,
        input: SupplierInput,
    ): SupplierWriteResult =
        execute(foreignKeyViolation = SupplierWriteResult.CountryNotFound) {
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
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
        }

    internal suspend fun delete(id: Long): SupplierDeleteResult =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                if (Suppliers.deleteWhere { Suppliers.id eq id } == 0) {
                    SupplierDeleteResult.NotFound
                } else {
                    SupplierDeleteResult.Deleted
                }
            }
        }

    private fun findInTransaction(id: Long): Supplier? =
        supplierWithCountry
            .selectAll()
            .where { Suppliers.id eq id }
            .singleOrNull()
            ?.let(::toSupplier)

    private fun toSupplier(row: ResultRow): Supplier {
        val countryId = row[Suppliers.countryId]
        val country =
            row.getOrNull(Countries.id)?.let { id ->
                Country(
                    id = id.value,
                    name = row[Countries.name],
                    countryCode = row[Countries.countryCode],
                )
            }

        return Supplier(
            id = row[Suppliers.id].value,
            name = row[Suppliers.name],
            title = row[Suppliers.title],
            firstName = row[Suppliers.firstName],
            lastName = row[Suppliers.lastName],
            street = row[Suppliers.street],
            houseNumber = row[Suppliers.houseNumber],
            city = row[Suppliers.city],
            postalCode = row[Suppliers.postalCode],
            countryId = countryId,
            country = country,
            phoneNumber1 = row[Suppliers.phoneNumber1],
            phoneNumber2 = row[Suppliers.phoneNumber2],
            phoneNumber3 = row[Suppliers.phoneNumber3],
            email = row[Suppliers.email],
            website = row[Suppliers.website],
        )
    }

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

    private val supplierWithCountry =
        Suppliers.leftJoin(
            Countries,
            onColumn = { countryId },
            otherColumn = { id },
        )
}
