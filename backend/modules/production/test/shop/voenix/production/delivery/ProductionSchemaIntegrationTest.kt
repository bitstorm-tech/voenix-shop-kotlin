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
            // The two orders the requests below point at; `order_id` is a foreign key since V16.
            insertOrders(dataSource, 10, 11)
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

    @Test
    fun `flyway enforces the shipping record and the item snapshot of a job`() {
        migratedDataSource("production-shipping-schema-test").use { dataSource ->
            resetProductionTables(dataSource)
            insertOrders(dataSource, 20)
            insertSupplier(dataSource, id = 2, name = "Beta")
            dataSource.connection.use { connection ->
                connection.execute(
                    "INSERT INTO voenix.production_requests (id, order_id) VALUES (2, 20)"
                )
                connection.execute(
                    "INSERT INTO voenix.production_jobs (id, request_id, supplier_id, file_name) " +
                        "VALUES (2, 2, 2, 'ORD-20.pdf')"
                )

                mapOf(
                        // Shipping data belongs to a shipped job only.
                        "UPDATE voenix.production_jobs SET shipping_carrier = 'DHL' " +
                            "WHERE id = 2" to "23514",
                        "UPDATE voenix.production_jobs SET tracking_number = '1Z' " +
                            "WHERE id = 2" to "23514",
                        // The carrier list is bounded.
                        "UPDATE voenix.production_jobs " +
                            "SET shipped_at = CURRENT_TIMESTAMP, shipping_carrier = 'PIGEON' " +
                            "WHERE id = 2" to "23514",
                        // Item lines belong to an existing job and count from one upwards.
                        "INSERT INTO voenix.production_job_items " +
                            "(production_job_id, position, article_name, variant_name, quantity) " +
                            "VALUES (999, 1, 'Mug', 'White', 1)" to "23503",
                        "INSERT INTO voenix.production_job_items " +
                            "(production_job_id, position, article_name, variant_name, quantity) " +
                            "VALUES (2, 0, 'Mug', 'White', 1)" to "23514",
                        "INSERT INTO voenix.production_job_items " +
                            "(production_job_id, position, article_name, variant_name, quantity) " +
                            "VALUES (2, 1, 'Mug', 'White', 0)" to "23514",
                    )
                    .forEach { (sql, expectedSqlState) ->
                        val failure =
                            kotlin.test.assertFailsWith<SQLException>(sql) {
                                connection.execute(sql)
                            }
                        assertEquals(expectedSqlState, failure.sqlState, sql)
                    }

                connection.execute(
                    "UPDATE voenix.production_jobs SET shipped_at = CURRENT_TIMESTAMP, " +
                        "shipping_carrier = 'DHL', tracking_number = '00340434' WHERE id = 2"
                )
                connection.execute(
                    "INSERT INTO voenix.production_job_items " +
                        "(production_job_id, position, article_name, variant_name, " +
                        "supplier_article_number, quantity) " +
                        "VALUES (2, 1, 'Mug', 'White', 'SUP-1', 2)"
                )
                val duplicatePosition =
                    kotlin.test.assertFailsWith<SQLException> {
                        connection.execute(
                            "INSERT INTO voenix.production_job_items " +
                                "(production_job_id, position, article_name, variant_name, " +
                                "quantity) VALUES (2, 1, 'Mug', 'Black', 1)"
                        )
                    }
                assertEquals("23505", duplicatePosition.sqlState, "one row per position")

                // The items are parts of the job, so deleting the job takes them with it.
                connection.execute("DELETE FROM voenix.production_jobs WHERE id = 2")
                assertEquals(0, connection.count("voenix.production_job_items"))
            }
        }
    }

    private fun Connection.execute(sql: String) {
        createStatement().use { statement -> statement.executeUpdate(sql) }
    }

    private fun Connection.count(table: String): Int =
        createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
}
