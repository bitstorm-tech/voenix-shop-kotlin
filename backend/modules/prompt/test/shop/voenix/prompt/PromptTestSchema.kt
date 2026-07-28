package shop.voenix.prompt

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Shared helpers for the prompt integration tests. Every test class runs against the same
 * PostgreSQL schema, so each one starts by emptying the prompt tables.
 */
internal object PromptTestSchema {
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
            DELETE FROM voenix.prompt_slot_variant_mappings;
            DELETE FROM voenix.prompts;
            DELETE FROM voenix.prompt_slot_variants;
            DELETE FROM voenix.prompt_slots;
            DELETE FROM voenix.prompt_subcategories;
            DELETE FROM voenix.prompt_categories;
            ALTER TABLE voenix.prompt_slots ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.prompt_slot_variants ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.prompts ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.prompt_categories ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.prompt_subcategories ALTER COLUMN id RESTART WITH 1;
            """
                .trimIndent(),
        )
    }

    /** Stores [names] as slots, numbered from position 1 in the given order. */
    fun seedSlots(
        dataSource: DataSource,
        vararg names: String,
    ) {
        val values =
            names.mapIndexed { index, name -> "('$name', ${index + 1})" }.joinToString(", ")
        execute(dataSource, "INSERT INTO voenix.prompt_slots (name, position) VALUES $values")
    }

    /** Stores [names] as variants of [slotId]. */
    fun seedVariants(
        dataSource: DataSource,
        slotId: Long,
        vararg names: String,
    ) {
        val values = names.joinToString(", ") { name -> "($slotId, '$name', 'text of $name')" }
        execute(
            dataSource,
            "INSERT INTO voenix.prompt_slot_variants (slot_id, name, prompt) VALUES $values",
        )
    }

    /**
     * Stores one prompt that uses [variantId], so that the "still in use" answers of the delete
     * routes have something real to be blocked by. The prompt needs a category, which is seeded
     * with it.
     */
    fun seedPromptUsing(
        dataSource: DataSource,
        variantId: Long,
    ) {
        execute(
            dataSource,
            """
            INSERT INTO voenix.prompt_categories (id, name, position) VALUES (1, 'Portraits', 1);
            INSERT INTO voenix.prompts (id, position, title, prompt_text, category_id, active, archived)
            VALUES (1, 1, 'Watercolor portrait', 'Turn the photo into art.', 1, TRUE, FALSE);
            INSERT INTO voenix.prompt_slot_variant_mappings (prompt_id, slot_variant_id)
            VALUES (1, $variantId);
            """
                .trimIndent(),
        )
    }

    /** The stored slots as `name to position` pairs, in display order. */
    fun orderedSlots(dataSource: DataSource): List<Pair<String, Int>> =
        query(dataSource, "SELECT name, position FROM voenix.prompt_slots ORDER BY position, id") {
            rows ->
            rows.getString("name") to rows.getInt("position")
        }

    /** The stored variants as `name to slot id` pairs, in id order. */
    fun storedVariants(dataSource: DataSource): List<Pair<String, Long>> =
        query(
            dataSource,
            "SELECT name, slot_id FROM voenix.prompt_slot_variants ORDER BY id",
        ) { rows ->
            rows.getString("name") to rows.getLong("slot_id")
        }

    /** Runs [sql] and maps every row with [row]. */
    private fun <T> query(
        dataSource: DataSource,
        sql: String,
        row: (ResultSet) -> T,
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
