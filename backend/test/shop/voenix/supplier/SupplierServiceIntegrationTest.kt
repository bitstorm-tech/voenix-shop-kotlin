package shop.voenix.supplier

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.country.Country
import shop.voenix.testing.PostgresIntegrationTest

class SupplierServiceIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create normalizes supplier fields and includes its country`() = runBlocking {
        withService { service, _ ->
            val created =
                assertIs<SupplierResult.Success<Supplier>>(
                        service.create(
                            SupplierInput(
                                name = " Acme ",
                                title = " GmbH ",
                                firstName = " ",
                                city = " Berlin ",
                                postalCode = " 10115 ",
                                countryId = 1,
                                phoneNumber1 = " +49 30 123456 ",
                                email = " info@example.test ",
                            )
                        )
                    )
                    .value

            assertEquals(1, created.id)
            assertEquals("Acme", created.name)
            assertEquals("GmbH", created.title)
            assertEquals(null, created.firstName)
            assertEquals("Berlin", created.city)
            assertEquals("10115", created.postalCode)
            assertEquals(1, created.countryId)
            assertEquals(Country(1, "Germany", "DE"), created.country)
            assertEquals("+49 30 123456", created.phoneNumber1)
            assertEquals("info@example.test", created.email)
        }
    }

    @Test
    fun `list and get expose the required representations in stable order`() = runBlocking {
        withService { service, _ ->
            val zulu = service.create(SupplierInput(name = "Zulu", city = "Zurich"))
            val acme =
                assertIs<SupplierResult.Success<Supplier>>(
                        service.create(
                            SupplierInput(
                                name = "Acme",
                                title = "Dr.",
                                firstName = "Ada",
                                lastName = "Lovelace",
                                city = "Berlin",
                                email = "ada@example.test",
                            )
                        )
                    )
                    .value
            val secondAcme =
                assertIs<SupplierResult.Success<Supplier>>(
                        service.create(SupplierInput(name = "Acme"))
                    )
                    .value

            assertIs<SupplierResult.Success<Supplier>>(zulu)
            val listed =
                assertIs<SupplierResult.Success<SupplierListResponse>>(service.list()).value.items
            assertEquals(listOf("Acme", "Acme", "Zulu"), listed.map { it.name })
            assertEquals(listOf(acme.id, secondAcme.id, 1), listed.map { it.id })
            assertEquals("Dr. Ada Lovelace", listed.first().contactPerson)
            assertEquals("Berlin", listed.first().city)
            assertEquals("ada@example.test", listed.first().email)

            assertEquals(
                acme,
                assertIs<SupplierResult.Success<Supplier>>(service.get(acme.id)).value,
            )
            assertEquals(SupplierResult.NotFound, service.get(404))
        }
    }

    @Test
    fun `update fully replaces fields and rolls back when the country is unknown`() = runBlocking {
        withService { service, _ ->
            val created =
                assertIs<SupplierResult.Success<Supplier>>(
                        service.create(
                            SupplierInput(
                                name = "Acme",
                                title = "Dr.",
                                firstName = "Ada",
                                city = "Berlin",
                                countryId = 1,
                                email = "ada@example.test",
                            )
                        )
                    )
                    .value

            val updated =
                assertIs<SupplierResult.Success<Supplier>>(
                        service.update(
                            created.id,
                            SupplierInput(name = " Globex ", countryId = 2),
                        )
                    )
                    .value
            assertEquals("Globex", updated.name)
            assertNull(updated.title)
            assertNull(updated.firstName)
            assertNull(updated.city)
            assertNull(updated.email)
            assertEquals(Country(2, "France", "FR"), updated.country)

            assertSame(
                SupplierResult.CountryNotFound,
                service.update(
                    created.id,
                    SupplierInput(name = "Must roll back", countryId = 404),
                ),
            )
            assertEquals(
                updated,
                assertIs<SupplierResult.Success<Supplier>>(service.get(created.id)).value,
            )
            assertSame(
                SupplierResult.NotFound,
                service.update(404, SupplierInput(name = "Missing")),
            )
        }
    }

    @Test
    fun `database constraints and service validation protect supplier writes`() = runBlocking {
        withService { service, dataSource ->
            assertIs<SupplierResult.Invalid>(service.create(SupplierInput()))
            assertSame(
                SupplierResult.CountryNotFound,
                service.create(SupplierInput(name = "Unknown country", countryId = 404)),
            )
            assertEquals(
                emptyList(),
                assertIs<SupplierResult.Success<SupplierListResponse>>(service.list()).value.items,
            )

            val created =
                assertIs<SupplierResult.Success<Supplier>>(
                        service.create(SupplierInput(name = "Acme", countryId = 1))
                    )
                    .value
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM voenix.countries WHERE id = 1")
                }
            }
            val withoutCountry =
                assertIs<SupplierResult.Success<Supplier>>(service.get(created.id)).value
            assertNull(withoutCountry.countryId)
            assertNull(withoutCountry.country)
        }
    }

    @Test
    fun `delete removes an existing supplier and then reports it missing`() = runBlocking {
        withService { service, _ ->
            val created =
                assertIs<SupplierResult.Success<Supplier>>(
                        service.create(SupplierInput(name = "Acme"))
                    )
                    .value

            assertIs<SupplierResult.Success<Unit>>(service.delete(created.id))
            assertSame(SupplierResult.NotFound, service.delete(created.id))
            assertSame(SupplierResult.NotFound, service.get(created.id))
        }
    }

    @Test
    fun `database failures are hidden behind database error results`() = runBlocking {
        val dataSource = migratedDataSource("supplier-database-failure-test")
        val service = SupplierService(SupplierRepository(Database.connect(datasource = dataSource)))
        dataSource.close()

        assertSame(SupplierResult.DatabaseError, service.list())
        assertSame(SupplierResult.DatabaseError, service.get(1))
        assertSame(
            SupplierResult.DatabaseError,
            service.create(SupplierInput(name = "Acme")),
        )
        assertSame(
            SupplierResult.DatabaseError,
            service.update(1, SupplierInput(name = "Acme")),
        )
        assertSame(SupplierResult.DatabaseError, service.delete(1))
    }

    private suspend fun withService(block: suspend (SupplierService, HikariDataSource) -> Unit) {
        migratedDataSource("supplier-service-test-${System.nanoTime()}").use { dataSource ->
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        TRUNCATE voenix.suppliers, voenix.countries RESTART IDENTITY CASCADE;
                        INSERT INTO voenix.countries (name, country_code)
                        VALUES
                            ('Germany', 'DE'),
                            ('France', 'FR'),
                            ('Italy', 'IT'),
                            ('Austria', 'AT'),
                            ('Belgium', 'BE'),
                            ('Netherlands', 'NL'),
                            ('Spain', 'ES'),
                            ('Sweden', 'SE');
                        """
                            .trimIndent()
                    )
                }
            }
            val service =
                SupplierService(SupplierRepository(Database.connect(datasource = dataSource)))
            block(service, dataSource)
        }
    }
}
