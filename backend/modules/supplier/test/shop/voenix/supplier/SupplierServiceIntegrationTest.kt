package shop.voenix.supplier

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.country.Country
import shop.voenix.country.CountryReader
import shop.voenix.country.createCountryModule
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

internal class SupplierServiceIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create normalizes supplier fields and includes its country`() = runBlocking {
        withService { service, _ ->
            val created =
                assertIs<OperationResult.Success<Supplier>>(
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
    fun `list and get expose suppliers in stable order`() = runBlocking {
        withService { service, _ ->
            val zulu = service.create(SupplierInput(name = "Zulu", city = "Zurich"))
            val acme =
                assertIs<OperationResult.Success<Supplier>>(
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
                assertIs<OperationResult.Success<Supplier>>(
                        service.create(SupplierInput(name = "Acme"))
                    )
                    .value

            assertIs<OperationResult.Success<Supplier>>(zulu)
            val listed = assertIs<OperationResult.Success<List<Supplier>>>(service.list()).value
            assertEquals(listOf("Acme", "Acme", "Zulu"), listed.map { it.name })
            assertEquals(listOf(acme.id, secondAcme.id, 1), listed.map { it.id })
            assertEquals(acme, listed.first())

            assertEquals(
                acme,
                assertIs<OperationResult.Success<Supplier>>(service.get(acme.id)).value,
            )
            assertEquals(OperationResult.NotFound, service.get(404))
        }
    }

    @Test
    fun `update fully replaces fields and rolls back when the country is unknown`() = runBlocking {
        withService { service, _ ->
            val created =
                assertIs<OperationResult.Success<Supplier>>(
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
                assertIs<OperationResult.Success<Supplier>>(
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

            assertEquals(
                mapOf("countryId" to listOf("Country not found")),
                assertIs<OperationResult.Invalid>(
                        service.update(
                            created.id,
                            SupplierInput(name = "Must roll back", countryId = 404),
                        )
                    )
                    .errors,
            )
            assertEquals(
                updated,
                assertIs<OperationResult.Success<Supplier>>(service.get(created.id)).value,
            )
            assertSame(
                OperationResult.NotFound,
                service.update(404, SupplierInput(name = "Missing")),
            )
        }
    }

    @Test
    fun `database constraints and service validation protect supplier writes`() = runBlocking {
        withService { service, dataSource ->
            assertIs<OperationResult.Invalid>(service.create(SupplierInput()))
            assertEquals(
                mapOf("countryId" to listOf("Country not found")),
                assertIs<OperationResult.Invalid>(
                        service.create(SupplierInput(name = "Unknown country", countryId = 404))
                    )
                    .errors,
            )
            assertEquals(
                emptyList(),
                assertIs<OperationResult.Success<List<Supplier>>>(service.list()).value,
            )

            val created =
                assertIs<OperationResult.Success<Supplier>>(
                        service.create(SupplierInput(name = "Acme", countryId = 1))
                    )
                    .value
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM voenix.countries WHERE id = 1")
                }
            }
            val withoutCountry =
                assertIs<OperationResult.Success<Supplier>>(service.get(created.id)).value
            assertNull(withoutCountry.countryId)
            assertNull(withoutCountry.country)
        }
    }

    @Test
    fun `delete removes an existing supplier and then reports it missing`() = runBlocking {
        withService { service, _ ->
            val created =
                assertIs<OperationResult.Success<Supplier>>(
                        service.create(SupplierInput(name = "Acme"))
                    )
                    .value

            assertIs<OperationResult.Success<Unit>>(service.delete(created.id))
            assertSame(OperationResult.NotFound, service.delete(created.id))
            assertSame(OperationResult.NotFound, service.get(created.id))
        }
    }

    @Test
    fun `list resolves all distinct countries in one batch lookup`() = runBlocking {
        migratedDataSource("supplier-country-batch-test").use { dataSource ->
            resetSuppliers(dataSource)
            val database = Database.connect(datasource = dataSource)
            val reader = CountingCountryReader(createCountryModule(database).reader)
            val service = SupplierService(SupplierRepository(database), reader)

            assertIs<OperationResult.Success<Supplier>>(
                service.create(SupplierInput(name = "First", countryId = 1))
            )
            assertIs<OperationResult.Success<Supplier>>(
                service.create(SupplierInput(name = "Second", countryId = 1))
            )
            assertIs<OperationResult.Success<Supplier>>(
                service.create(SupplierInput(name = "Third", countryId = 2))
            )
            reader.requestedIds.clear()

            assertIs<OperationResult.Success<List<Supplier>>>(service.list())

            assertEquals(listOf(setOf(1L, 2L)), reader.requestedIds)
        }
    }

    @Test
    fun `list accepts a country deletion between supplier and country snapshots`() = runBlocking {
        migratedDataSource("supplier-split-snapshot-test").use { dataSource ->
            resetSuppliers(dataSource)
            val database = Database.connect(datasource = dataSource)
            val countryModule = createCountryModule(database)
            val regularService = SupplierService(SupplierRepository(database), countryModule.reader)
            val created =
                assertIs<OperationResult.Success<Supplier>>(
                        regularService.create(SupplierInput(name = "Snapshot", countryId = 1))
                    )
                    .value
            val deletingReader =
                object : CountryReader {
                    override suspend fun find(ids: Set<Long>): Map<Long, Country> {
                        withContext(Dispatchers.IO) { deleteCountry(dataSource, 1) }
                        return countryModule.reader.find(ids)
                    }
                }
            val splitSnapshotService = SupplierService(SupplierRepository(database), deletingReader)

            val listed =
                assertIs<OperationResult.Success<List<Supplier>>>(splitSnapshotService.list())
                    .value
                    .single { supplier -> supplier.id == created.id }

            assertEquals(1, listed.countryId)
            assertNull(listed.country)
            val nextRead =
                assertIs<OperationResult.Success<Supplier>>(regularService.get(created.id)).value
            assertNull(nextRead.countryId)
            assertNull(nextRead.country)
        }
    }

    @Test
    fun `create and country enrichment participate in an existing outer transaction`() =
        runBlocking {
            migratedDataSource("supplier-transaction-test").use { dataSource ->
                resetSuppliers(dataSource)
                val database = Database.connect(datasource = dataSource)
                val service =
                    SupplierService(
                        SupplierRepository(database),
                        createCountryModule(database).reader,
                    )

                assertFailsWith<RollbackMarker> {
                    withContext(Dispatchers.IO) {
                        suspendTransaction(db = database) {
                            assertIs<OperationResult.Success<Supplier>>(
                                service.create(SupplierInput(name = "Rolled back", countryId = 1))
                            )
                            throw RollbackMarker()
                        }
                    }
                }

                assertEquals(0, supplierCount(dataSource))
            }
        }

    @Test
    fun `database failures are hidden behind unexpected failure results`() = runBlocking {
        val dataSource = migratedDataSource("supplier-database-failure-test")
        val database = Database.connect(datasource = dataSource)
        val service =
            SupplierService(
                SupplierRepository(database),
                createCountryModule(database).reader,
            )
        dataSource.close()

        assertSame(OperationResult.UnexpectedFailure, service.list())
        assertSame(OperationResult.UnexpectedFailure, service.get(1))
        assertSame(
            OperationResult.UnexpectedFailure,
            service.create(SupplierInput(name = "Acme")),
        )
        assertSame(
            OperationResult.UnexpectedFailure,
            service.update(1, SupplierInput(name = "Acme")),
        )
        assertSame(OperationResult.UnexpectedFailure, service.delete(1))
    }

    private suspend fun withService(block: suspend (SupplierService, HikariDataSource) -> Unit) {
        migratedDataSource("supplier-service-test-${System.nanoTime()}").use { dataSource ->
            resetSuppliers(dataSource)
            val database = Database.connect(datasource = dataSource)
            val service =
                SupplierService(
                    SupplierRepository(database),
                    createCountryModule(database).reader,
                )
            block(service, dataSource)
        }
    }

    private fun resetSuppliers(dataSource: HikariDataSource) {
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
    }

    private fun supplierCount(dataSource: HikariDataSource): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM voenix.suppliers").use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
        }

    private fun deleteCountry(
        dataSource: HikariDataSource,
        countryId: Long,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM voenix.countries WHERE id = ?").use { statement
                ->
                statement.setLong(1, countryId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private class CountingCountryReader(private val delegate: CountryReader) : CountryReader {
        val requestedIds = mutableListOf<Set<Long>>()

        override suspend fun find(ids: Set<Long>): Map<Long, Country> {
            requestedIds += ids
            return delegate.find(ids)
        }
    }

    private class RollbackMarker : RuntimeException()
}
