package shop.voenix.vat

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.testing.PostgresIntegrationTest

class VatServiceIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create normalizes values and list sorts by name then id`() = runBlocking {
        withService { service, _ ->
            val standard =
                assertIs<VatResult.Success<Vat>>(
                        service.create(
                            VatInput(
                                name = " Standard ",
                                percent = 19,
                                description = " German standard rate ",
                                isDefault = true,
                            )
                        )
                    )
                    .value
            assertEquals(Vat(standard.id, "Standard", 19, "German standard rate", true), standard)

            val reduced =
                assertIs<VatResult.Success<Vat>>(
                        service.create(
                            VatInput(
                                name = " Reduced ",
                                percent = 7,
                                description = "   ",
                            )
                        )
                    )
                    .value
            assertNull(reduced.description)

            val list = assertIs<VatResult.Success<List<Vat>>>(service.list()).value
            assertEquals(listOf("Reduced", "Standard"), list.map(Vat::name))
            assertEquals(standard, assertIs<VatResult.Success<Vat>>(service.get(standard.id)).value)
        }
    }

    @Test
    fun `create and update move the default atomically while false and delete may leave none`() =
        runBlocking {
            withService { service, _ ->
                val standard =
                    assertIs<VatResult.Success<Vat>>(
                            service.create(VatInput("Standard", 19, isDefault = true))
                        )
                        .value
                val reduced =
                    assertIs<VatResult.Success<Vat>>(
                            service.create(VatInput("Reduced", 7, isDefault = false))
                        )
                        .value

                val promoted =
                    assertIs<VatResult.Success<Vat>>(
                            service.update(
                                reduced.id,
                                VatInput(" Reduced rate ", 7, " Lower rate ", isDefault = true),
                            )
                        )
                        .value
                assertEquals(Vat(reduced.id, "Reduced rate", 7, "Lower rate", true), promoted)
                assertEquals(
                    listOf(promoted),
                    assertIs<VatResult.Success<List<Vat>>>(service.list())
                        .value
                        .filter(Vat::isDefault),
                )

                val demoted =
                    assertIs<VatResult.Success<Vat>>(
                            service.update(
                                promoted.id,
                                VatInput("Reduced rate", 7, isDefault = false),
                            )
                        )
                        .value
                assertTrue(!demoted.isDefault)
                assertTrue(
                    assertIs<VatResult.Success<List<Vat>>>(service.list())
                        .value
                        .none(Vat::isDefault)
                )

                assertIs<VatResult.Success<Vat>>(
                    service.update(
                        standard.id,
                        VatInput("Standard", 19, isDefault = true),
                    )
                )
                assertIs<VatResult.Success<Unit>>(service.delete(standard.id))
                assertSame(VatResult.NotFound, service.delete(standard.id))
                assertTrue(
                    assertIs<VatResult.Success<List<Vat>>>(service.list())
                        .value
                        .none(Vat::isDefault)
                )
                assertSame(
                    VatResult.NotFound,
                    service.update(999, VatInput("Missing", 19, isDefault = true)),
                )
            }
        }

    @Test
    fun `direct callers cannot bypass validation`() = runBlocking {
        withService { service, _ ->
            val expected =
                linkedMapOf(
                    "name" to listOf("Name is required"),
                    "percent" to listOf("Percent must be between 0 and 100"),
                )
            assertEquals(
                expected,
                assertIs<VatResult.Invalid>(service.create(VatInput("   ", 101))).errors,
            )
            assertEquals(
                expected,
                assertIs<VatResult.Invalid>(service.update(1, VatInput("   ", 101))).errors,
            )
            assertEquals(emptyList(), assertIs<VatResult.Success<List<Vat>>>(service.list()).value)
        }
    }

    @Test
    fun `database uniqueness handles normalized exact names and allows case variants`() =
        runBlocking {
            withService { service, _ ->
                val results = coroutineScope {
                    listOf(
                            async { service.create(VatInput("Standard", 19)) },
                            async { service.create(VatInput(" Standard ", 7)) },
                        )
                        .awaitAll()
                }

                assertEquals(1, results.count { it is VatResult.Success })
                assertEquals(1, results.count { it === VatResult.Conflict })
                assertIs<VatResult.Success<Vat>>(service.create(VatInput("standard", 7)))
                assertEquals(2, assertIs<VatResult.Success<List<Vat>>>(service.list()).value.size)
            }
        }

    @Test
    fun `failed default writes roll back the previous default`() = runBlocking {
        withService { service, _ ->
            val standard =
                assertIs<VatResult.Success<Vat>>(
                        service.create(VatInput("Standard", 19, isDefault = true))
                    )
                    .value
            val reduced =
                assertIs<VatResult.Success<Vat>>(
                        service.create(VatInput("Reduced", 7, isDefault = false))
                    )
                    .value

            assertSame(
                VatResult.Conflict,
                service.create(VatInput(" Standard ", 20, isDefault = true)),
            )
            assertSame(
                VatResult.Conflict,
                service.update(reduced.id, VatInput(" Standard ", 7, isDefault = true)),
            )

            val stored = assertIs<VatResult.Success<List<Vat>>>(service.list()).value
            assertEquals(2, stored.size)
            assertEquals(standard.id, stored.single(Vat::isDefault).id)
            assertEquals("Reduced", stored.single { it.id == reduced.id }.name)
        }
    }

    @Test
    fun `concurrent default writes never leave more than one default`() = runBlocking {
        withService { service, _ ->
            val results = coroutineScope {
                listOf(
                        async { service.create(VatInput("Standard", 19, isDefault = true)) },
                        async { service.create(VatInput("Reduced", 7, isDefault = true)) },
                    )
                    .awaitAll()
            }

            assertTrue(results.any { it is VatResult.Success })
            assertTrue(results.all { it is VatResult.Success || it === VatResult.DatabaseError })
            val stored = assertIs<VatResult.Success<List<Vat>>>(service.list()).value
            assertEquals(1, stored.count(Vat::isDefault))
        }
    }

    @Test
    fun `database failures are hidden behind database error results`() = runBlocking {
        val dataSource = migratedDataSource("vat-database-failure-test")
        resetVats(dataSource)
        val service = VatService(VatRepository(Database.connect(datasource = dataSource)))
        dataSource.close()

        assertSame(VatResult.DatabaseError, service.list())
        assertSame(VatResult.DatabaseError, service.get(1))
        assertSame(VatResult.DatabaseError, service.create(VatInput("Standard", 19)))
        assertSame(VatResult.DatabaseError, service.update(1, VatInput("Standard", 19)))
        assertSame(VatResult.DatabaseError, service.delete(1))
    }

    private suspend fun withService(block: suspend (VatService, HikariDataSource) -> Unit) {
        migratedDataSource("vat-service-test-${System.nanoTime()}").use { dataSource ->
            resetVats(dataSource)
            val service = VatService(VatRepository(Database.connect(datasource = dataSource)))
            block(service, dataSource)
        }
    }

    private fun resetVats(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.value_added_taxes RESTART IDENTITY CASCADE")
            }
        }
    }
}
