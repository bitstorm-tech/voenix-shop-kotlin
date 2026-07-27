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
import shop.voenix.article.RecordingPublicImageStorage
import shop.voenix.article.ReorderInput
import shop.voenix.article.persistence.ArticleSubcategoryRepository
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether the display order of a category survives concurrent writers.
 *
 * Subcategory positions are dense per category, so the anchor every position writer queues on is
 * the category row itself. These tests describe what that buys: two reorders in one category
 * serialize, a create cannot reuse a position a reorder is about to write, and only a writer that
 * ignores the lock can still lose the deferred unique check at COMMIT.
 */
internal class ArticleSubcategoryConcurrencyIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `two concurrent reorders in one category serialize and leave a dense sequence`() {
        migratedDataSource("article-subcategory-concurrent-reorder-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs")
            ArticleTestSchema.seedSubcategories(
                dataSource,
                categoryId = 1,
                "First",
                "Second",
                "Third",
                "Fourth",
            )
            val subcategories = subcategoryService(dataSource)

            runBlocking {
                val results = coroutineScope {
                    listOf(
                            async(Dispatchers.IO) {
                                subcategories.reorder(ReorderInput(sourceId = 4, targetId = 1))
                            },
                            async(Dispatchers.IO) {
                                subcategories.reorder(ReorderInput(sourceId = 1, targetId = 3))
                            },
                        )
                        .map { deferred -> deferred.await() }
                }

                results.forEach { result ->
                    val order = assertIs<OperationResult.Success<List<ArticleSubcategory>>>(result)
                    assertEquals(listOf(1, 2, 3, 4), order.value.map(ArticleSubcategory::position))
                }
            }

            assertDenseOrder(dataSource, categoryId = 1, "First", "Second", "Third", "Fourth")
        }
    }

    @Test
    fun `a create running next to a reorder cannot corrupt the sequence of its category`() {
        migratedDataSource("article-subcategory-concurrent-create-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs")
            ArticleTestSchema.seedSubcategories(
                dataSource,
                categoryId = 1,
                "First",
                "Second",
                "Third",
                "Fourth",
            )
            val subcategories = subcategoryService(dataSource)

            runBlocking {
                val results = coroutineScope {
                    listOf(
                            async(Dispatchers.IO) {
                                subcategories.create(
                                    ArticleSubcategoryInput(categoryId = 1, name = "Fifth")
                                )
                            },
                            async(Dispatchers.IO) {
                                subcategories.reorder(ReorderInput(sourceId = 4, targetId = 1))
                            },
                        )
                        .map { deferred -> deferred.await() }
                }

                assertTrue(
                    results.all { result -> result is OperationResult.Success },
                    "Both writers hold the ordering lock, so neither may fail: $results",
                )
            }

            assertDenseOrder(
                dataSource,
                categoryId = 1,
                "First",
                "Second",
                "Third",
                "Fourth",
                "Fifth",
            )
        }
    }

    @Test
    fun `concurrent case-variant creates in one category leave one row and one conflict`() {
        migratedDataSource("article-subcategory-concurrent-name-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs")
            val subcategories = subcategoryService(dataSource)

            runBlocking {
                val results = coroutineScope {
                    listOf(
                            async(Dispatchers.IO) {
                                subcategories.create(
                                    ArticleSubcategoryInput(categoryId = 1, name = "Race")
                                )
                            },
                            async(Dispatchers.IO) {
                                subcategories.create(
                                    ArticleSubcategoryInput(categoryId = 1, name = "RACE")
                                )
                            },
                        )
                        .map { deferred -> deferred.await() }
                }

                assertEquals(1, results.count { result -> result is OperationResult.Success })
                assertEquals(1, results.count { result -> result === OperationResult.Conflict })
            }

            assertEquals(1, ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 1).size)
        }
    }

    @Test
    fun `a position written outside the ordering lock makes the reorder fail at commit`() {
        migratedDataSource("article-subcategory-commit-conflict-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            ArticleTestSchema.seedCategories(dataSource, "Mugs")
            ArticleTestSchema.seedSubcategories(
                dataSource,
                categoryId = 1,
                "First",
                "Second",
                "Third",
                "Fourth",
            )
            val subcategories = subcategoryService(dataSource)

            dataSource("article-subcategory-commit-conflict-raw").use { rawSource ->
                rawSource.connection.use { raw ->
                    raw.autoCommit = false
                    // A writer that ignores the ordering lock rotates the whole sequence. Every
                    // statement is accepted, because the unique rule is only checked at COMMIT.
                    raw.createStatement().use { statement ->
                        statement.execute(
                            """
                            UPDATE voenix.article_subcategories SET position = 2 WHERE id = 1;
                            UPDATE voenix.article_subcategories SET position = 3 WHERE id = 2;
                            UPDATE voenix.article_subcategories SET position = 4 WHERE id = 3;
                            UPDATE voenix.article_subcategories SET position = 1 WHERE id = 4;
                            """
                                .trimIndent()
                        )
                    }

                    runBlocking {
                        val reorder =
                            async(Dispatchers.IO) {
                                subcategories.reorder(ReorderInput(sourceId = 4, targetId = 3))
                            }
                        // The reorder has read the old order and now waits for the rotated rows.
                        awaitBlockedSubcategoryWriter(rawSource)
                        raw.commit()

                        assertEquals(OperationResult.Conflict, reorder.await())
                    }
                }
            }

            // The rejected transaction rolled back completely, so the rotation is what stands.
            assertEquals(
                listOf("Fourth" to 1, "First" to 2, "Second" to 3, "Third" to 4),
                ArticleTestSchema.orderedSubcategories(dataSource, categoryId = 1),
            )
        }
    }

    private fun subcategoryService(dataSource: DataSource): ArticleSubcategoryService =
        ArticleSubcategoryService(
            ArticleSubcategoryRepository(Database.connect(datasource = dataSource)),
            RecordingPublicImageStorage(),
        )

    /** Asserts that the stored positions are 1..n and that every expected name is still there. */
    private fun assertDenseOrder(
        dataSource: DataSource,
        categoryId: Long,
        vararg expectedNames: String,
    ) {
        val stored = ArticleTestSchema.orderedSubcategories(dataSource, categoryId)
        assertEquals(expectedNames.size, stored.size)
        assertEquals((1..expectedNames.size).toList(), stored.map { (_, position) -> position })
        assertEquals(expectedNames.toSet(), stored.map { (name, _) -> name }.toSet())
    }

    /** Waits until a statement is blocked on a row lock of the subcategory table. */
    private suspend fun awaitBlockedSubcategoryWriter(dataSource: DataSource) {
        withTimeout(BLOCKED_WRITER_TIMEOUT_MILLIS) {
            while (!hasBlockedSubcategoryWriter(dataSource)) {
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun hasBlockedSubcategoryWriter(dataSource: DataSource): Boolean =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock'
                      AND query ILIKE '%article_subcategories%'
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
