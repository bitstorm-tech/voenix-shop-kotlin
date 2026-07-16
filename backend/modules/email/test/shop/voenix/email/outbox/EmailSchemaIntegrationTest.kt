package shop.voenix.email.outbox

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import shop.voenix.testing.PostgresIntegrationTest

internal class EmailSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates the minimal reference only job schema`() {
        migratedDataSource("email-schema-test").use { dataSource ->
            dataSource.connection.use { connection ->
                val columns = buildSet {
                    connection.metaData.getColumns(null, "voenix", "email_jobs", null).use { rows ->
                        while (rows.next()) add(rows.getString("COLUMN_NAME"))
                    }
                }
                assertEquals(EXPECTED_COLUMNS, columns)
                listOf(
                        "recipient_email",
                        "subject",
                        "message_html",
                        "message_text",
                        "template",
                        "auth_token",
                    )
                    .forEach { forbidden -> assertFalse(forbidden in columns) }

                val indexes =
                    connection.createStatement().use { statement ->
                        statement
                            .executeQuery(
                                "SELECT indexname FROM pg_indexes WHERE schemaname='voenix' AND tablename='email_jobs'"
                            )
                            .use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
                    }
                assertTrue("uq_email_jobs_reference" in indexes)
                assertEquals(setOf("pk_email_jobs", "uq_email_jobs_reference"), indexes)

                listOf(
                        invalidInsert("'UNKNOWN'", "1", "0"),
                        invalidInsert("'ORDER_CONFIRMATION'", "0", "0"),
                        invalidInsert("'ORDER_CONFIRMATION'", "1", "-1"),
                    )
                    .forEach { sql ->
                        val failure =
                            assertFailsWith<SQLException> {
                                connection.createStatement().use { it.executeUpdate(sql) }
                            }
                        assertEquals("23514", failure.sqlState)
                    }

                connection.createStatement().use { statement ->
                    statement.executeUpdate(validInsert("'ORDER_CONFIRMATION'", "1"))
                    val failure =
                        assertFailsWith<SQLException> {
                            statement.executeUpdate(validInsert("'ORDER_CONFIRMATION'", "1"))
                        }
                    assertEquals("23505", failure.sqlState)
                }
            }
        }
    }

    private fun invalidInsert(kind: String, sourceId: String, attempts: String): String =
        """
        INSERT INTO voenix.email_jobs (email_kind, source_id, attempt_count)
        VALUES ($kind, $sourceId, $attempts)
        """
            .trimIndent()

    private fun validInsert(kind: String, sourceId: String): String =
        "INSERT INTO voenix.email_jobs (email_kind, source_id) VALUES ($kind, $sourceId)"

    private companion object {
        val EXPECTED_COLUMNS =
            setOf(
                "id",
                "email_kind",
                "source_id",
                "attempt_count",
                "last_error_code",
                "created_at",
                "sent_at",
            )
    }
}
