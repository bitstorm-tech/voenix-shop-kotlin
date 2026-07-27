package shop.voenix.article

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.persistence.ArticleCategoryRepository
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether the Flyway migration builds a taxonomy that actually enforces the category and
 * subcategory invariants on an empty database.
 *
 * Every rule is asserted through the behavior it produces — a rejected write and its SQL state —
 * and never through a constraint name, so renaming a constraint stays the free change it should be.
 * The position rules are asserted twice on purpose: once for the statement that PostgreSQL accepts
 * and once for the COMMIT that rejects it, because the whole reorder design depends on the check
 * happening at commit time.
 */
internal class ArticleTaxonomySchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates the article taxonomy with its seed lock anchor and unique rules`() {
        migratedDataSource("article-taxonomy-schema-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            dataSource.connection.use { connection ->
                seedTaxonomy(connection)

                assertArticleTypesAreSeeded(connection)
                assertTaxonomyStateIsASingleRow(connection)
                assertCategoryRules(connection)
                assertSubcategoryRules(connection)
            }

            assertCategoryPositionsAreCheckedAtCommit(dataSource)
            assertSubcategoryPositionsAreCheckedAtCommit(dataSource)
        }
    }

    @Test
    fun `exposed maps the migrated schema and never creates one itself`() {
        // This data source deliberately skips Flyway and the schema it creates.
        dataSource("article-exposed-mapping-test").use { dataSource ->
            val repository = ArticleCategoryRepository(Database.connect(datasource = dataSource))

            val failure = assertFailsWith<SQLException> { runBlocking { repository.list() } }
            val states =
                generateSequence(failure as Throwable?) { throwable -> throwable.cause }
                    .filterIsInstance<SQLException>()
                    .map { exception -> exception.sqlState }
                    .toList()
            assertTrue("42P01" in states, "Expected an undefined-table failure, got $states")

            dataSource.connection.use { connection ->
                val created =
                    connection
                        .prepareStatement(
                            """
                            SELECT count(*)
                            FROM pg_tables
                            WHERE schemaname = 'public' AND tablename = 'article_categories'
                            """
                                .trimIndent()
                        )
                        .use { statement ->
                            statement.executeQuery().use { rows ->
                                rows.next()
                                rows.getInt(1)
                            }
                        }
                assertEquals(0, created)
            }
        }
    }

    private fun seedTaxonomy(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO voenix.article_categories (id, name, position)
                VALUES (1, 'Mugs', 1), (2, 'Posters', 2);
                INSERT INTO voenix.article_subcategories (id, category_id, name, position)
                VALUES (1, 1, 'Classic', 1), (2, 1, 'Premium', 2);
                """
                    .trimIndent()
            )
        }
    }

    private fun assertArticleTypesAreSeeded(connection: Connection) {
        val types =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT article_type FROM voenix.article_types").use { rows
                    ->
                    buildList { while (rows.next()) add(rows.getString("article_type")) }
                }
            }
        assertEquals(listOf("MUG"), types)

        assertSqlState(
            "23503",
            connection,
            "INSERT INTO voenix.article_identities (id, article_type) VALUES (90, 'POSTER')",
        )
    }

    private fun assertTaxonomyStateIsASingleRow(connection: Connection) {
        val rowCount =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM voenix.article_taxonomy_state").use {
                    rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        assertEquals(1, rowCount)

        assertSqlState(
            "23514",
            connection,
            "INSERT INTO voenix.article_taxonomy_state (id) VALUES (2)",
        )
        assertSqlState(
            "23505",
            connection,
            "INSERT INTO voenix.article_taxonomy_state (id) VALUES (1)",
        )
    }

    private fun assertCategoryRules(connection: Connection) {
        assertSqlState(
            "23505",
            connection,
            "INSERT INTO voenix.article_categories (id, name, position) VALUES (3, 'mUGS', 3)",
        )
        assertSqlState(
            "23514",
            connection,
            "INSERT INTO voenix.article_categories (id, name, position) VALUES (3, 'Free', 0)",
        )
    }

    private fun assertSubcategoryRules(connection: Connection) {
        assertSqlState(
            "23505",
            connection,
            """
            INSERT INTO voenix.article_subcategories (id, category_id, name, position)
            VALUES (3, 1, 'CLASSIC', 3)
            """
                .trimIndent(),
        )
        assertSqlState(
            "23514",
            connection,
            """
            INSERT INTO voenix.article_subcategories (id, category_id, name, position)
            VALUES (3, 1, 'Free', 0)
            """
                .trimIndent(),
        )
        assertSqlState(
            "23503",
            connection,
            """
            INSERT INTO voenix.article_subcategories (id, category_id, name, position)
            VALUES (3, 404, 'Orphan', 3)
            """
                .trimIndent(),
        )

        // The same name in another category is a different name.
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO voenix.article_subcategories (id, category_id, name, position)
                VALUES (3, 2, 'Classic', 1)
                """
                    .trimIndent()
            )
        }

        // A category that subcategories reference cannot be deleted.
        assertSqlState("23503", connection, "DELETE FROM voenix.article_categories WHERE id = 2")

        connection.createStatement().use { statement ->
            statement.execute("DELETE FROM voenix.article_subcategories WHERE id = 3")
        }
    }

    private fun assertCategoryPositionsAreCheckedAtCommit(dataSource: DataSource) {
        assertDeferredPositionConflict(
            dataSource,
            "UPDATE voenix.article_categories SET position = 2 WHERE id = 1",
        )
    }

    private fun assertSubcategoryPositionsAreCheckedAtCommit(dataSource: DataSource) {
        assertDeferredPositionConflict(
            dataSource,
            "UPDATE voenix.article_subcategories SET position = 2 WHERE id = 1",
        )
    }

    /**
     * Runs [conflictingUpdate], which makes two rows share a position, and asserts that PostgreSQL
     * accepts the statement and rejects the COMMIT. That is what lets a reorder rewrite the
     * sequence in one phase.
     */
    private fun assertDeferredPositionConflict(
        dataSource: DataSource,
        conflictingUpdate: String,
    ) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(conflictingUpdate)
                }
                val failure = assertFailsWith<SQLException> { connection.commit() }
                assertEquals("23505", failure.sqlState)
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    private fun assertSqlState(
        expected: String,
        connection: Connection,
        sql: String,
    ) = ArticleTestSchema.assertSqlState(expected, connection, sql)
}
