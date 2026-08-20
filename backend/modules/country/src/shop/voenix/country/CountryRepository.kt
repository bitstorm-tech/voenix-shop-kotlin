package shop.voenix.country

import java.util.Locale
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite
import shop.voenix.db.read
import shop.voenix.db.write

internal class CountryRepository(private val database: Database) :
    CountryReader, ShippableCountries {
    internal suspend fun list(): List<Country> = database.read {
        Countries.selectAll()
            .orderBy(
                Countries.countryCode to SortOrder.ASC,
                Countries.id to SortOrder.ASC,
            )
            .map(::toCountry)
    }

    internal suspend fun findById(id: Long): Country? = database.read {
        Countries.selectAll().where { Countries.id eq id }.singleOrNull()?.let(::toCountry)
    }

    override suspend fun find(ids: Set<Long>): Map<Long, Country> {
        if (ids.isEmpty()) return emptyMap()
        return database.read {
            Countries.selectAll()
                .where { Countries.id inList ids }
                .associate { row ->
                    val country = toCountry(row)
                    country.id to country
                }
        }
    }

    /**
     * Whether a row with this code exists — the whole of [ShippableCountries].
     *
     * The code is normalized the way [CountryService] normalizes it before it is stored, so the
     * comparison is a plain equality that the unique index on `country_code` answers directly.
     */
    override suspend fun isShippable(countryCode: String): Boolean {
        val code = countryCode.trim().uppercase(Locale.ROOT)
        if (code.isEmpty()) return false
        return database.read {
            Countries.select(Countries.id).where { Countries.countryCode eq code }.limit(1).any()
        }
    }

    internal suspend fun insert(country: CountryWrite): CountryWriteResult =
        executePostgresWrite(uniqueViolation = CountryWriteResult.Conflict) {
            val id = database.write {
                Countries.insertAndGetId {
                        it[Countries.name] = country.name
                        it[Countries.countryCode] = country.countryCode
                    }
                    .value
            }
            CountryWriteResult.Stored(country.toCountry(id))
        }

    internal suspend fun update(
        id: Long,
        country: CountryWrite,
    ): CountryWriteResult =
        executePostgresWrite(uniqueViolation = CountryWriteResult.Conflict) {
            val updated = database.write {
                Countries.update({ Countries.id eq id }) {
                    it[Countries.name] = country.name
                    it[Countries.countryCode] = country.countryCode
                }
            }

            when (updated) {
                0 -> CountryWriteResult.NotFound
                else -> CountryWriteResult.Stored(country.toCountry(id))
            }
        }

    internal suspend fun delete(id: Long): Int = database.write {
        Countries.deleteWhere { Countries.id eq id }
    }

    private fun toCountry(row: ResultRow): Country =
        Country(
            id = row[Countries.id].value,
            name = row[Countries.name],
            countryCode = row[Countries.countryCode],
        )

    private fun CountryWrite.toCountry(id: Long): Country =
        Country(id = id, name = name, countryCode = countryCode)
}

internal object Countries : LongIdTable("countries") {
    val name = varchar("name", length = 255)
    val countryCode = varchar("country_code", length = 2)
}

/** The normalized values [CountryService] wants stored, without the generated id. */
internal data class CountryWrite(
    val name: String,
    val countryCode: String,
)

internal sealed interface CountryWriteResult {
    data class Stored(val country: Country) : CountryWriteResult

    data object NotFound : CountryWriteResult

    data object Conflict : CountryWriteResult
}
