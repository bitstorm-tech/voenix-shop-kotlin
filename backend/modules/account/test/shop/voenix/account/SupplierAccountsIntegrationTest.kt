package shop.voenix.account

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.http.FrontendBaseUrl
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The one capability the account module exports: which supplier a logged-in user acts for. The
 * supplier route protection asks it on every request, so the three answers that matter are a linked
 * login, a user without a link, and a user that does not exist at all.
 */
internal class SupplierAccountsIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the capability answers the linked supplier and null for everyone else`() {
        migratedDataSource("supplier-accounts-test").use { dataSource ->
            prepare(dataSource)
            val accounts =
                createAccountModule(
                        database = Database.connect(datasource = dataSource),
                        settings =
                            AccountSettings(
                                frontendBaseUrl = FrontendBaseUrl("http://localhost:5173"),
                                pbkdf2Iterations = 1_000,
                            ),
                        userEmails = RecordingUserEmailSender(),
                    )
                    .supplierAccounts

            runBlocking {
                assertEquals(7L, accounts.supplierIdOf(1L), "the linked supplier login")
                assertNull(accounts.supplierIdOf(2L), "a customer carries no link")
                assertNull(accounts.supplierIdOf(999L), "an unknown user is not a supplier either")
            }
        }
    }

    private fun prepare(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.users RESTART IDENTITY CASCADE")
                statement.execute("TRUNCATE voenix.suppliers RESTART IDENTITY CASCADE")
                statement.execute("INSERT INTO voenix.suppliers (id, name) VALUES (7, 'Alpha')")
                statement.execute(
                    "INSERT INTO voenix.users (id, email, password_hash, supplier_id) " +
                        "VALUES (1, 'supplier@example.com', 'hash', 7)"
                )
                statement.execute(
                    "INSERT INTO voenix.users (id, email, password_hash) " +
                        "VALUES (2, 'customer@example.com', 'hash')"
                )
            }
        }
    }
}
