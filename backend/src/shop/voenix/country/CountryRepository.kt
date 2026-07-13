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
import shop.voenix.db.PostgresWrite.writeOrConflict

class CountryRepository(private val database: Database) {
    suspend fun list(): List<Country> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                Countries.selectAll()
                    .orderBy(
                        Countries.countryCode to SortOrder.ASC,
                        Countries.id to SortOrder.ASC,
                    )
                    .map { row ->
                        Country(
                            id = row[Countries.id].value,
                            name = row[Countries.name],
                            countryCode = row[Countries.countryCode],
                        )
                    }
            }
        }

    suspend fun find(id: Long): Country? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                Countries.selectAll()
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
        }

    internal suspend fun insert(
        name: String,
        countryCode: String,
    ): CountryWriteResult =
        writeOrConflict(CountryWriteResult.Conflict) {
            val id =
                withContext(Dispatchers.IO) {
                    suspendTransaction(db = database) {
                        maxAttempts = 1
                        Countries.insertAndGetId {
                                it[Countries.name] = name
                                it[Countries.countryCode] = countryCode
                            }
                            .value
                    }
                }
            CountryWriteResult.Stored(Country(id, name, countryCode))
        }

    internal suspend fun update(
        id: Long,
        name: String,
        countryCode: String,
    ): CountryWriteResult =
        writeOrConflict(CountryWriteResult.Conflict) {
            val updated =
                withContext(Dispatchers.IO) {
                    suspendTransaction(db = database) {
                        maxAttempts = 1
                        Countries.update({ Countries.id eq id }) {
                            it[Countries.name] = name
                            it[Countries.countryCode] = countryCode
                        }
                    }
                }

            when (updated) {
                0 -> CountryWriteResult.NotFound
                else -> CountryWriteResult.Stored(Country(id, name, countryCode))
            }
        }

    suspend fun delete(id: Long): Int =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                Countries.deleteWhere { Countries.id eq id }
            }
        }
}
