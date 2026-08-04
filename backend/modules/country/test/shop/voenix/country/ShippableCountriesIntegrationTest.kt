package shop.voenix.country

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The capability the checkout consults before it places an order (issue #81): does the shop ship to
 * this code?
 *
 * There is no `active` column on `countries` — the table *is* the list of destinations — so what
 * this test states is exactly that: a stored code is shippable, anything else is not, and the
 * country admin opens and closes a destination by creating and deleting the row.
 */
internal class ShippableCountriesIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a stored code is shippable, however the client spells it`() = runBlocking {
        withCountries { _, countries ->
            assertTrue(countries.isShippable("DE"))
            assertTrue(countries.isShippable("de"), "A client may send the code in any case")
            assertTrue(countries.isShippable(" de "), "…and with the whitespace of a form field")
            assertTrue(countries.isShippable("SE"))
        }
    }

    @Test
    fun `everything that is not a stored code is refused`() = runBlocking {
        withCountries { _, countries ->
            assertFalse(countries.isShippable("XX"), "A syntactically fine but unknown code")
            assertFalse(countries.isShippable(""), "A blank field reaches no country")
            assertFalse(countries.isShippable("   "))
            assertFalse(countries.isShippable("D"), "Half a code is no code")
            assertFalse(countries.isShippable("DEU"), "The three-letter code is not what is stored")
        }
    }

    @Test
    fun `the country admin opens and closes a destination`() = runBlocking {
        withCountries { service, countries ->
            assertFalse(countries.isShippable("DK"))

            val created =
                assertIs<OperationResult.Success<Country>>(
                    service.create(CountryInput("Denmark", "dk"))
                )
            assertTrue(countries.isShippable("DK"), "Adding the row opens the destination")

            assertIs<OperationResult.Success<Unit>>(service.delete(created.value.id))
            assertFalse(countries.isShippable("DK"), "…and deleting it closes it again")
        }
    }

    private suspend fun withCountries(block: suspend (CountryService, ShippableCountries) -> Unit) {
        migratedDataSource("shippable-countries-test-${System.nanoTime()}").use { dataSource ->
            seedCountries(dataSource)
            val repository = CountryRepository(Database.connect(datasource = dataSource))
            block(CountryService(repository), repository)
        }
    }

    private fun seedCountries(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.countries RESTART IDENTITY CASCADE")
                statement.execute(
                    """
                    INSERT INTO voenix.countries (name, country_code)
                    VALUES ('Germany', 'DE'), ('Sweden', 'SE')
                    """
                        .trimIndent()
                )
            }
        }
    }
}
