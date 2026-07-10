package shop.voenix.country

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.testing.PostgresIntegrationTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class CountryServiceIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create enforces exact case-insensitive names and normalized code conflicts`() = runBlocking {
        withSeededService { service ->
            assertSame(
                CountryResult.NameConflict,
                service.create(CreateAdminCountryRequest("germany", "GE")),
            )
            assertSame(
                CountryResult.CodeConflict,
                service.create(CreateAdminCountryRequest("Denmark", " de ")),
            )

            assertIs<CountryResult.Success<AdminCountryDto>>(
                service.create(CreateAdminCountryRequest("A%B", "AB")),
            )
            assertIs<CountryResult.Success<AdminCountryDto>>(
                service.create(CreateAdminCountryRequest("A_B", "AC")),
            )
            assertSame(
                CountryResult.NameConflict,
                service.create(CreateAdminCountryRequest("a%b", "AD")),
            )

            val countries = assertIs<CountryResult.Success<AdminCountryListResponse>>(service.listAdmin())
            assertEquals(10, countries.value.items.size)
            assertEquals(1, countries.value.items.count { it.name == "A%B" })
            assertEquals(1, countries.value.items.count { it.name == "A_B" })
        }
    }

    @Test
    fun `update allows self update and leaves the row unchanged on conflicts`() = runBlocking {
        withSeededService { service ->
            val selfUpdate =
                assertIs<CountryResult.Success<AdminCountryDto>>(
                    service.update(1, UpdateAdminCountryRequest("Germany", "DE")),
                )
            assertEquals(AdminCountryDto(1, "Germany", "DE"), selfUpdate.value)

            assertSame(
                CountryResult.NameConflict,
                service.update(2, UpdateAdminCountryRequest("germany", "FR")),
            )
            assertSame(
                CountryResult.CodeConflict,
                service.update(2, UpdateAdminCountryRequest("France", "DE")),
            )
            assertSame(
                CountryResult.NotFound,
                service.update(999, UpdateAdminCountryRequest("Denmark", "DK")),
            )

            val unchanged = assertIs<CountryResult.Success<AdminCountryDto>>(service.get(2))
            assertEquals(AdminCountryDto(2, "France", "FR"), unchanged.value)
        }
    }

    @Test
    fun `direct service validation preserves field priority and messages`() = runBlocking {
        withSeededService { service ->
            val cases =
                listOf(
                    CreateAdminCountryRequest("   ", "DE") to ("Name" to "Name is required"),
                    CreateAdminCountryRequest("A".repeat(256), "DE") to
                        ("Name" to "Name must be at most 255 characters"),
                    CreateAdminCountryRequest("Germany", "D") to
                        ("CountryCode" to "Country code must be exactly 2 characters"),
                    CreateAdminCountryRequest("Germany", "GER") to
                        ("CountryCode" to "Country code must be exactly 2 characters"),
                    CreateAdminCountryRequest("Germany", "D1") to
                        ("CountryCode" to "Country code must contain only letters"),
                    CreateAdminCountryRequest("Germany", "   ") to
                        ("CountryCode" to "Country code is required"),
                )

            cases.forEach { (request, expected) ->
                val create = assertIs<CountryResult.Invalid>(service.create(request))
                assertEquals(expected.first, create.field)
                assertEquals(expected.second, create.message)

                val update =
                    assertIs<CountryResult.Invalid>(
                        service.update(
                            1,
                            UpdateAdminCountryRequest(request.name, request.countryCode),
                        ),
                    )
                assertEquals(expected.first, update.field)
                assertEquals(expected.second, update.message)
            }
        }
    }

    @Test
    fun `delete removes the only row and then reports it missing`() = runBlocking {
        withService(seedCountries = false) { service, dataSource ->
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO voenix.countries (name, country_code) VALUES ('Germany', 'DE')",
                    ).use { statement -> statement.executeUpdate() }
            }

            assertIs<CountryResult.Success<Unit>>(service.delete(1))
            assertSame(CountryResult.NotFound, service.delete(1))
            val remaining = assertIs<CountryResult.Success<AdminCountryListResponse>>(service.listAdmin())
            assertEquals(emptyList(), remaining.value.items)
        }
    }

    @Test
    fun `public mapping normalizes stored code and returns null for an unknown region`() = runBlocking {
        withService(seedCountries = false) { service, dataSource ->
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO voenix.countries (name, country_code) VALUES ('Unknown', 'zz')",
                    ).use { statement -> statement.executeUpdate() }
            }

            val public = assertIs<CountryResult.Success<CountryListResponse>>(service.listPublic())
            assertEquals("ZZ", public.value.items.single().countryCode)
            assertNull(public.value.items.single().dialCode)

            val admin = assertIs<CountryResult.Success<AdminCountryListResponse>>(service.listAdmin())
            assertEquals("zz", admin.value.items.single().countryCode)
        }
    }

    @Test
    fun `case-variant concurrent creates leave one row and one conflict`() = runBlocking {
        withService(seedCountries = false) { service, _ ->
            val results =
                coroutineScope {
                    listOf(
                        async { service.create(CreateAdminCountryRequest("Denmark", "DK")) },
                        async { service.create(CreateAdminCountryRequest("denmark", "DN")) },
                    ).awaitAll()
                }

            assertEquals(1, results.count { it is CountryResult.Success })
            assertEquals(1, results.count { it === CountryResult.NameConflict })
            val countries = assertIs<CountryResult.Success<AdminCountryListResponse>>(service.listAdmin())
            assertEquals(1, countries.value.items.size)
        }
    }

    @Test
    fun `database failures are hidden behind database error results`() = runBlocking {
        val dataSource = migratedDataSource("country-database-failure-test")
        resetCountries(dataSource, seedCountries = true)
        val service = CountryService(CountryRepository(Database.connect(datasource = dataSource)))
        dataSource.close()

        assertSame(CountryResult.DatabaseError, service.listPublic())
        assertSame(CountryResult.DatabaseError, service.listAdmin())
        assertSame(CountryResult.DatabaseError, service.get(1))
        assertSame(
            CountryResult.DatabaseError,
            service.create(CreateAdminCountryRequest("Denmark", "DK")),
        )
        assertSame(
            CountryResult.DatabaseError,
            service.update(1, UpdateAdminCountryRequest("Denmark", "DK")),
        )
        assertSame(CountryResult.DatabaseError, service.delete(1))
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
            val service = CountryService(CountryRepository(Database.connect(datasource = dataSource)))
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
                        """.trimIndent(),
                    )
                }
            }
        }
    }
}
