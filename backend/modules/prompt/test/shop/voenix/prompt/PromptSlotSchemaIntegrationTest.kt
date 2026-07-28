package shop.voenix.prompt

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.prompt.persistence.PromptSlotRepository
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether the Flyway migration builds a prompt schema that actually enforces the slot invariants on
 * an empty database.
 *
 * Every rule is asserted through the behavior it produces — a rejected write and its SQL state —
 * and never through a constraint name, so renaming a constraint stays the free change it should be.
 * The position rule is asserted twice on purpose: once for the statement PostgreSQL accepts and
 * once for the COMMIT that rejects it, because the deferred check is what lets a statement-time
 * `23505` mean "name conflict" and nothing else.
 */
internal class PromptSlotSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates the slot tables with their anchor rows and unique rules`() {
        migratedDataSource("prompt-slot-schema-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            dataSource.connection.use { connection ->
                seedSlots(connection)

                assertOrderingAnchorsAreSeeded(connection)
                assertSlotRules(connection)
                assertVariantRules(connection)
            }

            assertSlotPositionsAreCheckedAtCommit(dataSource)
        }
    }

    @Test
    fun `exposed maps the migrated schema and never creates one itself`() {
        // This data source deliberately skips Flyway and the schema it creates.
        dataSource("prompt-exposed-mapping-test").use { dataSource ->
            val repository = PromptSlotRepository(Database.connect(datasource = dataSource))

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
                            WHERE schemaname = 'public' AND tablename = 'prompt_slots'
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

    private fun seedSlots(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO voenix.prompt_slots (id, name, position)
                VALUES (1, 'Background', 1), (2, 'Style', 2);
                INSERT INTO voenix.prompt_slot_variants (id, slot_id, name, prompt)
                VALUES (1, 1, 'Meadow', 'in a meadow'), (2, 2, 'Watercolor', 'in watercolor');
                """
                    .trimIndent()
            )
        }
    }

    /** The three global position sequences each have exactly one anchor row, and no fourth. */
    private fun assertOrderingAnchorsAreSeeded(connection: Connection) {
        val sequences =
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT sequence FROM voenix.prompt_ordering ORDER BY sequence")
                    .use { rows ->
                        buildList { while (rows.next()) add(rows.getString("sequence")) }
                    }
            }
        assertEquals(listOf("CATEGORY", "PROMPT", "SLOT"), sequences)

        assertSqlState(
            "23514",
            connection,
            "INSERT INTO voenix.prompt_ordering (sequence) VALUES ('SUBCATEGORY')",
        )
        assertSqlState(
            "23505",
            connection,
            "INSERT INTO voenix.prompt_ordering (sequence) VALUES ('SLOT')",
        )
    }

    private fun assertSlotRules(connection: Connection) {
        assertSqlState(
            "23505",
            connection,
            "INSERT INTO voenix.prompt_slots (id, name, position) VALUES (3, 'bACKGROUND', 3)",
        )
        assertSqlState(
            "23514",
            connection,
            "INSERT INTO voenix.prompt_slots (id, name, position) VALUES (3, 'Free', 0)",
        )

        // A slot that still has variants cannot be deleted.
        assertSqlState("23503", connection, "DELETE FROM voenix.prompt_slots WHERE id = 1")
    }

    private fun assertVariantRules(connection: Connection) {
        // Variant names are unique across all slots, not per slot.
        assertSqlState(
            "23505",
            connection,
            """
            INSERT INTO voenix.prompt_slot_variants (id, slot_id, name, prompt)
            VALUES (3, 2, 'mEADOW', 'in a meadow')
            """
                .trimIndent(),
        )
        assertSqlState(
            "23503",
            connection,
            """
            INSERT INTO voenix.prompt_slot_variants (id, slot_id, name, prompt)
            VALUES (3, 404, 'Orphan', 'nowhere')
            """
                .trimIndent(),
        )
        assertSqlState(
            "23502",
            connection,
            """
            INSERT INTO voenix.prompt_slot_variants (id, slot_id, name)
            VALUES (3, 1, 'Textless')
            """
                .trimIndent(),
        )
    }

    /**
     * Makes two slots share a position and asserts that PostgreSQL accepts the statement and
     * rejects the COMMIT. That deferral is what makes a `23505` raised while a slot write runs
     * provably a name conflict.
     */
    private fun assertSlotPositionsAreCheckedAtCommit(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "UPDATE voenix.prompt_slots SET position = 2 WHERE id = 1"
                    )
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
    ) = PromptTestSchema.assertSqlState(expected, connection, sql)
}
