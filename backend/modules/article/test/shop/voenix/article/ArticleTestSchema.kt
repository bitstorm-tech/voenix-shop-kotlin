package shop.voenix.article

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Shared helpers for the article integration tests. Every test class runs against the same
 * PostgreSQL schema, so each one starts by emptying the article tables.
 */
internal object ArticleTestSchema {
    /**
     * Asserts that [sql] is rejected with the SQL state [expected]. Schema rules are asserted by
     * the state they produce, never by the name of the constraint that produced it.
     */
    fun assertSqlState(
        expected: String,
        connection: Connection,
        sql: String,
    ) {
        val failure =
            assertFailsWith<SQLException> {
                connection.createStatement().use { statement -> statement.executeUpdate(sql) }
            }
        assertEquals(expected, failure.sqlState, "Unexpected SQL state for: $sql")
    }

    /** Empties every table this migration owns, plus the rows the tests seed around them. */
    fun reset(dataSource: DataSource) {
        execute(
            dataSource,
            """
            DELETE FROM voenix.article_mug_variants;
            DELETE FROM voenix.article_variant_identities;
            DELETE FROM voenix.article_mugs;
            DELETE FROM voenix.article_identities;
            DELETE FROM voenix.article_subcategories;
            DELETE FROM voenix.article_categories;
            DELETE FROM voenix.prices;
            DELETE FROM voenix.suppliers;
            DELETE FROM voenix.value_added_taxes;
            ALTER TABLE voenix.article_categories ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.article_subcategories ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.article_identities ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.article_variant_identities ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.prices ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.suppliers ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.value_added_taxes ALTER COLUMN id RESTART WITH 1;
            """
                .trimIndent(),
        )
    }

    /**
     * Stores [names] as categories, numbered from position 1 in the given order. The ids are
     * generated, so they run from 1 after [reset] and the sequence keeps working for the writes a
     * test performs afterwards.
     */
    fun seedCategories(
        dataSource: DataSource,
        vararg names: String,
    ) {
        val values =
            names.mapIndexed { index, name -> "('$name', ${index + 1})" }.joinToString(", ")
        execute(dataSource, "INSERT INTO voenix.article_categories (name, position) VALUES $values")
    }

    /** The stored categories as `name to position` pairs, in display order. */
    fun orderedCategories(dataSource: DataSource): List<Pair<String, Int>> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT name, position
                    FROM voenix.article_categories
                    ORDER BY position, id
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(rows.getString("name") to rows.getInt("position"))
                            }
                        }
                    }
                }
        }

    fun execute(
        dataSource: DataSource,
        sql: String,
    ) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }
}
