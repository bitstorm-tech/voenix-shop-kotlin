package shop.voenix.db

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

class PostgresWriteTest {
    @Test
    fun `successful writes return their result`() = runBlocking {
        val result = PostgresWrite.writeOrConflict(conflict = "conflict") { "stored" }

        assertEquals("stored", result)
    }

    @Test
    fun `unique violations return the supplied conflict`() = runBlocking {
        val result =
            PostgresWrite.writeOrConflict(conflict = "conflict") {
                throw SQLException("duplicate", "23505")
            }

        assertEquals("conflict", result)
    }

    @Test
    fun `other SQL errors keep the original exception`() = runBlocking {
        val original = SQLException("connection failed", "08006")

        val thrown =
            assertFailsWith<SQLException> {
                PostgresWrite.writeOrConflict(conflict = "conflict") { throw original }
            }

        assertSame(original, thrown)
    }
}
