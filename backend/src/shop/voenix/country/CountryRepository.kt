package shop.voenix.country

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

class CountryRepository(
    private val database: Database,
) {
    suspend fun list(): List<Country> =
        query(readOnly = true) {
            Countries
                .selectAll()
                .orderBy(
                    Countries.countryCode to SortOrder.ASC,
                    Countries.id to SortOrder.ASC,
                ).map { row ->
                    Country(
                        id = row[Countries.id].value,
                        name = row[Countries.name],
                        countryCode = row[Countries.countryCode],
                    )
                }
        }

    suspend fun find(id: Long): Country? =
        query(readOnly = true) {
            Countries
                .selectAll()
                .where { Countries.id eq id }
                .singleOrNull()
                ?.let { row ->
                    Country(
                        id = row[Countries.id].value,
                        name = row[Countries.name],
                        countryCode = row[Countries.countryCode],
                    )
                }
        }

    suspend fun insert(
        name: String,
        countryCode: String,
    ): Long =
        query {
            Countries
                .insertAndGetId {
                    it[Countries.name] = name
                    it[Countries.countryCode] = countryCode
                }.value
        }

    suspend fun update(
        id: Long,
        name: String,
        countryCode: String,
    ): Int =
        query {
            Countries.update({ Countries.id eq id }) {
                it[Countries.name] = name
                it[Countries.countryCode] = countryCode
            }
        }

    suspend fun delete(id: Long): Int =
        query {
            Countries.deleteWhere { Countries.id eq id }
        }

    private suspend fun <T> query(
        readOnly: Boolean = false,
        statement: suspend org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> T,
    ): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = readOnly) {
                maxAttempts = 1
                statement()
            }
        }
}
