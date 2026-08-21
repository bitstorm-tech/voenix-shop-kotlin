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
            resetProductionTables(dataSource)
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

    @Test
    fun `flyway enforces the per-channel destination detail tables`() {
        migratedDataSource("production-destination-channel-schema-test").use { dataSource ->
            resetProductionTables(dataSource)
            insertSupplier(dataSource, id = 3, name = "Gamma")
            insertDestination(dataSource, id = 1, supplierId = 3)
            insertSpodDestination(dataSource, id = 2, supplierId = 3)
            dataSource.connection.use { connection ->
                mapOf(
                        // The base table now knows two channels and nothing else.
                        "INSERT INTO voenix.production_destinations " +
                            "(id, supplier_id, channel, label) " +
                            "VALUES (3, 3, 'FTP', 'Unknown channel')" to "23514",
                        // A detail row belongs to a base row of its own channel: the composite
                        // foreign key pins the pair, the constant column pins the channel.
                        "INSERT INTO voenix.production_destination_sftp " +
                            "(id, host, username, password, host_key_fingerprint, " +
                            "timeout_seconds) " +
                            "VALUES (2, 'sftp.example.com', 'user', 'secret', 'SHA256:x', 30)" to
                            "23503",
                        "INSERT INTO voenix.production_destination_spod " +
                            "(id, environment, access_token, timeout_seconds) " +
                            "VALUES (1, 'STAGING', 'token', 30)" to "23503",
                        "INSERT INTO voenix.production_destination_spod " +
                            "(id, channel, environment, access_token, timeout_seconds) " +
                            "VALUES (2, 'SFTP', 'STAGING', 'token', 30)" to "23514",
                        // The ranges the columns brought with them from the base table.
                        "UPDATE voenix.production_destination_sftp SET port = 0 " +
                            "WHERE id = 1" to "23514",
                        "UPDATE voenix.production_destination_sftp SET timeout_seconds = 3601 " +
                            "WHERE id = 1" to "23514",
                        "UPDATE voenix.production_destination_spod SET timeout_seconds = 0 " +
                            "WHERE id = 2" to "23514",
                        // The environment is the bounded pair `SpodEnvironment` knows.
                        "UPDATE voenix.production_destination_spod SET environment = 'DEV' " +
                            "WHERE id = 2" to "23514",
                    )
                    .forEach { (sql, expectedSqlState) ->
                        val failure =
                            kotlin.test.assertFailsWith<SQLException>(sql) {
                                connection.execute(sql)
                            }
                        assertEquals(expectedSqlState, failure.sqlState, sql)
                    }

                // At most one *enabled* SPOD destination per supplier; a disabled successor may
                // be prepared next to it.
                val secondEnabledSpod =
                    kotlin.test.assertFailsWith<SQLException> {
                        connection.execute(
                            "INSERT INTO voenix.production_destinations " +
                                "(id, supplier_id, channel, label) " +
                                "VALUES (4, 3, 'SPOD', 'Second enabled')"
                        )
                    }
                assertEquals("23505", secondEnabledSpod.sqlState)
                connection.execute(
                    "INSERT INTO voenix.production_destinations " +
                        "(id, supplier_id, channel, label, enabled) " +
                        "VALUES (5, 3, 'SPOD', 'Prepared successor', false)"
                )

                // A detail row is part of its destination and goes with it.
                connection.execute("DELETE FROM voenix.production_destinations WHERE id = 2")
                assertEquals(0, connection.count("voenix.production_destination_spod"))
                assertEquals(1, connection.count("voenix.production_destination_sftp"))
            }
        }
    }

    /**
     * The channel migration moves configured SFTP destinations instead of asking an admin to
     * re-enter them, so it is verified where it happens: a schema of its own is migrated to the
     * version before the split, filled with an old-shape destination, and then migrated across it.
     */
    @Test
    fun `the channel migration copies existing sftp destinations into the detail table`() {
        dataSource("production-destination-copy-test", COPY_SCHEMA).use { dataSource ->
            migrate(dataSource, COPY_SCHEMA, target = VERSION_BEFORE_CHANNEL_SPLIT)
            dataSource.connection.use { connection ->
                connection.execute("INSERT INTO $COPY_SCHEMA.suppliers (id, name) VALUES (1, 'A')")
                connection.execute(
                    "INSERT INTO $COPY_SCHEMA.production_destinations " +
                        "(id, supplier_id, channel, label, host, port, username, password, " +
                        "host_key_fingerprint, remote_path, timeout_seconds) " +
                        "VALUES (1, 1, 'SFTP', 'Producer drop', 'sftp.example.com', 2222, " +
                        "'voenix', 'super-secret', 'SHA256:fingerprint', '/drop', 45)"
                )
            }

            migrate(dataSource, COPY_SCHEMA)

            dataSource.connection.use { connection ->
                assertEquals(
                    listOf(
                        "1",
                        "SFTP",
                        "sftp.example.com",
                        "2222",
                        "voenix",
                        "super-secret",
                        "SHA256:fingerprint",
                        "/drop",
                        "45",
                    ),
                    connection.row(
                        "SELECT id, channel, host, port, username, password, " +
                            "host_key_fingerprint, remote_path, timeout_seconds " +
                            "FROM $COPY_SCHEMA.production_destination_sftp WHERE id = 1"
                    ),
                )
                assertEquals(
                    listOf("1", "SFTP", "Producer drop"),
                    connection.row(
                        "SELECT id, channel, label FROM $COPY_SCHEMA.production_destinations " +
                            "WHERE id = 1"
                    ),
                    "the base row keeps its identity across the split",
                )
            }
        }
    }

    private fun Connection.row(sql: String): List<String?> =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next()) { "No row for $sql" }
                (1..rows.metaData.columnCount).map { column -> rows.getString(column) }
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

    private companion object {
        /** A schema of its own, so the shared `voenix` schema keeps its migrated state. */
        const val COPY_SCHEMA = "production_channel_copy"

        /** The last version before `V22__production_destination_channels.sql`. */
        const val VERSION_BEFORE_CHANNEL_SPLIT = "21"
    }
}
