package shop.voenix.magiccoins

import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import shop.voenix.testing.PostgresIntegrationTest

internal class MagicCoinsSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the database enforces the owner and balance invariants`() {
        migratedDataSource("magic-coins-schema").use { dataSource ->
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("TRUNCATE voenix.magic_coins RESTART IDENTITY")
                }
            }

            assertEquals(
                CHECK_VIOLATION,
                insertFailure(dataSource, "'both-owners-token'", "5", "10"),
            )
            assertEquals(CHECK_VIOLATION, insertFailure(dataSource, "NULL", "NULL", "10"))
            assertEquals(
                CHECK_VIOLATION,
                insertFailure(dataSource, "'negative-token'", "NULL", "-1"),
            )

            insert(dataSource, "'duplicate-guest-token'", "NULL", "10")
            assertEquals(
                UNIQUE_VIOLATION,
                insertFailure(dataSource, "'duplicate-guest-token'", "NULL", "10"),
            )

            insert(dataSource, "NULL", "9", "10")
            assertEquals(UNIQUE_VIOLATION, insertFailure(dataSource, "NULL", "9", "10"))
        }
    }

    private fun insert(
        dataSource: HikariDataSource,
        guestSessionToken: String,
        userId: String,
        balance: String,
    ) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO voenix.magic_coins (guest_session_token, user_id, balance)
                    VALUES ($guestSessionToken, $userId, $balance)
                    """
                        .trimIndent()
                )
            }
        }
    }

    private fun insertFailure(
        dataSource: HikariDataSource,
        guestSessionToken: String,
        userId: String,
        balance: String,
    ): String? =
        assertFailsWith<SQLException> { insert(dataSource, guestSessionToken, userId, balance) }
            .sqlState

    private companion object {
        const val CHECK_VIOLATION = "23514"
        const val UNIQUE_VIOLATION = "23505"
    }
}
