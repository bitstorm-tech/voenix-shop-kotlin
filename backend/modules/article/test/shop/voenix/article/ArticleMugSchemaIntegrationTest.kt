package shop.voenix.article

import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether the Flyway migration builds a mug table that actually enforces the article invariants.
 *
 * The identity registries, the completeness rules of a mug, and the restricted references to
 * supplier, price, category, and subcategory are all asserted by the write they reject and the SQL
 * state PostgreSQL answers with.
 */
internal class ArticleMugSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates a mug table that enforces identity completeness and references`() {
        migratedDataSource("article-mug-schema-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            dataSource.connection.use { connection ->
                seedMugs(connection)

                assertIdentitiesAreRequired(connection)
                assertVariantsBelongToTheirArticle(connection)
                assertDetailsAreAllOrNone(connection)
                assertMeasurementsArePositive(connection)
                assertActiveArticlesAreComplete(connection)
                assertReferencesAreRestricted(connection)
                assertOneDefaultVariantPerArticle(connection)
                assertDeletingTheIdentityRemovesTheMug(connection)
            }
        }
    }

    private fun seedMugs(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO voenix.value_added_taxes (id, name, percent, is_default)
                VALUES (1, 'Article schema VAT', 19, FALSE);
                INSERT INTO voenix.prices (
                    id, purchase_vat_id, purchase_calculation_mode, purchase_active_row,
                    purchase_price_input_cents, purchase_cost_input_cents, purchase_cost_percent,
                    sales_vat_id, sales_calculation_mode, sales_active_row,
                    sales_margin_input_cents, sales_margin_percent, sales_total_input_cents
                ) VALUES
                    (1, 1, 'NET', 'COST', 500, 0, 0, 1, 'NET', 'MARGIN', 500, 0, 1000),
                    (2, 1, 'NET', 'COST', 500, 0, 0, 1, 'NET', 'MARGIN', 500, 0, 1000);
                INSERT INTO voenix.suppliers (id, name) VALUES (1, 'Article schema supplier');
                INSERT INTO voenix.article_categories (id, name, position)
                VALUES (1, 'Mugs', 1), (2, 'Posters', 2);
                INSERT INTO voenix.article_subcategories (id, category_id, name, position)
                VALUES (1, 1, 'Classic', 1);
                INSERT INTO voenix.article_identities (id, article_type)
                VALUES (1, 'MUG'), (2, 'MUG');
                INSERT INTO voenix.article_mugs (
                    id, position, name, description_short, description_long, active,
                    category_id, subcategory_id, supplier_id, price_id,
                    height_mm, diameter_mm, print_template_width_mm, print_template_height_mm,
                    dishwasher_safe
                ) VALUES
                    (1, 1, 'Classic mug', 'Short', 'Long', TRUE, 1, 1, 1, 1, 95, 82, 200, 90, TRUE);
                INSERT INTO voenix.article_mugs (
                    id, position, name, description_short, description_long, active
                ) VALUES (2, 2, 'Draft mug', 'Short', 'Long', FALSE);
                INSERT INTO voenix.article_variant_identities (id, article_id, article_type)
                VALUES (1, 1, 'MUG');
                INSERT INTO voenix.article_mug_variants (
                    id, article_id, inside_color_code, outside_color_code, name, is_default, active
                ) VALUES (1, 1, '#ffffff', '#ffffff', 'White', TRUE, TRUE);
                """
                    .trimIndent()
            )
        }
    }

    private fun assertIdentitiesAreRequired(connection: Connection) {
        assertSqlState("23503", connection, mugSql(id = 91, position = 91))
        assertSqlState(
            "23514",
            connection,
            """
            INSERT INTO voenix.article_mugs (
                id, article_type, position, name, description_short, description_long, active
            ) VALUES (91, 'POSTER', 91, 'Wrong type', 'Short', 'Long', FALSE)
            """
                .trimIndent(),
        )

        // Every rejected row below carries this registered identity, so the rejection can only come
        // from the rule the assertion is about.
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO voenix.article_identities (id, article_type) VALUES (90, 'MUG')"
            )
        }
    }

    private fun assertVariantsBelongToTheirArticle(connection: Connection) {
        assertSqlState(
            "23503",
            connection,
            """
            INSERT INTO voenix.article_variant_identities (id, article_id, article_type)
            VALUES (90, 404, 'MUG')
            """
                .trimIndent(),
        )

        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO voenix.article_variant_identities (id, article_id, article_type)
                VALUES (90, 2, 'MUG')
                """
                    .trimIndent()
            )
        }
        // The variant identity says the variant belongs to mug 2, so mug 1 cannot claim it.
        assertSqlState(
            "23503",
            connection,
            variantSql(id = 90, articleId = 1, isDefault = false),
        )
        connection.createStatement().use { statement ->
            statement.execute("DELETE FROM voenix.article_variant_identities WHERE id = 90")
        }
    }

    private fun assertDetailsAreAllOrNone(connection: Connection) {
        assertSqlState(
            "23514",
            connection,
            mugSql(id = 90, position = 92, columns = mapOf("height_mm" to "95")),
        )
        assertSqlState(
            "23514",
            connection,
            mugSql(id = 90, position = 92, columns = mapOf("filling_quantity" to "'300 ml'")),
        )
        assertSqlState(
            "23514",
            connection,
            mugSql(
                id = 90,
                position = 92,
                columns = completeDetails() + ("dishwasher_safe" to "NULL"),
            ),
        )
    }

    private fun assertMeasurementsArePositive(connection: Connection) {
        listOf(
                "height_mm" to "0",
                "diameter_mm" to "0",
                "print_template_width_mm" to "0",
                "print_template_height_mm" to "0",
                "document_format_width_mm" to "0",
                "document_format_height_mm" to "0",
                "document_format_margin_bottom_mm" to "0",
            )
            .forEach { override ->
                assertSqlState(
                    "23514",
                    connection,
                    mugSql(id = 90, position = 93, columns = completeDetails() + override),
                )
            }
    }

    private fun assertActiveArticlesAreComplete(connection: Connection) {
        val active = mapOf("active" to "TRUE")
        // Details and category, but no price.
        assertSqlState(
            "23514",
            connection,
            mugSql(
                id = 90,
                position = 94,
                columns = completeDetails() + active + ("category_id" to "1"),
            ),
        )
        // Price and details, but no category.
        assertSqlState(
            "23514",
            connection,
            mugSql(
                id = 90,
                position = 94,
                columns = completeDetails() + active + ("price_id" to "2"),
            ),
        )
        // Price and category, but no details.
        assertSqlState(
            "23514",
            connection,
            mugSql(
                id = 90,
                position = 94,
                columns = active + ("price_id" to "2") + ("category_id" to "1"),
            ),
        )
        // A subcategory without a category.
        assertSqlState(
            "23514",
            connection,
            mugSql(id = 90, position = 94, columns = mapOf("subcategory_id" to "1")),
        )
    }

    private fun assertReferencesAreRestricted(connection: Connection) {
        assertSqlState("23503", connection, "DELETE FROM voenix.suppliers WHERE id = 1")
        assertSqlState("23503", connection, "DELETE FROM voenix.prices WHERE id = 1")
        assertSqlState("23503", connection, "DELETE FROM voenix.article_categories WHERE id = 1")
        assertSqlState("23503", connection, "DELETE FROM voenix.article_subcategories WHERE id = 1")

        // The subcategory belongs to category 1, so it cannot be used under category 2.
        assertSqlState(
            "23503",
            connection,
            "UPDATE voenix.article_mugs SET category_id = 2, subcategory_id = 1 WHERE id = 2",
        )
        // A price belongs to exactly one article.
        assertSqlState(
            "23505",
            connection,
            "UPDATE voenix.article_mugs SET price_id = 1 WHERE id = 2",
        )
    }

    private fun assertOneDefaultVariantPerArticle(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO voenix.article_variant_identities (id, article_id, article_type)
                VALUES (2, 1, 'MUG')
                """
                    .trimIndent()
            )
        }
        assertSqlState("23505", connection, variantSql(id = 2, articleId = 1, isDefault = true))
        connection.createStatement().use { statement ->
            statement.execute(variantSql(id = 2, articleId = 1, isDefault = false))
        }
    }

    private fun assertDeletingTheIdentityRemovesTheMug(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("DELETE FROM voenix.article_identities WHERE id = 1")
        }

        assertEquals(0, countOf(connection, "voenix.article_mugs", "id = 1"))
        assertEquals(0, countOf(connection, "voenix.article_mug_variants", "article_id = 1"))
        assertEquals(0, countOf(connection, "voenix.article_variant_identities", "article_id = 1"))
        assertEquals(1, countOf(connection, "voenix.article_mugs", "id = 2"))
    }

    private fun countOf(
        connection: Connection,
        table: String,
        where: String,
    ): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM $table WHERE $where").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /** The four required measurements plus the flag, as a complete detail block. */
    private fun completeDetails(): Map<String, String> =
        mapOf(
            "height_mm" to "95",
            "diameter_mm" to "82",
            "print_template_width_mm" to "200",
            "print_template_height_mm" to "90",
            "dishwasher_safe" to "TRUE",
        )

    private fun mugSql(
        id: Int,
        position: Int,
        columns: Map<String, String> = emptyMap(),
    ): String {
        val values =
            mapOf(
                "id" to "$id",
                "position" to "$position",
                "name" to "'Mug $position'",
                "description_short" to "'Short'",
                "description_long" to "'Long'",
                "active" to "FALSE",
            ) + columns

        return """
        INSERT INTO voenix.article_mugs (${values.keys.joinToString(", ")})
        VALUES (${values.values.joinToString(", ")})
        """
            .trimIndent()
    }

    private fun variantSql(
        id: Int,
        articleId: Int,
        isDefault: Boolean = true,
    ): String =
        """
        INSERT INTO voenix.article_mug_variants (
            id, article_id, inside_color_code, outside_color_code, name, is_default, active
        ) VALUES ($id, $articleId, '#000000', '#000000', 'Variant $id', $isDefault, TRUE)
        """
            .trimIndent()

    private fun assertSqlState(
        expected: String,
        connection: Connection,
        sql: String,
    ) = ArticleTestSchema.assertSqlState(expected, connection, sql)
}
