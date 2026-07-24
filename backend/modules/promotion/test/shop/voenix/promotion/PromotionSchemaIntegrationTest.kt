package shop.voenix.promotion

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import shop.voenix.testing.PostgresIntegrationTest

internal class PromotionSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates promotion constraints foreign key and indexes on an empty database`() {
        migratedDataSource("promotion-schema-integration-test").use { dataSource ->
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(insertPromotionSql(id = 1, code = "Winter10"))
                    statement.execute(
                        """
                        INSERT INTO voenix.promotion_redemptions
                            (promotion_id, user_id, redeemed_at)
                        VALUES (1, NULL, '2026-02-01T10:00:00Z')
                        """
                            .trimIndent()
                    )
                }

                assertConstraintMetadata(connection)
                assertRedemptionForeignKeyRestrictsDelete(connection)
                assertCaseInsensitiveDuplicateCodeIsRejected(connection)
                assertCheckConstraintsRejectInvalidRows(connection)
                assertIndexes(connection)
            }
        }
    }

    private fun assertConstraintMetadata(connection: Connection) {
        val expected =
            mapOf(
                "pk_promotions" to "p",
                "ck_promotions_discount_type" to "c",
                "ck_promotions_discount_value_positive" to "c",
                "ck_promotions_usage_limit_total_positive" to "c",
                "ck_promotions_usage_limit_per_user_positive" to "c",
                "ux_promotions_coupon_code_normalized" to "u",
            )
        assertEquals(expected, constraintsOf(connection, "promotions"))
        assertEquals(
            mapOf(
                "pk_promotion_redemptions" to "p",
                "fk_promotion_redemptions_promotion" to "f",
            ),
            constraintsOf(connection, "promotion_redemptions"),
        )
    }

    private fun constraintsOf(connection: Connection, table: String): Map<String, String> =
        connection
            .prepareStatement(
                """
                SELECT conname, contype
                FROM pg_constraint
                WHERE conrelid = 'voenix.$table'::regclass
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

    private fun assertRedemptionForeignKeyRestrictsDelete(connection: Connection) {
        val foreignKeys = buildMap {
            connection.metaData.getImportedKeys(null, "voenix", "promotion_redemptions").use { rows
                ->
                while (rows.next()) {
                    put(
                        rows.getString("FKCOLUMN_NAME"),
                        rows.getString("PKTABLE_NAME") to rows.getInt("DELETE_RULE"),
                    )
                }
            }
        }
        assertEquals(
            mapOf("promotion_id" to ("promotions" to DatabaseMetaData.importedKeyRestrict)),
            foreignKeys,
        )

        val deleteFailure =
            assertFailsWith<SQLException> {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM voenix.promotions WHERE id = 1")
                }
            }
        assertEquals("23503", deleteFailure.sqlState)
    }

    private fun assertCaseInsensitiveDuplicateCodeIsRejected(connection: Connection) {
        val duplicate =
            assertFailsWith<SQLException> {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(insertPromotionSql(id = 2, code = "wINTER10"))
                }
            }
        assertEquals("23505", duplicate.sqlState)
    }

    private fun assertCheckConstraintsRejectInvalidRows(connection: Connection) {
        val invalidOverrides =
            listOf(
                "discount_type" to "'INVALID'",
                "discount_value" to "0",
                "discount_value" to "-1",
                "usage_limit_total" to "0",
                "usage_limit_per_user" to "0",
            )
        invalidOverrides.forEachIndexed { index, (column, value) ->
            val exception =
                assertFailsWith<SQLException> {
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            insertPromotionSql(
                                id = index + 10,
                                code = "Code${index + 10}",
                                column to value,
                            )
                        )
                    }
                }
            assertEquals("23514", exception.sqlState)
        }
    }

    private fun assertIndexes(connection: Connection) {
        val names =
            connection
                .prepareStatement(
                    """
                    SELECT indexname
                    FROM pg_indexes
                    WHERE schemaname = 'voenix'
                      AND tablename IN ('promotions', 'promotion_redemptions')
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        buildSet { while (rows.next()) add(rows.getString("indexname")) }
                    }
                }
        assertTrue("ix_promotions_name" in names)
        assertTrue("ix_promotion_redemptions_promotion_id" in names)
        assertTrue("ix_promotion_redemptions_promotion_id_user_id" in names)
    }

    private fun insertPromotionSql(
        id: Int,
        code: String,
        override: Pair<String, String>? = null,
    ): String {
        val values =
            mutableMapOf(
                "name" to "'Promotion $id'",
                "discount_type" to "'PERCENTAGE'",
                "discount_value" to "10.00",
                "coupon_code" to "'$code'",
                "coupon_code_normalized" to "upper('$code')",
                "starts_at" to "NULL",
                "ends_at" to "NULL",
                "usage_limit_total" to "NULL",
                "usage_limit_per_user" to "NULL",
                "is_active" to "TRUE",
            )
        if (override != null) {
            check(values.replace(override.first, override.second) != null) {
                "Unknown promotion column: ${override.first}"
            }
        }

        return """
        INSERT INTO voenix.promotions (
            id, ${values.keys.joinToString(", ")}
        ) VALUES (
            $id, ${values.values.joinToString(", ")}
        )
        """
            .trimIndent()
    }
}
