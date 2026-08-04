package shop.voenix.operation

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * What the shared helper does with a failing operation: answer with the fallback, unless the
 * failure is a cancellation — in which case the coroutine has to keep being cancelled.
 */
internal class DatabaseOperationTest {
    private val logger = LoggerFactory.getLogger(DatabaseOperationTest::class.java)

    @Test
    fun `a successful operation returns its own result`() = runBlocking {
        val result = logger.databaseOperation("Database error while reading", "fallback") { "read" }

        assertEquals("read", result)
    }

    @Test
    fun `an unexpected failure becomes the fallback`() = runBlocking {
        val result =
            logger.databaseOperation<String>("Database error while reading", "fallback") {
                throw SQLException("connection reset")
            }

        assertEquals("fallback", result)
    }

    @Test
    fun `a cancellation is rethrown instead of becoming the fallback`() = runBlocking {
        val cancellation = CancellationException("the call was cancelled")

        val thrown =
            assertFailsWith<CancellationException> {
                logger.databaseOperation<String>("Database error while reading", "fallback") {
                    throw cancellation
                }
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `a cancellation wrapped in another exception is rethrown as well`() = runBlocking {
        val cancellation = CancellationException("the call was cancelled")

        val thrown =
            assertFailsWith<CancellationException> {
                logger.databaseOperation<String>("Database error while reading", "fallback") {
                    // What a driver or a transaction wrapper does with a cancelled call: it
                    // reports its own failure and keeps the cancellation as the cause.
                    throw IllegalStateException("transaction aborted", cancellation)
                }
            }

        assertSame(cancellation, thrown)
    }
}
