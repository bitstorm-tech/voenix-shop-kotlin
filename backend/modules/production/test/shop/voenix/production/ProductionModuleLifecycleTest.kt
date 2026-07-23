package shop.voenix.production

import io.ktor.server.testing.testApplication
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.production.delivery.insertDestination
import shop.voenix.production.delivery.insertSupplier
import shop.voenix.production.delivery.order
import shop.voenix.production.pdf.newTempDirectory
import shop.voenix.testing.PostgresIntegrationTest

internal class ProductionModuleLifecycleTest : PostgresIntegrationTest() {
    private val artifactRoot = newTempDirectory()

    @AfterTest
    fun cleanUp() {
        artifactRoot.toFile().deleteRecursively()
    }

    @Test
    fun `install starts exactly one worker that processes durable requests`() = runBlocking {
        migratedDataSource("production-module-lifecycle-test").use { dataSource ->
            insertSupplierWithDestination(dataSource)
            val database = Database.connect(dataSource)
            val module =
                createProductionModule(
                    database,
                    artifactRoot,
                    emailOutbox = { reference ->
                        error("unexpected notification enqueue for $reference")
                    },
                ) { orderId ->
                    order(orderId)
                }
            withContext(Dispatchers.IO) {
                suspendTransaction(db = database) {
                    maxAttempts = 1
                    module.outbox.request(77)
                }
            }

            testApplication {
                application {
                    installHttpRuntime()
                    installAuthModule(AuthSettings("production-module-lifecycle-secret"))
                    module.install(this)
                    assertFailsWith<IllegalStateException> { module.install(this) }
                }
                startApplication()

                var remainingPolls = 100
                while (!processed(dataSource) && remainingPolls > 0) {
                    delay(100)
                    remainingPolls -= 1
                }
                assertTrue(processed(dataSource), "worker did not split the request in time")
            }
        }
    }

    private fun insertSupplierWithDestination(dataSource: DataSource) {
        insertSupplier(dataSource)
        insertDestination(dataSource, id = 1)
    }

    private fun processed(dataSource: DataSource): Boolean =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT count(*) FROM voenix.production_requests " +
                            "WHERE processed_at IS NOT NULL"
                    )
                    .use { rows ->
                        rows.next()
                        rows.getInt(1) == 1
                    }
            }
        }
}
