package shop.voenix.email

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import shop.voenix.testing.PostgresIntegrationTest

internal class EmailSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates reference only lifecycle schema and operational indexes`() {
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
                assertTrue("ix_email_jobs_claim" in indexes)
                assertTrue("ix_email_jobs_lease_recovery" in indexes)
                assertTrue("ix_email_jobs_pending_retries" in indexes)
                assertTrue("uq_email_jobs_idempotency_hash" in indexes)

                listOf(
                        invalidInsert("'UNKNOWN'", "'PENDING'", "NULL", "NULL", "NULL", "0"),
                        invalidInsert(
                            "'ORDER_CONFIRMATION'",
                            "'UNKNOWN'",
                            "NULL",
                            "NULL",
                            "NULL",
                            "0",
                        ),
                        invalidInsert(
                            "'ORDER_CONFIRMATION'",
                            "'PROCESSING'",
                            "NULL",
                            "NULL",
                            "NULL",
                            "0",
                        ),
                        invalidInsert(
                            "'ORDER_CONFIRMATION'",
                            "'TRANSMITTED'",
                            "NULL",
                            "NULL",
                            "NULL",
                            "0",
                        ),
                        invalidInsert(
                            "'ORDER_CONFIRMATION'",
                            "'PENDING'",
                            "NULL",
                            "NULL",
                            "NULL",
                            "-1",
                        ),
                    )
                    .forEach { sql ->
                        val failure =
                            assertFailsWith<SQLException> {
                                connection.createStatement().use { it.executeUpdate(sql) }
                            }
                        assertEquals("23514", failure.sqlState)
                    }
            }
        }
    }

    private fun invalidInsert(
        kind: String,
        status: String,
        lease: String,
        leaseExpiry: String,
        completed: String,
        retries: String,
    ): String =
        """
        INSERT INTO voenix.email_jobs
            (idempotency_hash, intent_hash, email_kind, source_id, status, retry_count,
             lease_token, lease_expires_at, completed_at)
        VALUES (decode(repeat('00', 32), 'hex'), decode(repeat('11', 32), 'hex'),
                $kind, 1, $status, $retries, $lease, $leaseExpiry, $completed)
        """
            .trimIndent()

    private companion object {
        val EXPECTED_COLUMNS =
            setOf(
                "id",
                "idempotency_hash",
                "intent_hash",
                "email_kind",
                "source_id",
                "status",
                "retry_count",
                "next_attempt_at",
                "lease_token",
                "lease_expires_at",
                "last_error_code",
                "last_error_message",
                "created_at",
                "updated_at",
                "completed_at",
            )
    }
}
