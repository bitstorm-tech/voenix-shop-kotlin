package shop.voenix.country

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

internal class CountryServiceIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create returns conflicts for duplicate names and normalized codes`() = runBlocking {
        withSeededService { service ->
            assertSame(
                OperationResult.Conflict,
                service.create(CountryInput("germany", "GE")),
            )
            assertSame(
                OperationResult.Conflict,
                service.create(CountryInput("Denmark", " de ")),
            )

            assertIs<OperationResult.Success<Country>>(service.create(CountryInput("A%B", "AB")))
            assertIs<OperationResult.Success<Country>>(service.create(CountryInput("A_B", "AC")))
            assertSame(
                OperationResult.Conflict,
                service.create(CountryInput("a%b", "AD")),
            )

            val countries = assertIs<OperationResult.Success<List<Country>>>(service.listAdmin())
            assertEquals(10, countries.value.size)
            assertEquals(1, countries.value.count { it.name == "A%B" })
            assertEquals(1, countries.value.count { it.name == "A_B" })
        }
    }

    @Test
    fun `update allows self update and leaves the row unchanged on conflicts`() = runBlocking {
        withSeededService { service ->
            val selfUpdate =
                assertIs<OperationResult.Success<Country>>(
                    service.update(1, CountryInput("Germany", "DE"))
                )
            assertEquals(Country(1, "Germany", "DE"), selfUpdate.value)

            assertSame(
                OperationResult.Conflict,
                service.update(2, CountryInput("germany", "FR")),
            )
            assertSame(
                OperationResult.Conflict,
                service.update(2, CountryInput("France", "DE")),
            )
            assertSame(
                OperationResult.NotFound,
                service.update(999, CountryInput("Denmark", "DK")),
            )

            val unchanged = assertIs<OperationResult.Success<Country>>(service.get(2))
            assertEquals(Country(2, "France", "FR"), unchanged.value)
        }
    }

    @Test
    fun `direct service validation returns every lower camel case field error`() = runBlocking {
        withSeededService { service ->
            val cases =
                listOf(
                    CountryInput() to
                        linkedMapOf(
                            "name" to listOf("Name is required"),
                            "countryCode" to listOf("Country code is required"),
                        ),
                    CountryInput("A".repeat(256), "D1") to
                        linkedMapOf(
                            "name" to listOf("Name must be at most 255 characters"),
                            "countryCode" to listOf("Country code must contain only letters"),
                        ),
                    CountryInput("Germany", "D") to
                        mapOf("countryCode" to listOf("Country code must be exactly 2 characters")),
                    CountryInput("Germany", "GER") to
                        mapOf("countryCode" to listOf("Country code must be exactly 2 characters")),
                    CountryInput("Germany", "   ") to
                        mapOf("countryCode" to listOf("Country code is required")),
                )

            cases.forEach { (request, expected) ->
                val create = assertIs<OperationResult.Invalid>(service.create(request))
                assertEquals(expected, create.errors)

                val update = assertIs<OperationResult.Invalid>(service.update(1, request))
                assertEquals(expected, update.errors)
            }
        }
    }

    @Test
    fun `delete removes the only row and then reports it missing`() = runBlocking {
        withService(seedCountries = false) { service, dataSource ->
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO voenix.countries (name, country_code) VALUES ('Germany', 'DE')"
                    )
                    .use { statement -> statement.executeUpdate() }
            }

            assertIs<OperationResult.Success<Unit>>(service.delete(1))
            assertSame(OperationResult.NotFound, service.delete(1))
            val remaining = assertIs<OperationResult.Success<List<Country>>>(service.listAdmin())
            assertEquals(emptyList(), remaining.value)
        }
    }

    @Test
    fun `public mapping normalizes stored code and returns null for an unknown region`() =
        runBlocking {
            withService(seedCountries = false) { service, dataSource ->
                dataSource.connection.use { connection ->
                    connection
                        .prepareStatement(
                            "INSERT INTO voenix.countries (name, country_code) VALUES ('Unknown', 'zz')"
                        )
                        .use { statement -> statement.executeUpdate() }
                }

                val public =
                    assertIs<OperationResult.Success<List<PublicCountry>>>(service.listPublic())
                assertEquals("ZZ", public.value.single().countryCode)
                assertNull(public.value.single().dialCode)

                val admin = assertIs<OperationResult.Success<List<Country>>>(service.listAdmin())
                assertEquals("zz", admin.value.single().countryCode)
            }
        }

    @Test
    fun `case-variant concurrent creates leave one row and one conflict`() = runBlocking {
        withService(seedCountries = false) { service, _ ->
            val results = coroutineScope {
                listOf(
                        async { service.create(CountryInput("Denmark", "DK")) },
                        async { service.create(CountryInput("denmark", "DN")) },
                    )
                    .awaitAll()
            }

            assertEquals(1, results.count { it is OperationResult.Success })
            assertEquals(1, results.count { it === OperationResult.Conflict })
            val countries = assertIs<OperationResult.Success<List<Country>>>(service.listAdmin())
            assertEquals(1, countries.value.size)
        }
    }

    @Test
    fun `concurrent creates with the same code leave one row and one conflict`() = runBlocking {
        withService(seedCountries = false) { service, _ ->
            val results = coroutineScope {
                listOf(
                        async { service.create(CountryInput("Denmark", "DK")) },
                        async { service.create(CountryInput("Norway", "DK")) },
                    )
                    .awaitAll()
            }

            assertEquals(1, results.count { it is OperationResult.Success })
            assertEquals(1, results.count { it === OperationResult.Conflict })
            val countries = assertIs<OperationResult.Success<List<Country>>>(service.listAdmin())
            assertEquals(1, countries.value.size)
        }
    }

    @Test
    fun `database failures are hidden behind unexpected failure results`() = runBlocking {
        val dataSource = migratedDataSource("country-database-failure-test")
        resetCountries(dataSource, seedCountries = true)
        val service = CountryService(CountryRepository(Database.connect(datasource = dataSource)))
        dataSource.close()

        assertSame(OperationResult.UnexpectedFailure, service.listPublic())
        assertSame(OperationResult.UnexpectedFailure, service.listAdmin())
        assertSame(OperationResult.UnexpectedFailure, service.get(1))
        assertSame(
            OperationResult.UnexpectedFailure,
            service.create(CountryInput("Denmark", "DK")),
        )
        assertSame(
            OperationResult.UnexpectedFailure,
            service.update(1, CountryInput("Denmark", "DK")),
        )
        assertSame(OperationResult.UnexpectedFailure, service.delete(1))
    }

    private suspend fun withSeededService(block: suspend (CountryService) -> Unit) {
        withService(seedCountries = true) { service, _ -> block(service) }
    }

    private suspend fun withService(
        seedCountries: Boolean,
        block: suspend (CountryService, HikariDataSource) -> Unit,
    ) {
        migratedDataSource("country-service-test-${System.nanoTime()}").use { dataSource ->
            resetCountries(dataSource, seedCountries)
            val service =
                CountryService(CountryRepository(Database.connect(datasource = dataSource)))
            block(service, dataSource)
        }
    }

    private fun resetCountries(
        dataSource: HikariDataSource,
        seedCountries: Boolean,
    ) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.countries RESTART IDENTITY CASCADE")
                if (seedCountries) {
                    statement.execute(
                        """
                        INSERT INTO voenix.countries (name, country_code)
                        VALUES
                            ('Germany', 'DE'),
                            ('France', 'FR'),
                            ('Italy', 'IT'),
                            ('Austria', 'AT'),
                            ('Belgium', 'BE'),
                            ('Netherlands', 'NL'),
                            ('Spain', 'ES'),
                            ('Sweden', 'SE')
                        """
                            .trimIndent()
                    )
                }
            }
        }
    }
}
