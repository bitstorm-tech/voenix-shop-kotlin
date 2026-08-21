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
                    "INSERT INTO voenix.production_jobs " +
                        "(id, request_id, supplier_id, fulfillment_channel, file_name) " +
                        "VALUES (1, 1, 1, 'SFTP', 'ORD-10.pdf')"
                )
                connection.execute(
                    "INSERT INTO voenix.production_deliveries " +
                        "(id, production_job_id, destination_id) VALUES (1, 1, 1)"
                )

                mapOf(
                        // Unique identities: order, request+supplier, job+destination.
                        "INSERT INTO voenix.production_requests (order_id) VALUES (10)" to "23505",
                        "INSERT INTO voenix.production_jobs " +
                            "(request_id, supplier_id, fulfillment_channel, file_name) " +
                            "VALUES (1, 1, 'SFTP', 'ORD-10.pdf')" to "23505",
                        "INSERT INTO voenix.production_deliveries " +
                            "(production_job_id, destination_id) VALUES (1, 1)" to "23505",
                        // Counter and positivity checks.
                        "INSERT INTO voenix.production_requests (order_id) VALUES (0)" to "23514",
                        "INSERT INTO voenix.production_requests (order_id, attempt_count) " +
                            "VALUES (11, -1)" to "23514",
                        "INSERT INTO voenix.production_jobs " +
                            "(request_id, supplier_id, fulfillment_channel, file_name, " +
                            "generation_attempt_count) " +
                            "VALUES (1, 2, 'SFTP', 'ORD-10.pdf', -1)" to "23514",
                        "INSERT INTO voenix.production_deliveries " +
                            "(production_job_id, destination_id, attempt_count) " +
                            "VALUES (1, 2, -1)" to "23514",
                        // Artifact metadata is all or nothing: digest and timestamp together.
                        "INSERT INTO voenix.production_jobs " +
                            "(request_id, supplier_id, fulfillment_channel, file_name, " +
                            "content_sha256) " +
                            "VALUES (1, 2, 'SFTP', 'ORD-10.pdf', 'abc')" to "23514",
                        "INSERT INTO voenix.production_jobs " +
                            "(request_id, supplier_id, fulfillment_channel, file_name, " +
                            "generated_at) " +
                            "VALUES (1, 2, 'SFTP', 'ORD-10.pdf', CURRENT_TIMESTAMP)" to "23514",
                        // The channel is one of the two the code knows, and it is never guessed:
                        // there is no default, so a job without one is refused outright.
                        "INSERT INTO voenix.production_jobs " +
                            "(request_id, supplier_id, fulfillment_channel, file_name) " +
                            "VALUES (1, 2, 'CARRIER_PIGEON', 'ORD-10.pdf')" to "23514",
                        "INSERT INTO voenix.production_jobs (request_id, supplier_id, file_name) " +
                            "VALUES (1, 2, 'ORD-10.pdf')" to "23502",
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
    fun `flyway enforces the channel the shipping record and the item snapshot of a job`() {
        migratedDataSource("production-shipping-schema-test").use { dataSource ->
            resetProductionTables(dataSource)
            insertOrders(dataSource, 20)
            insertSupplier(dataSource, id = 2, name = "Beta")
            dataSource.connection.use { connection ->
                connection.execute(
                    "INSERT INTO voenix.production_requests (id, order_id) VALUES (2, 20)"
                )
                connection.execute(
                    "INSERT INTO voenix.production_jobs " +
                        "(id, request_id, supplier_id, fulfillment_channel, file_name) " +
                        "VALUES (2, 2, 2, 'SFTP', 'ORD-20.pdf')"
                )

                mapOf(
                        // Shipping data belongs to a shipped job only.
                        "UPDATE voenix.production_jobs SET shipping_carrier = 'DHL' " +
                            "WHERE id = 2" to "23514",
                        "UPDATE voenix.production_jobs SET tracking_number = '1Z' " +
                            "WHERE id = 2" to "23514",
                        "UPDATE voenix.production_jobs SET shipped_by_channel = 'SPOD' " +
                            "WHERE id = 2" to "23514",
                        "UPDATE voenix.production_jobs " +
                            "SET shipping_carrier_reported = 'Deutsche Post' " +
                            "WHERE id = 2" to "23514",
                        // A shipment has one reporter: a person or a channel, never both.
                        "UPDATE voenix.production_jobs SET prepared_at = CURRENT_TIMESTAMP, " +
                            "shipped_at = CURRENT_TIMESTAMP, shipped_by_channel = 'SPOD', " +
                            "shipped_by_user_id = 1 WHERE id = 2" to "23514",
                        // And the reporting channel is as bounded as the carrier list.
                        "UPDATE voenix.production_jobs SET prepared_at = CURRENT_TIMESTAMP, " +
                            "shipped_at = CURRENT_TIMESTAMP, shipped_by_channel = 'PIGEON' " +
                            "WHERE id = 2" to "23514",
                        // A job is shipped only after it was prepared, whichever channel
                        // prepared it — the database refuses what the ship guard refuses.
                        "UPDATE voenix.production_jobs SET shipped_at = CURRENT_TIMESTAMP " +
                            "WHERE id = 2" to "23514",
                        // The carrier list is bounded.
                        "UPDATE voenix.production_jobs " +
                            "SET shipped_at = CURRENT_TIMESTAMP, prepared_at = " +
                            "CURRENT_TIMESTAMP, shipping_carrier = 'PIGEON' " +
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

                // The channel-reported shipment: no user, the channel instead, and the partner's
                // own carrier spelling beside the bounded one.
                connection.execute(
                    "UPDATE voenix.production_jobs SET prepared_at = CURRENT_TIMESTAMP, " +
                        "shipped_at = CURRENT_TIMESTAMP, shipped_by_channel = 'SPOD', " +
                        "shipping_carrier = 'OTHER', " +
                        "shipping_carrier_reported = 'SpodExpress', " +
                        "tracking_number = '00340434' WHERE id = 2"
                )
                connection.execute(
                    "UPDATE voenix.production_jobs SET shipped_by_channel = NULL, " +
                        "shipping_carrier_reported = NULL, shipping_carrier = 'DHL' WHERE id = 2"
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

    /**
     * The channel migration of the *jobs* has a backfill too, and it decides what happens to every
     * job that is already in flight when the deployment lands: it was pushed over SFTP, and it was
     * ready to be shipped exactly when its PDF existed. Verified where it happens — a schema of its
     * own is migrated to the version before the split, filled with a generated job, and migrated
     * across it.
     */
    @Test
    fun `the job channel migration backfills sftp and prepared_at`() {
        dataSource("production-job-channel-backfill-test", BACKFILL_SCHEMA).use { dataSource ->
            migrate(dataSource, BACKFILL_SCHEMA, target = VERSION_BEFORE_JOB_CHANNELS)
            dataSource.connection.use { connection ->
                connection.execute(
                    "INSERT INTO $BACKFILL_SCHEMA.suppliers (id, name) " +
                        "VALUES (1, 'A'), (2, 'B')"
                )
                connection.execute(
                    "INSERT INTO $BACKFILL_SCHEMA.carts (id, guest_session_token, status) " +
                        "VALUES (1, 'guest-1', 'CHECKED_OUT')"
                )
                connection.execute(
                    "INSERT INTO $BACKFILL_SCHEMA.orders (id, cart_id, guest_session_token, " +
                        "access_token, status, shipping_first_name, shipping_last_name, " +
                        "shipping_street, shipping_house_number, shipping_postal_code, " +
                        "shipping_city, shipping_country, billing_first_name, " +
                        "billing_last_name, billing_street, billing_house_number, " +
                        "billing_postal_code, billing_city, billing_country, email, " +
                        "subtotal_cents, shipping_cost_cents, discount_cents, total_cents) " +
                        "VALUES (1, 1, 'guest-1', '${"access-token-1".padEnd(43, 'x')}', 'PAID', " +
                        "'Erika', 'Musterfrau', 'Musterstraße', '1', '12345', 'Berlin', 'DE', " +
                        "'Erika', 'Musterfrau', 'Musterstraße', '1', '12345', 'Berlin', 'DE', " +
                        "'kundin@example.com', 1000, 490, 0, 1490)"
                )
                connection.execute(
                    "INSERT INTO $BACKFILL_SCHEMA.production_requests (id, order_id) VALUES (1, 1)"
                )
                connection.execute(
                    "INSERT INTO $BACKFILL_SCHEMA.production_jobs " +
                        "(id, request_id, supplier_id, file_name, content_sha256, generated_at) " +
                        "VALUES (1, 1, 1, 'ORD-1.pdf', repeat('0', 64), " +
                        "TIMESTAMPTZ '2026-08-01 10:00:00+02'), " +
                        "(2, 1, 2, 'ORD-1.pdf', NULL, NULL)"
                )
            }

            migrate(dataSource, BACKFILL_SCHEMA)

            dataSource.connection.use { connection ->
                assertEquals(
                    listOf("SFTP", "true", "false"),
                    connection.row(
                        "SELECT fulfillment_channel, " +
                            "(prepared_at IS NOT DISTINCT FROM generated_at)::text, " +
                            "(prepared_at IS NULL)::text " +
                            "FROM $BACKFILL_SCHEMA.production_jobs WHERE id = 1"
                    ),
                    "a generated job was prepared the moment its document existed",
                )
                assertEquals(
                    listOf("SFTP", "true"),
                    connection.row(
                        "SELECT fulfillment_channel, (prepared_at IS NULL)::text " +
                            "FROM $BACKFILL_SCHEMA.production_jobs WHERE id = 2"
                    ),
                    "a job still waiting for its document stays unprepared",
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

        /** A schema of its own for the job-channel backfill, for the same reason. */
        const val BACKFILL_SCHEMA = "production_job_channel_backfill"

        /** The last version before `V23__production_job_channels.sql`. */
        const val VERSION_BEFORE_JOB_CHANNELS = "22"
    }
}
