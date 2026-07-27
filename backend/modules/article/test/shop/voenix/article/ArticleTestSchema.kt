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

    /**
     * Stores [names] as subcategories of [categoryId], numbered from position 1 in the given order.
     */
    fun seedSubcategories(
        dataSource: DataSource,
        categoryId: Long,
        vararg names: String,
    ) {
        val values =
            names
                .mapIndexed { index, name -> "($categoryId, '$name', ${index + 1})" }
                .joinToString(", ")
        execute(
            dataSource,
            "INSERT INTO voenix.article_subcategories (category_id, name, position) VALUES $values",
        )
    }

    /**
     * Stores a mug that uses [subcategoryId] of [categoryId]. The mug slice arrives in a later
     * ticket, so the row is written directly: what the tests need from it is only that an article
     * references the subcategory, which is what the composite foreign key of `article_mugs`
     * enforces.
     */
    fun seedMugUsing(
        dataSource: DataSource,
        categoryId: Long,
        subcategoryId: Long,
    ) {
        execute(
            dataSource,
            """
            INSERT INTO voenix.article_identities (id, article_type) VALUES (1, 'MUG');
            INSERT INTO voenix.article_mugs (
                id, position, name, description_short, description_long, active,
                category_id, subcategory_id
            )
            VALUES (1, 1, 'Mug', 'Short', 'Long', FALSE, $categoryId, $subcategoryId);
            """
                .trimIndent(),
        )
    }

    /** The stored subcategories as `name to position` pairs, per category in display order. */
    fun orderedSubcategories(
        dataSource: DataSource,
        categoryId: Long,
    ): List<Pair<String, Int>> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT name, position
                    FROM voenix.article_subcategories
                    WHERE category_id = $categoryId
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
