package shop.voenix.db

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

internal class PostgresWriteTest {
    @Test
    fun `successful writes return their result`() = runBlocking {
        val result = executePostgresWrite { "stored" }

        assertEquals("stored", result)
    }

    @Test
    fun `unique violations return the supplied conflict`() = runBlocking {
        val result =
            executePostgresWrite(uniqueViolation = "conflict") {
                throw SQLException("duplicate", "23505")
            }

        assertEquals("conflict", result)
    }

    @Test
    fun `foreign key violations return the supplied result`() = runBlocking {
        val result =
            executePostgresWrite(foreignKeyViolation = "missing reference") {
                throw SQLException("missing reference", "23503")
            }

        assertEquals("missing reference", result)
    }

    @Test
    fun `one write configuration can handle unique and foreign key violations`() = runBlocking {
        val uniqueViolation =
            executePostgresWrite(
                uniqueViolation = "conflict",
                foreignKeyViolation = "missing reference",
            ) {
                throw SQLException("duplicate", "23505")
            }
        val foreignKeyViolation =
            executePostgresWrite(
                uniqueViolation = "conflict",
                foreignKeyViolation = "missing reference",
            ) {
                throw SQLException("missing reference", "23503")
            }

        assertEquals("conflict", uniqueViolation)
        assertEquals("missing reference", foreignKeyViolation)
    }

    @Test
    fun `unconfigured constraint violations keep the original exception`() = runBlocking {
        val original = SQLException("missing reference", "23503")

        val thrown =
            assertFailsWith<SQLException> {
                executePostgresWrite(uniqueViolation = "conflict") { throw original }
            }

        assertSame(original, thrown)
    }

    @Test
    fun `other SQL errors keep the original exception`() = runBlocking {
        val original = SQLException("connection failed", "08006")

        val thrown =
            assertFailsWith<SQLException> {
                executePostgresWrite(uniqueViolation = "conflict") { throw original }
            }

        assertSame(original, thrown)
    }
}
