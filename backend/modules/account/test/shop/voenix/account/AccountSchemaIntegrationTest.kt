package shop.voenix.account

import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import shop.voenix.testing.PostgresIntegrationTest

/** Proves the Flyway migration against an empty PostgreSQL database and the lean schema. */
internal class AccountSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the migration builds the account schema with its constraints`() {
        migratedDataSource("account-schema-test").use { dataSource ->
            dataSource.connection.use { connection ->
                execute(connection, "TRUNCATE voenix.users RESTART IDENTITY CASCADE")

                execute(
                    connection,
                    "INSERT INTO voenix.users (email, password_hash) " +
                        "VALUES ('user@example.com', 'hash')",
                )
                assertSqlState(connection, "23505", "the email is unique case-insensitively") {
                    "INSERT INTO voenix.users (email, password_hash) " +
                        "VALUES ('USER@example.com', 'hash')"
                }

                execute(
                    connection,
                    "INSERT INTO voenix.user_roles (user_id, role) VALUES (1, 'CUSTOMER')",
                )
                assertSqlState(connection, "23505", "a role can be granted only once") {
                    "INSERT INTO voenix.user_roles (user_id, role) VALUES (1, 'CUSTOMER')"
                }
                assertSqlState(connection, "23503", "roles need an existing user") {
                    "INSERT INTO voenix.user_roles (user_id, role) VALUES (999, 'CUSTOMER')"
                }

                execute(
                    connection,
                    "INSERT INTO voenix.account_tokens " +
                        "(user_id, purpose, token_hash, expires_at) " +
                        "VALUES (1, 'CONFIRM_EMAIL', 'hash-1', now())",
                )
                assertSqlState(connection, "23505", "only one token per user and purpose") {
                    "INSERT INTO voenix.account_tokens " +
                        "(user_id, purpose, token_hash, expires_at) " +
                        "VALUES (1, 'CONFIRM_EMAIL', 'hash-2', now())"
                }
                assertSqlState(connection, "23503", "tokens need an existing user") {
                    "INSERT INTO voenix.account_tokens " +
                        "(user_id, purpose, token_hash, expires_at) " +
                        "VALUES (999, 'CONFIRM_EMAIL', 'hash-3', now())"
                }

                execute(
                    connection,
                    "INSERT INTO voenix.magic_coins (user_id, balance) VALUES (1, 10)",
                )
                assertSqlState(connection, "23503", "magic coins need an existing user") {
                    "INSERT INTO voenix.magic_coins (user_id, balance) VALUES (999, 1)"
                }

                execute(connection, "DELETE FROM voenix.users WHERE id = 1")
                assertEquals(
                    0,
                    count(connection, "voenix.user_roles"),
                    "deleting a user cascades to the roles",
                )
                assertEquals(
                    0,
                    count(connection, "voenix.account_tokens"),
                    "deleting a user cascades to the tokens",
                )
                assertEquals(
                    0,
                    count(connection, "voenix.magic_coins"),
                    "deleting a user cascades to the magic coin balance",
                )

                assertEquals(
                    0,
                    countIdentityBallastTables(connection),
                    "the ASP.NET Identity ballast tables are not migrated",
                )
            }
        }
    }

    private fun assertSqlState(
        connection: Connection,
        expected: String,
        message: String,
        sql: () -> String,
    ) {
        val failure = assertFailsWith<SQLException>(message) { execute(connection, sql()) }
        assertEquals(expected, failure.sqlState, message)
    }

    private fun execute(connection: Connection, sql: String) {
        connection.createStatement().use { statement -> statement.execute(sql) }
    }

    private fun count(connection: Connection, table: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun countIdentityBallastTables(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT count(*) FROM information_schema.tables " +
                        "WHERE table_schema = 'voenix' AND table_name IN " +
                        "('roles', 'user_claims', 'role_claims', 'user_logins', 'user_tokens')"
                )
                .use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
        }
}
