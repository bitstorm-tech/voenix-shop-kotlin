package shop.voenix.supplier

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Proves the contract the Article module depends on: one call resolves every distinct supplier
 * reference of a list, a reference to a deleted supplier is simply absent, and a list without any
 * supplier reference costs no query at all.
 */
internal class SupplierReaderIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `find resolves several suppliers in one batch and omits unknown ids`() = runBlocking {
        withReader { repository ->
            val acme = repository.store("Acme")
            val globex = repository.store("Globex")
            repository.store("Unreferenced")

            val found = repository.find(setOf(acme, globex, 404L))

            assertEquals(setOf(acme, globex), found.keys)
            assertEquals(SupplierSummary(id = acme, name = "Acme"), found[acme])
            assertEquals(SupplierSummary(id = globex, name = "Globex"), found[globex])
        }
    }

    @Test
    fun `find returns nothing when no requested supplier exists`() = runBlocking {
        withReader { repository ->
            val acme = repository.store("Acme")

            assertEquals(emptyMap(), repository.find(setOf(404L, 405L)))
            assertEquals(setOf(acme), repository.find(setOf(acme)).keys)
        }
    }

    @Test
    fun `find answers an empty set without touching the database`() = runBlocking {
        migratedDataSource("supplier-reader-empty-test").use { dataSource ->
            val reader: SupplierReader =
                SupplierRepository(Database.connect(datasource = dataSource))
            dataSource.close()

            assertEquals(emptyMap(), reader.find(emptySet()))
        }
    }

    private suspend fun withReader(block: suspend (SupplierRepository) -> Unit) {
        migratedDataSource("supplier-reader-test-${System.nanoTime()}").use { dataSource ->
            resetSuppliers(dataSource)
            block(SupplierRepository(Database.connect(datasource = dataSource)))
        }
    }

    private suspend fun SupplierRepository.store(name: String): Long =
        assertIs<SupplierWriteResult.Stored>(insert(SupplierInput(name = name))).supplier.id

    private fun resetSuppliers(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.suppliers RESTART IDENTITY CASCADE")
            }
        }
    }
}
