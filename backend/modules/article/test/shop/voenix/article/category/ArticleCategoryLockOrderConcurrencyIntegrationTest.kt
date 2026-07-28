package shop.voenix.article.category

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
import shop.voenix.article.ArticleTestSchema
import shop.voenix.article.RecordingPublicImageStorage
import shop.voenix.article.ReorderInput
import shop.voenix.article.persistence.ArticleCategoryRepository
import shop.voenix.article.persistence.ArticleSubcategoryRepository
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether every writer of category rows really takes them in the same order.
 *
 * Category rows are locked by two slices that never wait on the same anchor: the category writers,
 * which queue on `article_category_ordering`, and the subcategory writers, which do not. Only the
 * shared ascending id order keeps them from waiting on each other's rows, and a violation of it is
 * a deadlock — SQL state `40P01`, which nothing maps, so it surfaces as a failed operation.
 *
 * Both tests below build that situation deterministically: a raw connection holds one category row,
 * the writers are started so that each of them ends up needing a row another one holds, and only
 * then is the raw connection released.
 */
internal class ArticleCategoryLockOrderConcurrencyIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a category reorder and a subcategory move between categories do not wait on each other`() {
        migratedDataSource("article-category-cross-slice-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "First", "Second", "Third", "Fourth")
            ArticleTestSchema.seedSubcategories(dataSource, categoryId = 3, "Moving")
            val database = Database.connect(datasource = dataSource)
            val categories = ArticleCategoryService(ArticleCategoryRepository(database))
            val subcategories =
                ArticleSubcategoryService(
                    ArticleSubcategoryRepository(database),
                    RecordingPublicImageStorage(),
                )

            dataSource("article-category-cross-slice-raw").use { rawSource ->
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
                                    input =
                                        ArticleSubcategoryInput(categoryId = 4, name = "Moving"),
                                )
                            }
                        awaitSettledCategoryWriter(rawSource, writer = move)
                        raw.rollback()

                        assertIs<OperationResult.Success<List<ArticleCategory>>>(reorder.await())
                        assertIs<OperationResult.Success<ArticleSubcategory>>(move.await())
                    }
                }
            }

            assertEquals(
                listOf("Fourth" to 1, "First" to 2, "Second" to 3, "Third" to 4),
                ArticleTestSchema.orderedCategories(dataSource),
            )
            assertEquals(
                emptyList(),
                ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 3),
            )
            assertEquals(
                listOf("Moving" to 1),
                ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 4),
            )
        }
    }

    @Test
    fun `two subcategory moves in opposite directions between two categories serialize`() {
        migratedDataSource("article-category-opposite-moves-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs", "Cups")
            ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "Alpha", "Gamma")
            ArticleTestSchema.seedSubcategories(dataSource, categoryId = 2, "Beta", "Delta")
            val subcategories =
                ArticleSubcategoryService(
                    ArticleSubcategoryRepository(Database.connect(datasource = dataSource)),
                    RecordingPublicImageStorage(),
                )

            dataSource("article-category-opposite-moves-raw").use { rawSource ->
                rawSource.connection.use { raw ->
                    raw.autoCommit = false
                    // The lower of the two ids, which both writers therefore ask for first.
                    lockCategoryRow(raw, id = 1)

                    runBlocking {
                        val outbound =
                            async(Dispatchers.IO) {
                                subcategories.update(
                                    id = 1,
                                    input = ArticleSubcategoryInput(categoryId = 2, name = "Alpha"),
                                )
                            }
                        val inbound =
                            async(Dispatchers.IO) {
                                subcategories.update(
                                    id = 3,
                                    input = ArticleSubcategoryInput(categoryId = 1, name = "Beta"),
                                )
                            }
                        // Both writers queue on the same first row. A writer that took its rows in
                        // the direction of its own move would hold the second row here instead.
                        awaitBlockedCategoryWriters(rawSource, expected = 2)
                        raw.rollback()

                        assertIs<OperationResult.Success<ArticleSubcategory>>(outbound.await())
                        assertIs<OperationResult.Success<ArticleSubcategory>>(inbound.await())
                    }
                }
            }

            assertEquals(
                listOf("Gamma" to 1, "Beta" to 2),
                ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 1),
            )
            assertEquals(
                listOf("Delta" to 1, "Alpha" to 2),
                ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 2),
            )
        }
    }

    /** Holds the category row [id] until the connection ends its transaction. */
    private fun lockCategoryRow(
        connection: Connection,
        id: Long,
    ) {
        connection.createStatement().use { statement ->
            statement.execute("SELECT id FROM voenix.article_categories WHERE id = $id FOR UPDATE")
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
                      AND query ILIKE '%article_categories%'
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
