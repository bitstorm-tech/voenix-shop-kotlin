package shop.voenix.article.taxonomy

import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleTestSchema
import shop.voenix.article.ReorderInput
import shop.voenix.article.persistence.ArticleCategoryRepository
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether the display order survives concurrent writers.
 *
 * Every writer that changes a position first locks the taxonomy anchor row, so the tests below
 * describe what that lock buys: two reorders queue instead of interleaving, a create cannot reuse a
 * position a reorder is about to write, and only a writer that ignores the lock can still lose the
 * deferred unique check at COMMIT — which the module answers with a retryable conflict.
 */
internal class ArticleCategoryConcurrencyIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `two concurrent reorders serialize and leave a dense sequence`() {
        migratedDataSource("article-category-concurrent-reorder-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "First", "Second", "Third", "Fourth")
            val categories = categoryService(dataSource)

            runBlocking {
                val results = coroutineScope {
                    listOf(
                            async(Dispatchers.IO) {
                                categories.reorder(ReorderInput(sourceId = 4, targetId = 1))
                            },
                            async(Dispatchers.IO) {
                                categories.reorder(ReorderInput(sourceId = 1, targetId = 3))
                            },
                        )
                        .map { deferred -> deferred.await() }
                }

                results.forEach { result ->
                    val order = assertIs<OperationResult.Success<List<ArticleCategory>>>(result)
                    assertEquals(listOf(1, 2, 3, 4), order.value.map(ArticleCategory::position))
                }
            }

            assertDenseOrder(dataSource, "First", "Second", "Third", "Fourth")
        }
    }

    @Test
    fun `a create running next to a reorder cannot corrupt the sequence`() {
        migratedDataSource("article-category-concurrent-create-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "First", "Second", "Third", "Fourth")
            val categories = categoryService(dataSource)

            runBlocking {
                val results = coroutineScope {
                    listOf(
                            async(Dispatchers.IO) {
                                categories.create(ArticleCategoryInput(name = "Fifth"))
                            },
                            async(Dispatchers.IO) {
                                categories.reorder(ReorderInput(sourceId = 4, targetId = 1))
                            },
                        )
                        .map { deferred -> deferred.await() }
                }

                assertTrue(
                    results.all { result -> result is OperationResult.Success },
                    "Both writers hold the ordering lock, so neither may fail: $results",
                )
            }

            assertDenseOrder(dataSource, "First", "Second", "Third", "Fourth", "Fifth")
        }
    }

    @Test
    fun `concurrent case-variant creates leave one row and one conflict`() {
        migratedDataSource("article-category-concurrent-name-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            val categories = categoryService(dataSource)

            runBlocking {
                val results = coroutineScope {
                    listOf(
                            async(Dispatchers.IO) {
                                categories.create(ArticleCategoryInput(name = "Race"))
                            },
                            async(Dispatchers.IO) {
                                categories.create(ArticleCategoryInput(name = "RACE"))
                            },
                        )
                        .map { deferred -> deferred.await() }
                }

                assertEquals(1, results.count { result -> result is OperationResult.Success })
                assertEquals(1, results.count { result -> result === OperationResult.Conflict })
            }

            assertEquals(1, ArticleTestSchema.orderedCategories(dataSource).size)
        }
    }

    @Test
    fun `a position written outside the ordering lock makes the reorder fail at commit`() {
        migratedDataSource("article-category-commit-conflict-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "First", "Second", "Third", "Fourth")
            val categories = categoryService(dataSource)

            dataSource("article-category-commit-conflict-raw").use { rawSource ->
                rawSource.connection.use { raw ->
                    raw.autoCommit = false
                    // A writer that ignores the ordering lock rotates the whole sequence. Every
                    // statement is accepted, because the unique rule is only checked at COMMIT.
                    raw.createStatement().use { statement ->
                        statement.execute(
                            """
                            UPDATE voenix.article_categories SET position = 2 WHERE id = 1;
                            UPDATE voenix.article_categories SET position = 3 WHERE id = 2;
                            UPDATE voenix.article_categories SET position = 4 WHERE id = 3;
                            UPDATE voenix.article_categories SET position = 1 WHERE id = 4;
                            """
                                .trimIndent()
                        )
                    }

                    runBlocking {
                        val reorder =
                            async(Dispatchers.IO) {
                                categories.reorder(ReorderInput(sourceId = 4, targetId = 3))
                            }
                        // The reorder has read the old order and now waits for the rotated rows.
                        awaitBlockedCategoryWriter(rawSource)
                        raw.commit()

                        assertEquals(OperationResult.Conflict, reorder.await())
                    }
                }
            }

            // The rejected transaction rolled back completely, so the rotation is what stands.
            assertEquals(
                listOf("Fourth" to 1, "First" to 2, "Second" to 3, "Third" to 4),
                ArticleTestSchema.orderedCategories(dataSource),
            )
        }
    }

    private fun categoryService(dataSource: DataSource): ArticleCategoryService =
        ArticleCategoryService(ArticleCategoryRepository(Database.connect(datasource = dataSource)))

    /** Asserts that the stored positions are 1..n and that every expected name is still there. */
    private fun assertDenseOrder(
        dataSource: DataSource,
        vararg expectedNames: String,
    ) {
        val stored = ArticleTestSchema.orderedCategories(dataSource)
        assertEquals(expectedNames.size, stored.size)
        assertEquals((1..expectedNames.size).toList(), stored.map { (_, position) -> position })
        assertEquals(expectedNames.toSet(), stored.map { (name, _) -> name }.toSet())
    }

    /** Waits until a statement is blocked on a row lock of the category table. */
    private suspend fun awaitBlockedCategoryWriter(dataSource: DataSource) {
        withTimeout(BLOCKED_WRITER_TIMEOUT_MILLIS) {
            while (!hasBlockedCategoryWriter(dataSource)) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun hasBlockedCategoryWriter(dataSource: DataSource): Boolean =
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
                        rows.getInt(1) > 0
                    }
                }
        }

    private companion object {
        const val BLOCKED_WRITER_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}
