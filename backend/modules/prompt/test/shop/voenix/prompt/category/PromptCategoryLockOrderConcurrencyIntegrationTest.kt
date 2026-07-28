package shop.voenix.prompt.category

import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.operation.OperationResult
import shop.voenix.prompt.PromptTestSchema
import shop.voenix.prompt.ReorderInput
import shop.voenix.prompt.persistence.PromptCategoryRepository
import shop.voenix.prompt.persistence.PromptSubcategoryRepository
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether every writer of category rows really takes them in the same order.
 *
 * Category rows are locked by two slices that never wait on the same anchor: the category writers,
 * which queue on the `CATEGORY` row of `prompt_ordering`, and the subcategory writers, which do
 * not. Only the shared ascending id order keeps them from waiting on each other's rows, and a
 * violation of it is a deadlock — SQL state `40P01`, which nothing maps, so it surfaces as a failed
 * operation.
 *
 * Both tests below build that situation deterministically: a raw connection holds one category row,
 * the writers are started so that each of them ends up needing a row another one holds, and only
 * then is the raw connection released.
 */
internal class PromptCategoryLockOrderConcurrencyIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a category reorder and a subcategory move between categories do not wait on each other`() {
        migratedDataSource("prompt-category-cross-slice-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "First", "Second", "Third", "Fourth")
            PromptTestSchema.seedSubcategories(dataSource, categoryId = 3, "Moving")
            val database = Database.connect(datasource = dataSource)
            val categories = PromptCategoryService(PromptCategoryRepository(database))
            val subcategories = PromptSubcategoryService(PromptSubcategoryRepository(database))

            dataSource("prompt-category-cross-slice-raw").use { rawSource ->
                rawSource.connection.use { raw ->
                    raw.autoCommit = false
                    lockCategoryRow(raw, id = 2)

                    runBlocking {
                        // Moving "Fourth" to the front rewrites the positions of all four
                        // categories, and it writes them in the new display order: 4, 1, 2, 3. The
                        // held row 2 stops it in the middle of that sequence.
                        val reorder =
                            async(Dispatchers.IO) {
                                categories.reorder(ReorderInput(sourceId = 4, targetId = 1))
                            }
                        awaitBlockedCategoryWriters(rawSource, expected = 1)

                        // The move needs categories 3 and 4 — the second of which the reorder holds
                        // as soon as it locks the rows it happens to write instead of all of them.
                        val move =
                            async(Dispatchers.IO) {
                                subcategories.update(
                                    id = 1,
                                    input = PromptSubcategoryInput(categoryId = 4, name = "Moving"),
                                )
                            }
                        awaitSettledCategoryWriter(rawSource, writer = move)
                        raw.rollback()

                        assertIs<OperationResult.Success<List<PromptCategory>>>(reorder.await())
                        assertIs<OperationResult.Success<PromptSubcategory>>(move.await())
                    }
                }
            }

            assertEquals(
                listOf("Fourth" to 1, "First" to 2, "Second" to 3, "Third" to 4),
                PromptTestSchema.orderedCategories(dataSource),
            )
            assertEquals(
                emptyList(),
                PromptTestSchema.orderedSubcategories(dataSource, categoryId = 3),
            )
            assertEquals(
                listOf("Moving" to 1),
                PromptTestSchema.orderedSubcategories(dataSource, categoryId = 4),
            )
        }
    }

    @Test
    fun `two subcategory moves in opposite directions between two categories serialize`() {
        migratedDataSource("prompt-category-opposite-moves-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedCategories(dataSource, "Portraits", "Animals")
            PromptTestSchema.seedSubcategories(dataSource, categoryId = 1, "Alpha", "Gamma")
            PromptTestSchema.seedSubcategories(dataSource, categoryId = 2, "Beta", "Delta")
            val subcategories =
                PromptSubcategoryService(
                    PromptSubcategoryRepository(Database.connect(datasource = dataSource))
                )

            dataSource("prompt-category-opposite-moves-raw").use { rawSource ->
                rawSource.connection.use { raw ->
                    raw.autoCommit = false
                    // The lower of the two ids, which both writers therefore ask for first.
                    lockCategoryRow(raw, id = 1)

                    runBlocking {
                        val outbound =
                            async(Dispatchers.IO) {
                                subcategories.update(
                                    id = 1,
                                    input = PromptSubcategoryInput(categoryId = 2, name = "Alpha"),
                                )
                            }
                        val inbound =
                            async(Dispatchers.IO) {
                                subcategories.update(
                                    id = 3,
                                    input = PromptSubcategoryInput(categoryId = 1, name = "Beta"),
                                )
                            }
                        // Both writers queue on the same first row. A writer that took its rows in
                        // the direction of its own move would hold the second row here instead.
                        awaitBlockedCategoryWriters(rawSource, expected = 2)
                        raw.rollback()

                        assertIs<OperationResult.Success<PromptSubcategory>>(outbound.await())
                        assertIs<OperationResult.Success<PromptSubcategory>>(inbound.await())
                    }
                }
            }

            assertEquals(
                listOf("Gamma" to 1, "Beta" to 2),
                PromptTestSchema.orderedSubcategories(dataSource, categoryId = 1),
            )
            assertEquals(
                listOf("Delta" to 1, "Alpha" to 2),
                PromptTestSchema.orderedSubcategories(dataSource, categoryId = 2),
            )
        }
    }

    /** Holds the category row [id] until the connection ends its transaction. */
    private fun lockCategoryRow(
        connection: Connection,
        id: Long,
    ) {
        connection.createStatement().use { statement ->
            statement.execute("SELECT id FROM voenix.prompt_categories WHERE id = $id FOR UPDATE")
        }
    }

    /** Waits until [expected] statements wait for a category row lock. */
    private suspend fun awaitBlockedCategoryWriters(
        dataSource: DataSource,
        expected: Int,
    ) {
        withTimeout(BLOCKED_WRITER_TIMEOUT_MILLIS) {
            while (blockedCategoryWriters(dataSource) < expected) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * Waits until [writer] either waits for a category row lock next to the writer already blocked,
     * or is through. Both outcomes are what the test wants to release the held row on: the second
     * one is the writer that never needed a row of the other one.
     */
    private suspend fun awaitSettledCategoryWriter(
        dataSource: DataSource,
        writer: Deferred<*>,
    ) {
        withTimeout(BLOCKED_WRITER_TIMEOUT_MILLIS) {
            while (!writer.isCompleted && blockedCategoryWriters(dataSource) < 2) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun blockedCategoryWriters(dataSource: DataSource): Int =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock'
                      AND query ILIKE '%prompt_categories%'
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
        }

    private companion object {
        const val BLOCKED_WRITER_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}
