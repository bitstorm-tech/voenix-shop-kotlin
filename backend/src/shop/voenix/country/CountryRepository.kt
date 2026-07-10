package shop.voenix.country

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.LongColumnType
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

    suspend fun nameExists(
        name: String,
        excludeId: Long? = null,
    ): Boolean =
        query(readOnly = true) {
            val exclusion = if (excludeId == null) "" else " AND id <> ?"
            val arguments =
                buildList {
                    add(Countries.name.columnType to escapeLikePattern(name))
                    if (excludeId != null) add(LongColumnType() to excludeId)
                }
            exec(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM countries
                    WHERE name ILIKE ? ESCAPE '\'$exclusion
                )
                """.trimIndent(),
                arguments,
            ) { rows ->
                rows.next()
                rows.getBoolean(1)
            } ?: false
        }

    suspend fun codeExists(
        countryCode: String,
        excludeId: Long? = null,
    ): Boolean =
        query(readOnly = true) {
            val exclusion = if (excludeId == null) "" else " AND id <> ?"
            val arguments =
                buildList {
                    add(Countries.countryCode.columnType to countryCode)
                    if (excludeId != null) add(LongColumnType() to excludeId)
                }
            exec(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM countries
                    WHERE country_code = ?$exclusion
                )
                """.trimIndent(),
                arguments,
            ) { rows ->
                rows.next()
                rows.getBoolean(1)
            } ?: false
        }

    suspend fun insert(country: NormalizedCountry): Long =
        query {
            Countries
                .insertAndGetId {
                    it[name] = country.name
                    it[countryCode] = country.countryCode
                }.value
        }

    suspend fun update(
        id: Long,
        country: NormalizedCountry,
    ): Int =
        query {
            Countries.update({ Countries.id eq id }) {
                it[name] = country.name
                it[countryCode] = country.countryCode
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

    private fun escapeLikePattern(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
}
