package shop.voenix.prompt

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether the Flyway migration builds a schema that actually enforces the category invariants on an
 * empty database.
 *
 * Every rule is asserted through the behavior it produces — a rejected write and its SQL state —
 * and never through a constraint name, so renaming a constraint stays the free change it should be.
 * The two position rules are asserted twice on purpose: once for the statement PostgreSQL accepts
 * and once for the COMMIT that rejects it, because that deferral is what lets a statement-time
 * `23505` mean "name conflict" and nothing else.
 */
internal class PromptCategorySchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates the category tables with their unique and reference rules`() {
        migratedDataSource("prompt-category-schema-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            dataSource.connection.use { connection ->
                seedCategoryStructure(connection)

                assertCategoryRules(connection)
                assertSubcategoryRules(connection)
                assertPromptReferenceRules(connection)
            }

            assertCategoryPositionsAreCheckedAtCommit(dataSource)
            assertSubcategoryPositionsAreCheckedAtCommit(dataSource)
        }
    }

    private fun seedCategoryStructure(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO voenix.prompt_categories (id, name, position)
                VALUES (1, 'Portraits', 1), (2, 'Animals', 2);
                INSERT INTO voenix.prompt_subcategories (id, category_id, name, position)
                VALUES (1, 1, 'Kids', 1), (2, 1, 'Adults', 2), (3, 2, 'Dogs', 1);
                INSERT INTO voenix.prompts
                    (id, position, title, prompt_text, category_id, subcategory_id, active, archived)
                VALUES (1, 1, 'Watercolor', 'Turn the photo into art.', 1, 1, TRUE, FALSE);
                """
                    .trimIndent()
            )
        }
    }

    private fun assertCategoryRules(connection: Connection) {
        // Names are unique case-insensitively.
        assertSqlState(
            "23505",
            connection,
            "INSERT INTO voenix.prompt_categories (id, name, position) VALUES (3, 'pORTRAITS', 3)",
        )
        assertSqlState(
            "23514",
            connection,
            "INSERT INTO voenix.prompt_categories (id, name, position) VALUES (3, 'Free', 0)",
        )

        // Subcategories and prompts hold their category with ON DELETE RESTRICT.
        assertSqlState("23503", connection, "DELETE FROM voenix.prompt_categories WHERE id = 2")
        assertSqlState("23503", connection, "DELETE FROM voenix.prompt_categories WHERE id = 1")
    }

    private fun assertSubcategoryRules(connection: Connection) {
        // Unique per category, case-insensitively — the legacy index was case-sensitive.
        assertSqlState(
            "23505",
            connection,
            """
            INSERT INTO voenix.prompt_subcategories (id, category_id, name, position)
            VALUES (4, 1, 'kIDS', 3)
            """
                .trimIndent(),
        )
        assertSqlState(
            "23503",
            connection,
            """
            INSERT INTO voenix.prompt_subcategories (id, category_id, name, position)
            VALUES (4, 404, 'Orphan', 1)
            """
                .trimIndent(),
        )
        // A prompt still uses subcategory 1.
        assertSqlState("23503", connection, "DELETE FROM voenix.prompt_subcategories WHERE id = 1")

        // The same name in another category is allowed: the rule counts per category.
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO voenix.prompt_subcategories (id, category_id, name, position)
                VALUES (4, 2, 'Adults', 2)
                """
                    .trimIndent()
            )
        }
    }

    /**
     * The composite foreign key is what makes "a prompt's subcategory belongs to the prompt's
     * category" a database fact: neither a mismatched pair nor moving a used subcategory out of its
     * category is accepted.
     */
    private fun assertPromptReferenceRules(connection: Connection) {
        assertSqlState(
            "23503",
            connection,
            """
            INSERT INTO voenix.prompts
                (id, position, title, prompt_text, category_id, subcategory_id, active, archived)
            VALUES (2, 2, 'Mismatch', 'text', 2, 1, TRUE, FALSE)
            """
                .trimIndent(),
        )
        assertSqlState(
            "23503",
            connection,
            "UPDATE voenix.prompt_subcategories SET category_id = 2 WHERE id = 1",
        )
    }

    /**
     * Makes two categories share a position and asserts that PostgreSQL accepts the statement and
     * rejects the COMMIT.
     */
    private fun assertCategoryPositionsAreCheckedAtCommit(dataSource: DataSource) =
        assertPositionIsCheckedAtCommit(
            dataSource,
            "UPDATE voenix.prompt_categories SET position = 2 WHERE id = 1",
        )

    /** The same for two subcategories of one category. */
    private fun assertSubcategoryPositionsAreCheckedAtCommit(dataSource: DataSource) =
        assertPositionIsCheckedAtCommit(
            dataSource,
            "UPDATE voenix.prompt_subcategories SET position = 2 WHERE id = 1",
        )

    private fun assertPositionIsCheckedAtCommit(
        dataSource: DataSource,
        sql: String,
    ) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement -> statement.executeUpdate(sql) }
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
    ) = PromptTestSchema.assertSqlState(expected, connection, sql)
}
