package shop.voenix.article

import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether the Flyway migration builds a t-shirt slice that actually enforces the shirt invariants.
 *
 * It asks the same questions [ArticleMugSchemaIntegrationTest] asks of the mug slice — identities,
 * restricted references, one default variant, the cascade from the identity — plus the rules only a
 * shirt has: the print frame stays inside the mockup, a variant carries a positive SPOD id triple
 * that repeats nowhere inside one article, and, since `V27__article_tshirts_spod_sync.sql`, the
 * shirt carries the identity of the synced article it is a copy of. Every rule is proven by the
 * write it rejects and the SQL state PostgreSQL answers with, never by the name of the constraint
 * that produced it.
 */
internal class ArticleTshirtSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates a tshirt table that enforces frames, references, and variant identity`() {
        migratedDataSource("article-tshirt-schema-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            dataSource.connection.use { connection ->
                seedTshirts(connection)

                assertTheTypeIsRegistered(connection)
                assertIdentitiesAreRequired(connection)
                assertPrintFrameStaysInsideTheMockup(connection)
                assertPrintAspectRatioDefaultsToSquareAndIsBounded(connection)
                assertActiveArticlesAreComplete(connection)
                assertReferencesAreRestricted(connection)
                assertTheSyncedIdentityIsRequiredAndUnique(connection)
                assertVariantsBelongToTheirArticle(connection)
                assertSpodIdsArePositive(connection)
                assertVariantsAreUniquePerArticle(connection)
                assertOneDefaultVariantPerArticle(connection)
                assertDeletingTheIdentityRemovesTheTshirt(connection)
            }
        }
    }

    private fun seedTshirts(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO voenix.value_added_taxes (id, name, percent, is_default)
                VALUES (1, 'Shirt schema VAT', 19, FALSE);
                INSERT INTO voenix.prices (
                    id, purchase_vat_id, purchase_calculation_mode, purchase_active_row,
                    purchase_price_input_cents, purchase_cost_input_cents, purchase_cost_percent,
                    sales_vat_id, sales_calculation_mode, sales_active_row,
                    sales_margin_input_cents, sales_margin_percent, sales_total_input_cents
                ) VALUES
                    (1, 1, 'NET', 'COST', 500, 0, 0, 1, 'NET', 'MARGIN', 500, 0, 1000),
                    (2, 1, 'NET', 'COST', 500, 0, 0, 1, 'NET', 'MARGIN', 500, 0, 1000);
                INSERT INTO voenix.suppliers (id, name) VALUES (1, 'Shirt schema supplier');
                -- Only one *enabled* SPOD destination per supplier is allowed, and the second one
                -- is here to prove that the same article id may exist behind another destination.
                INSERT INTO voenix.production_destinations
                    (id, supplier_id, channel, label, enabled)
                VALUES (1, 1, 'SPOD', 'Shirt schema destination', TRUE),
                       (2, 1, 'SPOD', 'Second shirt schema destination', FALSE);
                INSERT INTO voenix.article_categories (id, name, position)
                VALUES (1, 'Shirts', 1), (2, 'Posters', 2);
                INSERT INTO voenix.article_subcategories (id, category_id, name, position)
                VALUES (1, 1, 'Unisex', 1);
                INSERT INTO voenix.article_identities (id, article_type)
                VALUES (1, 'TSHIRT'), (2, 'TSHIRT');
                INSERT INTO voenix.article_tshirts (
                    id, position, name, description_short, description_long, active,
                    category_id, subcategory_id, supplier_id, price_id,
                    print_frame_left_pct, print_frame_top_pct,
                    print_frame_width_pct, print_frame_height_pct,
                    spod_destination_id, spod_environment, spod_article_id, spod_synced_at
                ) VALUES
                    (1, 1, 'Classic shirt', 'Short', 'Long', TRUE, 1, 1, 1, 1,
                     30.00, 25.00, 40.00, 45.00,
                     1, 'PRODUCTION', 'a-1', TIMESTAMPTZ '2026-08-24 09:00:00+00');
                INSERT INTO voenix.article_tshirts (
                    id, position, name, description_short, description_long, active,
                    supplier_id, print_frame_left_pct, print_frame_top_pct,
                    print_frame_width_pct, print_frame_height_pct,
                    spod_destination_id, spod_environment, spod_article_id, spod_synced_at
                ) VALUES (2, 2, 'Draft shirt', 'Short', 'Long', FALSE, 1, 0, 0, 100, 100,
                          1, 'PRODUCTION', 'a-2', TIMESTAMPTZ '2026-08-24 09:00:00+00');
                INSERT INTO voenix.article_variant_identities (id, article_id, article_type)
                VALUES (1, 1, 'TSHIRT');
                INSERT INTO voenix.article_tshirt_variants (
                    id, article_id, color_name, color_hex, size_label,
                    spod_product_type_id, spod_appearance_id, spod_size_id,
                    spod_variant_id, is_default, active
                ) VALUES (1, 1, 'Black', '#000000', 'M', 300, 4, 12, 'v-1', TRUE, TRUE);
                """
                    .trimIndent()
            )
        }
    }

    /** The type row is what an identity references and what a position writer locks. */
    private fun assertTheTypeIsRegistered(connection: Connection) {
        assertEquals(1, countOf(connection, "voenix.article_types", "article_type = 'TSHIRT'"))
        assertSqlState(
            "23503",
            connection,
            "DELETE FROM voenix.article_types WHERE article_type = 'TSHIRT'",
        )
    }

    private fun assertIdentitiesAreRequired(connection: Connection) {
        assertSqlState("23503", connection, tshirtSql(id = 91, position = 91))
        // The identity of a mug cannot be adopted by a shirt: the composite foreign key carries
        // the type, and the constant column says which type this table is.
        assertSqlState(
            "23514",
            connection,
            """
            INSERT INTO voenix.article_tshirts (
                id, article_type, position, name, description_short, description_long, active,
                supplier_id, print_frame_left_pct, print_frame_top_pct,
                print_frame_width_pct, print_frame_height_pct,
                spod_destination_id, spod_environment, spod_article_id, spod_synced_at
            ) VALUES (91, 'MUG', 91, 'Wrong type', 'Short', 'Long', FALSE, 1, 0, 0, 10, 10,
                      1, 'PRODUCTION', 'a-91', TIMESTAMPTZ '2026-08-24 09:00:00+00')
            """
                .trimIndent(),
        )

        // Every rejected row below carries this registered identity, so the rejection can only come
        // from the rule the assertion is about.
        connection.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO voenix.article_identities (id, article_type) VALUES (90, 'TSHIRT')"
            )
        }
    }

    /** The frame is a rectangle inside the mockup: no negative edge, and no edge that leaves it. */
    private fun assertPrintFrameStaysInsideTheMockup(connection: Connection) {
        listOf(
                "print_frame_left_pct" to "-0.01",
                "print_frame_top_pct" to "-0.01",
                "print_frame_width_pct" to "-0.01",
                "print_frame_height_pct" to "-0.01",
            )
            .forEach { override ->
                assertSqlState(
                    "23514",
                    connection,
                    tshirtSql(id = 90, position = 92, columns = mapOf(override)),
                )
            }

        assertSqlState(
            "23514",
            connection,
            tshirtSql(
                id = 90,
                position = 92,
                columns =
                    mapOf("print_frame_left_pct" to "60.01", "print_frame_width_pct" to "40.00"),
            ),
        )
        assertSqlState(
            "23514",
            connection,
            tshirtSql(
                id = 90,
                position = 92,
                columns =
                    mapOf("print_frame_top_pct" to "60.01", "print_frame_height_pct" to "40.00"),
            ),
        )

        // The frame that fills the whole mockup is the boundary case, and it is allowed.
        connection.createStatement().use { statement ->
            statement.execute(
                tshirtSql(
                    id = 90,
                    position = 92,
                    columns =
                        mapOf(
                            "print_frame_left_pct" to "0.00",
                            "print_frame_top_pct" to "0.00",
                            "print_frame_width_pct" to "100.00",
                            "print_frame_height_pct" to "100.00",
                        ),
                )
            )
        }
    }

    /**
     * A shirt is printed square unless it says otherwise — the one difference to the mug column,
     * whose default is the wide wrap-around print. Anything outside the pair the backend knows is
     * refused.
     */
    private fun assertPrintAspectRatioDefaultsToSquareAndIsBounded(connection: Connection) {
        assertEquals(3, countOf(connection, "voenix.article_tshirts", "print_aspect_ratio = '1:1'"))

        connection.createStatement().use { statement ->
            statement.execute(
                "UPDATE voenix.article_tshirts SET print_aspect_ratio = '16:9' WHERE id = 90"
            )
        }
        assertEquals(
            1,
            countOf(connection, "voenix.article_tshirts", "print_aspect_ratio = '16:9'"),
        )

        assertSqlState(
            "23514",
            connection,
            "UPDATE voenix.article_tshirts SET print_aspect_ratio = '4:3' WHERE id = 90",
        )
        assertSqlState(
            "23514",
            connection,
            "UPDATE voenix.article_tshirts SET print_aspect_ratio = 'SQUARE' WHERE id = 90",
        )

        connection.createStatement().use { statement ->
            statement.execute("DELETE FROM voenix.article_tshirts WHERE id = 90")
        }
    }

    private fun assertActiveArticlesAreComplete(connection: Connection) {
        val active = mapOf("active" to "TRUE")
        // A category, but no price.
        assertSqlState(
            "23514",
            connection,
            tshirtSql(id = 90, position = 93, columns = active + ("category_id" to "1")),
        )
        // A price, but no category.
        assertSqlState(
            "23514",
            connection,
            tshirtSql(id = 90, position = 93, columns = active + ("price_id" to "2")),
        )
        // A subcategory without its category.
        assertSqlState(
            "23514",
            connection,
            tshirtSql(id = 90, position = 93, columns = mapOf("subcategory_id" to "1")),
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
            "UPDATE voenix.article_tshirts SET category_id = 2, subcategory_id = 1 WHERE id = 2",
        )
        // A price belongs to exactly one article.
        assertSqlState(
            "23505",
            connection,
            "UPDATE voenix.article_tshirts SET price_id = 1 WHERE id = 2",
        )
    }

    /**
     * The identity of the synced article, added by `V27__article_tshirts_spod_sync.sql`: which
     * installation of which destination the shirt came from, and which article it is over there.
     */
    private fun assertTheSyncedIdentityIsRequiredAndUnique(connection: Connection) {
        // The two installations a destination is switched between, and nothing else.
        assertSqlState(
            "23514",
            connection,
            tshirtSql(id = 90, position = 94, columns = mapOf("spod_environment" to "'SANDBOX'")),
        )
        // A shirt is produced by the supplier its destination belongs to, so it always has one.
        assertSqlState(
            "23502",
            connection,
            tshirtSql(id = 90, position = 94, columns = mapOf("supplier_id" to "NULL")),
        )
        // The destination a shirt was synced from can be disabled, but not deleted under it.
        assertSqlState(
            "23503",
            connection,
            "DELETE FROM voenix.production_destinations WHERE id = 1",
        )

        // One synced article is one shop article...
        assertSqlState(
            "23505",
            connection,
            tshirtSql(id = 90, position = 94, columns = mapOf("spod_article_id" to "'a-1'")),
        )
        // ...but the very same article id names another article behind another destination, and
        // another one again in the other installation of the same destination. That is why the
        // environment is part of the key: production article *n* is not staging article *n*.
        listOf(
                mapOf("spod_article_id" to "'a-1'", "spod_destination_id" to "2"),
                mapOf("spod_article_id" to "'a-1'", "spod_environment" to "'STAGING'"),
            )
            .forEach { columns ->
                connection.createStatement().use { statement ->
                    statement.execute(tshirtSql(id = 90, position = 94, columns = columns))
                    statement.execute("DELETE FROM voenix.article_tshirts WHERE id = 90")
                }
            }
    }

    private fun assertVariantsBelongToTheirArticle(connection: Connection) {
        // A colour and size nothing else uses, so only the identity rule can reject these rows.
        assertSqlState(
            "23503",
            connection,
            variantSql(
                id = 90,
                articleId = 1,
                sizeLabel = "S",
                isDefault = false,
                columns = mapOf("spod_size_id" to "11"),
            ),
        )

        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO voenix.article_variant_identities (id, article_id, article_type)
                VALUES (90, 2, 'TSHIRT')
                """
                    .trimIndent()
            )
        }
        // The variant identity says the variant belongs to shirt 2, so shirt 1 cannot claim it.
        assertSqlState(
            "23503",
            connection,
            variantSql(
                id = 90,
                articleId = 1,
                sizeLabel = "S",
                isDefault = false,
                columns = mapOf("spod_size_id" to "11"),
            ),
        )
        connection.createStatement().use { statement ->
            statement.execute("DELETE FROM voenix.article_variant_identities WHERE id = 90")
        }
    }

    private fun assertSpodIdsArePositive(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO voenix.article_variant_identities (id, article_id, article_type)
                VALUES (2, 1, 'TSHIRT'), (3, 1, 'TSHIRT')
                """
                    .trimIndent()
            )
        }

        listOf(
                "spod_product_type_id" to "0",
                "spod_appearance_id" to "0",
                "spod_size_id" to "-1",
            )
            .forEach { override ->
                assertSqlState(
                    "23514",
                    connection,
                    variantSql(
                        id = 2,
                        articleId = 1,
                        sizeLabel = "L",
                        columns = mapOf(override),
                    ),
                )
            }
    }

    private fun assertVariantsAreUniquePerArticle(connection: Connection) {
        // The same printable product twice under one article, sold under two names.
        assertSqlState(
            "23505",
            connection,
            variantSql(
                id = 2,
                articleId = 1,
                colorName = "Deep black",
                sizeLabel = "L",
                isDefault = false,
            ),
        )
        // The colour and size pair, on the other hand, may now repeat: V27 dropped that rule so
        // that a colour the partner renamed can take the place of its predecessor within one sync
        // run. The printable product is what tells two variants apart.
        connection.createStatement().use { statement ->
            statement.execute(
                variantSql(
                    id = 2,
                    articleId = 1,
                    isDefault = false,
                    columns = mapOf("spod_size_id" to "13"),
                )
            )
        }
    }

    private fun assertOneDefaultVariantPerArticle(connection: Connection) {
        val distinctProduct = mapOf("spod_size_id" to "14")
        assertSqlState(
            "23505",
            connection,
            variantSql(
                id = 3,
                articleId = 1,
                sizeLabel = "XL",
                isDefault = true,
                columns = distinctProduct,
            ),
        )
        connection.createStatement().use { statement ->
            statement.execute(
                variantSql(
                    id = 3,
                    articleId = 1,
                    sizeLabel = "XL",
                    isDefault = false,
                    columns = distinctProduct,
                )
            )
        }
    }

    private fun assertDeletingTheIdentityRemovesTheTshirt(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("DELETE FROM voenix.article_identities WHERE id = 1")
        }

        assertEquals(0, countOf(connection, "voenix.article_tshirts", "id = 1"))
        assertEquals(0, countOf(connection, "voenix.article_tshirt_variants", "article_id = 1"))
        assertEquals(0, countOf(connection, "voenix.article_variant_identities", "article_id = 1"))
        assertEquals(1, countOf(connection, "voenix.article_tshirts", "id = 2"))
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

    private fun tshirtSql(
        id: Int,
        position: Int,
        columns: Map<String, String> = emptyMap(),
    ): String {
        val values =
            mapOf(
                "id" to "$id",
                "position" to "$position",
                "name" to "'Shirt $position'",
                "description_short" to "'Short'",
                "description_long" to "'Long'",
                "active" to "FALSE",
                "supplier_id" to "1",
                "print_frame_left_pct" to "10.00",
                "print_frame_top_pct" to "10.00",
                "print_frame_width_pct" to "50.00",
                "print_frame_height_pct" to "50.00",
                "spod_destination_id" to "1",
                "spod_environment" to "'PRODUCTION'",
                "spod_article_id" to "'a-$position'",
                "spod_synced_at" to "TIMESTAMPTZ '2026-08-24 09:00:00+00'",
            ) + columns

        return """
        INSERT INTO voenix.article_tshirts (${values.keys.joinToString(", ")})
        VALUES (${values.values.joinToString(", ")})
        """
            .trimIndent()
    }

    private fun variantSql(
        id: Int,
        articleId: Int,
        colorName: String = "Black",
        sizeLabel: String = "M",
        isDefault: Boolean = true,
        columns: Map<String, String> = emptyMap(),
    ): String {
        val values =
            mapOf(
                "id" to "$id",
                "article_id" to "$articleId",
                "color_name" to "'$colorName'",
                "color_hex" to "'#000000'",
                "size_label" to "'$sizeLabel'",
                "spod_product_type_id" to "300",
                "spod_appearance_id" to "4",
                "spod_size_id" to "12",
                "spod_variant_id" to "'v-$id'",
                "is_default" to "$isDefault",
                "active" to "TRUE",
            ) + columns

        return """
        INSERT INTO voenix.article_tshirt_variants (${values.keys.joinToString(", ")})
        VALUES (${values.values.joinToString(", ")})
        """
            .trimIndent()
    }

    private fun assertSqlState(
        expected: String,
        connection: Connection,
        sql: String,
    ) = ArticleTestSchema.assertSqlState(expected, connection, sql)
}
