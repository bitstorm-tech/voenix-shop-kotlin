package shop.voenix.prompt

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether the Flyway migration builds a schema that actually enforces the prompt invariants on an
 * empty database.
 *
 * Every rule is asserted through the behavior it produces — a rejected write and its SQL state —
 * and never through a constraint name, so renaming a constraint stays the free change it should be.
 * The position rule is asserted twice on purpose: once for the statement PostgreSQL accepts and
 * once for the COMMIT that rejects it, because that deferral is what lets the write path leave a
 * statement-time `23505` unmapped.
 */
internal class PromptSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates the prompt tables with their reference, price, and mapping rules`() {
        migratedDataSource("prompt-schema-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            dataSource.connection.use { connection ->
                seedPromptStructure(connection)

                assertOrderingAnchors(connection)
                assertPromptColumnRules(connection)
                assertPriceRules(connection)
                assertMappingRules(connection)
            }

            assertMappingsFollowTheirPrompt(dataSource)
            assertPromptPositionsAreCheckedAtCommit(dataSource)
        }
    }

    private fun seedPromptStructure(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO voenix.value_added_taxes (id, name, percent, is_default)
                VALUES (1, 'Standard', 19, TRUE);
                INSERT INTO voenix.prices
                    (id, purchase_vat_id, purchase_calculation_mode, purchase_active_row,
                     purchase_price_input_cents, purchase_cost_input_cents, purchase_cost_percent,
                     sales_vat_id, sales_calculation_mode, sales_active_row,
                     sales_margin_input_cents, sales_margin_percent, sales_total_input_cents)
                VALUES (1, 1, 'NET', 'COST', 0, 0, 0, 1, 'GROSS', 'TOTAL', 0, 0, 499);
                INSERT INTO voenix.prompt_categories (id, name, position) VALUES (1, 'Portraits', 1);
                INSERT INTO voenix.prompt_subcategories (id, category_id, name, position)
                VALUES (1, 1, 'Kids', 1);
                INSERT INTO voenix.prompt_slots (id, name, position) VALUES (1, 'Style', 1);
                INSERT INTO voenix.prompt_slot_variants (id, slot_id, name, prompt)
                VALUES (1, 1, 'Watercolor', 'in watercolor');
                INSERT INTO voenix.prompts
                    (id, position, title, prompt_text, category_id, subcategory_id, price_id,
                     active, archived)
                VALUES (1, 1, 'Watercolor portrait', 'Turn the photo into art.', 1, 1, 1,
                    TRUE, FALSE);
                INSERT INTO voenix.prompt_slot_variant_mappings (prompt_id, slot_variant_id)
                VALUES (1, 1);
                """
                    .trimIndent()
            )
        }
    }

    /** The three anchor rows the position writers lock exist, and no fourth sequence may. */
    private fun assertOrderingAnchors(connection: Connection) {
        val sequences =
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT sequence FROM voenix.prompt_ordering ORDER BY 1")
                    .use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(rows.getString("sequence"))
                            }
                        }
                    }
            }
        assertEquals(listOf("CATEGORY", "PROMPT", "SLOT"), sequences)
        assertSqlState(
            "23514",
            connection,
            "INSERT INTO voenix.prompt_ordering (sequence) VALUES ('SUBCATEGORY')",
        )
    }

    private fun assertPromptColumnRules(connection: Connection) {
        // The position is positive, and the title is bounded although the legacy column was not.
        assertSqlState("23514", connection, promptInsert(id = 2, position = 0))
        assertSqlState("22001", connection, promptInsert(id = 2, title = "a".repeat(256)))
        // The prompt text is NOT NULL: the legacy column was nullable and the writes rejected
        // blanks anyway, so the compensation for a null could go.
        assertSqlState(
            "23502",
            connection,
            """
            INSERT INTO voenix.prompts (id, position, title, prompt_text, category_id,
                active, archived)
            VALUES (2, 2, 'No text', NULL, 1, TRUE, FALSE)
            """
                .trimIndent(),
        )
        assertSqlState("23503", connection, promptInsert(id = 2, categoryId = 404))
    }

    /**
     * A price belongs to exactly one prompt, and the prompt that owns it holds it: the owner is
     * deleted first, never the price.
     */
    private fun assertPriceRules(connection: Connection) {
        assertSqlState("23505", connection, promptInsert(id = 2, priceId = 1))
        assertSqlState("23503", connection, promptInsert(id = 2, priceId = 404))
        assertSqlState("23503", connection, "DELETE FROM voenix.prices WHERE id = 1")
    }

    private fun assertMappingRules(connection: Connection) {
        // One variant is mapped to one prompt once; the pair is the key.
        assertSqlState(
            "23505",
            connection,
            """
            INSERT INTO voenix.prompt_slot_variant_mappings (prompt_id, slot_variant_id)
            VALUES (1, 1)
            """
                .trimIndent(),
        )
        assertSqlState(
            "23503",
            connection,
            """
            INSERT INTO voenix.prompt_slot_variant_mappings (prompt_id, slot_variant_id)
            VALUES (1, 404)
            """
                .trimIndent(),
        )
        // A variant a prompt still uses cannot be deleted; a prompt takes its mappings with it.
        assertSqlState("23503", connection, "DELETE FROM voenix.prompt_slot_variants WHERE id = 1")
    }

    /** Deleting a prompt cascades to its mappings, which is why prompts have no in-use answer. */
    private fun assertMappingsFollowTheirPrompt(dataSource: DataSource) {
        PromptTestSchema.execute(dataSource, "DELETE FROM voenix.prompts WHERE id = 1")
        assertEquals(emptyList(), PromptTestSchema.mappedSlotVariantIds(dataSource, promptId = 1))
    }

    /**
     * Makes two prompts share a position and asserts that PostgreSQL accepts the statement and
     * rejects the COMMIT.
     */
    private fun assertPromptPositionsAreCheckedAtCommit(dataSource: DataSource) {
        PromptTestSchema.execute(
            dataSource,
            """
            INSERT INTO voenix.prompts (id, position, title, prompt_text, category_id,
                active, archived)
            VALUES (2, 2, 'First', 'text', 1, TRUE, FALSE),
                   (3, 3, 'Second', 'text', 1, TRUE, FALSE)
            """
                .trimIndent(),
        )
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("UPDATE voenix.prompts SET position = 3 WHERE id = 2")
                }
                val failure = assertFailsWith<SQLException> { connection.commit() }
                assertEquals("23505", failure.sqlState)
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    private fun promptInsert(
        id: Long,
        position: Int = 2,
        title: String = "Second prompt",
        categoryId: Long = 1,
        priceId: Long? = null,
    ): String =
        """
        INSERT INTO voenix.prompts (id, position, title, prompt_text, category_id, price_id,
            active, archived)
        VALUES ($id, $position, '$title', 'text', $categoryId, ${priceId ?: "NULL"}, TRUE, FALSE)
        """
            .trimIndent()

    private fun assertSqlState(
        expected: String,
        connection: Connection,
        sql: String,
    ) = PromptTestSchema.assertSqlState(expected, connection, sql)
}
