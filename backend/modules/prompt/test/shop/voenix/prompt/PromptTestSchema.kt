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
            DELETE FROM voenix.prices;
            DELETE FROM voenix.value_added_taxes;
            ALTER TABLE voenix.prompt_slots ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.prompt_slot_variants ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.prompts ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.prompt_categories ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.prompt_subcategories ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.prices ALTER COLUMN id RESTART WITH 1;
            ALTER TABLE voenix.value_added_taxes ALTER COLUMN id RESTART WITH 1;
            """
                .trimIndent(),
        )
    }

    /**
     * Stores the VAT entry every price refers to. After [reset] the identity sequence starts again,
     * so the entry is always id 1.
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

    /** The ids of every stored price row, so a test can prove that none was left behind. */
    fun storedPriceIds(dataSource: DataSource): List<Long> =
        query(dataSource, "SELECT id FROM voenix.prices ORDER BY id") { rows -> rows.getLong("id") }

    /** The stored prompts as `title to position` pairs, in display order. */
    fun orderedPrompts(dataSource: DataSource): List<Pair<String, Int>> =
        query(dataSource, "SELECT title, position FROM voenix.prompts ORDER BY position, id") { rows
            ->
            rows.getString("title") to rows.getInt("position")
        }

    /** The slot variants one prompt is mapped to, ascending by id. */
    fun mappedSlotVariantIds(
        dataSource: DataSource,
        promptId: Long,
    ): List<Long> =
        query(
            dataSource,
            """
            SELECT slot_variant_id
            FROM voenix.prompt_slot_variant_mappings
            WHERE prompt_id = $promptId
            ORDER BY slot_variant_id
            """
                .trimIndent(),
        ) { rows ->
            rows.getLong("slot_variant_id")
        }

    /** The price row a prompt points at, or `null` when it has none. */
    fun priceIdOf(
        dataSource: DataSource,
        promptId: Long,
    ): Long? =
        query(dataSource, "SELECT price_id FROM voenix.prompts WHERE id = $promptId") { rows ->
                rows.getLong("price_id").takeUnless { rows.wasNull() }
            }
            .single()

    /** The stored prompt text of one prompt, so a round trip can prove it was not trimmed. */
    fun promptTextOf(
        dataSource: DataSource,
        promptId: Long,
    ): String =
        query(dataSource, "SELECT prompt_text FROM voenix.prompts WHERE id = $promptId") { rows ->
                rows.getString("prompt_text")
            }
            .single()

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

    /** Stores [names] as categories, numbered from position 1 in the given order. */
    fun seedCategories(
        dataSource: DataSource,
        vararg names: String,
    ) {
        val values =
            names.mapIndexed { index, name -> "('$name', ${index + 1})" }.joinToString(", ")
        execute(dataSource, "INSERT INTO voenix.prompt_categories (name, position) VALUES $values")
    }

    /** Stores [names] as subcategories of [categoryId], numbered from position 1 in the order. */
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
            "INSERT INTO voenix.prompt_subcategories (category_id, name, position) VALUES $values",
        )
    }

    /**
     * Stores one prompt in [categoryId], optionally in [subcategoryId], so that the "still in use"
     * answers of the category and subcategory delete routes have something real to be blocked by.
     */
    fun seedPromptIn(
        dataSource: DataSource,
        categoryId: Long,
        subcategoryId: Long? = null,
    ) {
        execute(
            dataSource,
            """
            INSERT INTO voenix.prompts
                (position, title, prompt_text, category_id, subcategory_id, active, archived)
            VALUES (1, 'Watercolor portrait', 'Turn the photo into art.', $categoryId,
                ${subcategoryId ?: "NULL"}, TRUE, FALSE)
            """
                .trimIndent(),
        )
    }

    /** The stored categories as `name to position` pairs, in display order. */
    fun orderedCategories(dataSource: DataSource): List<Pair<String, Int>> =
        query(
            dataSource,
            "SELECT name, position FROM voenix.prompt_categories ORDER BY position, id",
        ) { rows ->
            rows.getString("name") to rows.getInt("position")
        }

    /** The stored subcategories of one category as `name to position` pairs, in display order. */
    fun orderedSubcategories(
        dataSource: DataSource,
        categoryId: Long,
    ): List<Pair<String, Int>> =
        query(
            dataSource,
            """
            SELECT name, position
            FROM voenix.prompt_subcategories
            WHERE category_id = $categoryId
            ORDER BY position, id
            """
                .trimIndent(),
        ) { rows ->
            rows.getString("name") to rows.getInt("position")
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
