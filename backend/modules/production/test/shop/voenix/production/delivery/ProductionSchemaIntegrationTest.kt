package shop.voenix.production.delivery

import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import shop.voenix.testing.PostgresIntegrationTest

internal class ProductionSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway enforces the request job and delivery identities and counters`() {
        migratedDataSource("production-schema-test").use { dataSource ->
            insertSupplier(dataSource, id = 1, name = "Alpha")
            insertDestination(dataSource, id = 1)
            dataSource.connection.use { connection ->
                connection.execute(
                    "INSERT INTO voenix.production_requests (id, order_id) VALUES (1, 10)"
                )
                connection.execute(
                    "INSERT INTO voenix.production_jobs (id, request_id, supplier_id, file_name) " +
                        "VALUES (1, 1, 1, 'ORD-10.pdf')"
                )
                connection.execute(
                    "INSERT INTO voenix.production_deliveries " +
                        "(id, production_job_id, destination_id) VALUES (1, 1, 1)"
                )

                mapOf(
                        // Unique identities: order, request+supplier, job+destination.
                        "INSERT INTO voenix.production_requests (order_id) VALUES (10)" to "23505",
                        "INSERT INTO voenix.production_jobs (request_id, supplier_id, file_name) " +
                            "VALUES (1, 1, 'ORD-10.pdf')" to "23505",
                        "INSERT INTO voenix.production_deliveries " +
                            "(production_job_id, destination_id) VALUES (1, 1)" to "23505",
                        // Counter and positivity checks.
                        "INSERT INTO voenix.production_requests (order_id) VALUES (0)" to "23514",
                        "INSERT INTO voenix.production_requests (order_id, attempt_count) " +
                            "VALUES (11, -1)" to "23514",
                        "INSERT INTO voenix.production_jobs " +
                            "(request_id, supplier_id, file_name, generation_attempt_count) " +
                            "VALUES (1, 2, 'ORD-10.pdf', -1)" to "23514",
                        "INSERT INTO voenix.production_deliveries " +
                            "(production_job_id, destination_id, attempt_count) " +
                            "VALUES (1, 2, -1)" to "23514",
                        // Artifact metadata is all or nothing: digest and timestamp together.
                        "INSERT INTO voenix.production_jobs " +
                            "(request_id, supplier_id, file_name, content_sha256) " +
                            "VALUES (1, 2, 'ORD-10.pdf', 'abc')" to "23514",
                        "INSERT INTO voenix.production_jobs " +
                            "(request_id, supplier_id, file_name, generated_at) " +
                            "VALUES (1, 2, 'ORD-10.pdf', CURRENT_TIMESTAMP)" to "23514",
                        // Referenced rows cannot be hard-deleted.
                        "DELETE FROM voenix.production_destinations WHERE id = 1" to "23503",
                        "DELETE FROM voenix.suppliers WHERE id = 1" to "23503",
                        "DELETE FROM voenix.production_requests WHERE id = 1" to "23503",
                    )
                    .forEach { (sql, expectedSqlState) ->
                        val failure =
                            kotlin.test.assertFailsWith<SQLException>(sql) {
                                connection.execute(sql)
                            }
                        assertEquals(expectedSqlState, failure.sqlState, sql)
                    }
            }
        }
    }

    private fun Connection.execute(sql: String) {
        createStatement().use { statement -> statement.executeUpdate(sql) }
    }
}
