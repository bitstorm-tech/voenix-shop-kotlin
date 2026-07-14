package shop.voenix.pricing

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import shop.voenix.testing.PostgresIntegrationTest

class PriceSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates price constraints foreign keys and vat indexes`() {
        migratedDataSource("pricing-schema-integration-test").use { dataSource ->
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        TRUNCATE voenix.prices, voenix.value_added_taxes RESTART IDENTITY CASCADE;
                        INSERT INTO voenix.value_added_taxes
                            (name, percent, description, is_default)
                        VALUES ('Standard', 19, NULL, TRUE);
                        """
                            .trimIndent()
                    )
                }
                assertColumnMetadata(connection)
                assertPercentagePrecisionAndScale(connection)
                assertForeignKeyMetadata(connection)
                assertConstraintMetadata(connection)

                val invalidOverrides =
                    listOf(
                        "purchase_calculation_mode" to "'INVALID'",
                        "purchase_active_row" to "'INVALID'",
                        "sales_calculation_mode" to "'INVALID'",
                        "sales_active_row" to "'INVALID'",
                        "purchase_price_input_cents" to "-1",
                        "purchase_cost_input_cents" to "-1",
                        "purchase_cost_percent" to "-0.01",
                        "sales_total_input_cents" to "-1",
                    )
                invalidOverrides.forEachIndexed { index, (column, value) ->
                    val exception =
                        assertFailsWith<SQLException> {
                            connection.createStatement().use { statement ->
                                statement.executeUpdate(insertSql(id = index + 1, column, value))
                            }
                        }
                    assertEquals("23514", exception.sqlState)
                }

                val foreignKeyFailure =
                    assertFailsWith<SQLException> {
                        connection.createStatement().use { statement ->
                            statement.executeUpdate(insertSql(id = 20, "purchase_vat_id", "404"))
                        }
                    }
                assertEquals("23503", foreignKeyFailure.sqlState)

                connection
                    .prepareStatement(
                        """
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'voenix' AND tablename = 'prices'
                        """
                            .trimIndent()
                    )
                    .use { statement ->
                        statement.executeQuery().use { rows ->
                            val names = buildSet {
                                while (rows.next()) add(rows.getString("indexname"))
                            }
                            assertTrue("ix_prices_purchase_vat_id" in names)
                            assertTrue("ix_prices_sales_vat_id" in names)
                        }
                    }
            }
        }
    }

    private fun assertColumnMetadata(connection: Connection) {
        val expectedTypes =
            mapOf(
                "id" to Types.BIGINT,
                "purchase_vat_id" to Types.BIGINT,
                "purchase_calculation_mode" to Types.VARCHAR,
                "purchase_active_row" to Types.VARCHAR,
                "purchase_price_input_cents" to Types.INTEGER,
                "purchase_cost_input_cents" to Types.INTEGER,
                "purchase_cost_percent" to Types.NUMERIC,
                "sales_vat_id" to Types.BIGINT,
                "sales_calculation_mode" to Types.VARCHAR,
                "sales_active_row" to Types.VARCHAR,
                "sales_margin_input_cents" to Types.INTEGER,
                "sales_margin_percent" to Types.NUMERIC,
                "sales_total_input_cents" to Types.INTEGER,
            )
        val actual = buildMap {
            connection.metaData.getColumns(null, "voenix", "prices", null).use { rows ->
                while (rows.next()) {
                    put(
                        rows.getString("COLUMN_NAME"),
                        rows.getInt("DATA_TYPE") to rows.getInt("NULLABLE"),
                    )
                }
            }
        }

        assertEquals(expectedTypes, actual.mapValues { (_, metadata) -> metadata.first })
        assertTrue(
            actual.values.all { (_, nullable) -> nullable == DatabaseMetaData.columnNoNulls }
        )
    }

    private fun assertPercentagePrecisionAndScale(connection: Connection) {
        val percentageColumns =
            connection.metaData.getColumns(null, "voenix", "prices", "%_percent").use { rows ->
                buildMap {
                    while (rows.next()) {
                        put(
                            rows.getString("COLUMN_NAME"),
                            rows.getInt("COLUMN_SIZE") to rows.getInt("DECIMAL_DIGITS"),
                        )
                    }
                }
            }

        assertEquals(
            mapOf(
                "purchase_cost_percent" to (6 to 2),
                "sales_margin_percent" to (6 to 2),
            ),
            percentageColumns,
        )
    }

    private fun assertForeignKeyMetadata(connection: Connection) {
        val foreignKeys = buildMap {
            connection.metaData.getImportedKeys(null, "voenix", "prices").use { rows ->
                while (rows.next()) {
                    put(
                        rows.getString("FKCOLUMN_NAME"),
                        Triple(
                            rows.getString("PKTABLE_NAME"),
                            rows.getString("PKCOLUMN_NAME"),
                            rows.getInt("DELETE_RULE"),
                        ),
                    )
                }
            }
        }

        assertEquals(setOf("purchase_vat_id", "sales_vat_id"), foreignKeys.keys)
        foreignKeys.values.forEach { (table, column, deleteRule) ->
            assertEquals("value_added_taxes", table)
            assertEquals("id", column)
            assertEquals(DatabaseMetaData.importedKeyRestrict, deleteRule)
        }
    }

    private fun assertConstraintMetadata(connection: Connection) {
        val expected =
            mapOf(
                "pk_prices" to "p",
                "fk_prices_purchase_vat" to "f",
                "fk_prices_sales_vat" to "f",
                "ck_prices_purchase_calculation_mode" to "c",
                "ck_prices_purchase_active_row" to "c",
                "ck_prices_sales_calculation_mode" to "c",
                "ck_prices_sales_active_row" to "c",
                "ck_prices_purchase_price_input_non_negative" to "c",
                "ck_prices_purchase_cost_input_non_negative" to "c",
                "ck_prices_purchase_cost_percent_non_negative" to "c",
                "ck_prices_sales_total_input_non_negative" to "c",
            )
        val actual =
            connection
                .prepareStatement(
                    """
                    SELECT conname, contype
                    FROM pg_constraint
                    WHERE conrelid = 'voenix.prices'::regclass
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        buildMap {
                            while (rows.next()) {
                                put(rows.getString("conname"), rows.getString("contype"))
                            }
                        }
                    }
                }

        assertEquals(expected, actual)
    }

    private fun insertSql(id: Int, overriddenColumn: String, overriddenValue: String): String {
        val values =
            mutableMapOf(
                "purchase_vat_id" to "1",
                "purchase_calculation_mode" to "'NET'",
                "purchase_active_row" to "'COST'",
                "purchase_price_input_cents" to "0",
                "purchase_cost_input_cents" to "0",
                "purchase_cost_percent" to "0",
                "sales_vat_id" to "1",
                "sales_calculation_mode" to "'GROSS'",
                "sales_active_row" to "'TOTAL'",
                "sales_margin_input_cents" to "0",
                "sales_margin_percent" to "0",
                "sales_total_input_cents" to "0",
            )
        check(values.replace(overriddenColumn, overriddenValue) != null) {
            "Unknown price column: $overriddenColumn"
        }

        return """
        INSERT INTO voenix.prices (
            id,
            purchase_vat_id,
            purchase_calculation_mode,
            purchase_active_row,
            purchase_price_input_cents,
            purchase_cost_input_cents,
            purchase_cost_percent,
            sales_vat_id,
            sales_calculation_mode,
            sales_active_row,
            sales_margin_input_cents,
            sales_margin_percent,
            sales_total_input_cents
        ) VALUES (
            $id,
            ${values.getValue("purchase_vat_id")},
            ${values.getValue("purchase_calculation_mode")},
            ${values.getValue("purchase_active_row")},
            ${values.getValue("purchase_price_input_cents")},
            ${values.getValue("purchase_cost_input_cents")},
            ${values.getValue("purchase_cost_percent")},
            ${values.getValue("sales_vat_id")},
            ${values.getValue("sales_calculation_mode")},
            ${values.getValue("sales_active_row")},
            ${values.getValue("sales_margin_input_cents")},
            ${values.getValue("sales_margin_percent")},
            ${values.getValue("sales_total_input_cents")}
        )
        """
            .trimIndent()
    }
}
