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
     * Stores the VAT entry every price refers to and returns nothing: after [reset] the identity
     * sequence starts again, so the entry is always id 1.
     */
    fun seedVat(dataSource: DataSource) {
        execute(
            dataSource,
            """
            INSERT INTO voenix.value_added_taxes (name, percent, is_default)
            VALUES ('Standard', 19, TRUE)
            """
                .trimIndent(),
        )
    }

    /** Stores [names] as suppliers, numbered from id 1 in the given order. */
    fun seedSuppliers(
        dataSource: DataSource,
        vararg names: String,
    ) {
        val values = names.joinToString(", ") { name -> "('$name')" }
        execute(dataSource, "INSERT INTO voenix.suppliers (name) VALUES $values")
    }

    /** The stored mugs as `name to position` pairs, in display order. */
    fun orderedMugs(dataSource: DataSource): List<Pair<String, Int>> =
        query(dataSource, "SELECT name, position FROM voenix.article_mugs ORDER BY position, id") {
            rows ->
            rows.getString("name") to rows.getInt("position")
        }

    /** The ids of every stored price row, so a test can prove that none was left behind. */
    fun storedPriceIds(dataSource: DataSource): List<Long> =
        query(dataSource, "SELECT id FROM voenix.prices ORDER BY id") { rows -> rows.getLong("id") }

    /** The stored variants of one mug as `name to example image` pairs, in id order. */
    fun storedVariants(
        dataSource: DataSource,
        articleId: Long,
    ): List<Pair<String, String?>> =
        query(
            dataSource,
            """
            SELECT name, example_image_filename
            FROM voenix.article_mug_variants
            WHERE article_id = $articleId
            ORDER BY id
            """
                .trimIndent(),
        ) { rows ->
            rows.getString("name") to rows.getString("example_image_filename")
        }

    /** The number of rows in [table], for the assertions about what a rollback left behind. */
    fun rowCount(
        dataSource: DataSource,
        table: String,
    ): Int =
        query(dataSource, "SELECT count(*) AS total FROM voenix.$table") { rows ->
                rows.getInt("total")
            }
            .single()

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

    /** Runs [sql] and maps every row with [row]. */
    private fun <T> query(
        dataSource: DataSource,
        sql: String,
        row: (java.sql.ResultSet) -> T,
    ): List<T> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(row(rows))
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
