package shop.voenix.article

import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import shop.voenix.testing.PostgresIntegrationTest

/**
 * Whether the Flyway migration builds a SPOD article slice that actually enforces its invariants.
 *
 * It asks the same questions [ArticleMugSchemaIntegrationTest] asks of the mug slice — identities,
 * restricted references, one default variant, the cascade from the identity — plus the rules only a
 * SPOD article has: the identity of the synced article it is a copy of, the measured print frame
 * that is set as a whole or not at all and belongs to a hotspot, an active article that cannot be
 * without that frame, and the three tables below the article — the colours, the variants that name
 * one, and the pictures of a colour. Every rule is proven by the write it rejects and the SQL state
 * PostgreSQL answers with, never by the name of the constraint that produced it.
 */
internal class ArticleSpodSchemaIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `flyway creates SPOD tables that enforce frames, references, colours, and variants`() {
        migratedDataSource("article-spod-schema-test").use { dataSource ->
            ArticleTestSchema.reset(dataSource)
            dataSource.connection.use { connection ->
                seedArticles(connection)

                assertTheTypeIsRegistered(connection)
                assertIdentitiesAreRequired(connection)
                assertThePrintFrameIsMeasuredAsAWhole(connection)
                assertPrintFrameStaysInsideTheMockup(connection)
                assertActiveArticlesAreComplete(connection)
                assertReferencesAreRestricted(connection)
                assertTheSyncedIdentityIsRequiredAndUnique(connection)
                assertAppearancesAreUniquePerArticle(connection)
                assertVariantsBelongToTheirArticle(connection)
                assertSpodIdsArePositive(connection)
                assertVariantsAreUniquePerArticle(connection)
                assertOneDefaultVariantPerArticle(connection)
                assertImagesHaveARoleAPlaceAndOneMockup(connection)
                assertDeletingTheIdentityRemovesTheArticle(connection)
            }
        }
    }

    private fun seedArticles(connection: Connection) {
        execute(
            connection,
            """
            INSERT INTO voenix.value_added_taxes (id, name, percent, is_default)
            VALUES (1, 'SPOD schema VAT', 19, FALSE);
            INSERT INTO voenix.prices (
                id, purchase_vat_id, purchase_calculation_mode, purchase_active_row,
                purchase_price_input_cents, purchase_cost_input_cents, purchase_cost_percent,
                sales_vat_id, sales_calculation_mode, sales_active_row,
                sales_margin_input_cents, sales_margin_percent, sales_total_input_cents
            ) VALUES
                (1, 1, 'NET', 'COST', 500, 0, 0, 1, 'NET', 'MARGIN', 500, 0, 1000),
                (2, 1, 'NET', 'COST', 500, 0, 0, 1, 'NET', 'MARGIN', 500, 0, 1000);
            INSERT INTO voenix.suppliers (id, name) VALUES (1, 'SPOD schema supplier');
            -- Only one *enabled* SPOD destination per supplier is allowed, and the second one
            -- is here to prove that the same article id may exist behind another destination.
            INSERT INTO voenix.production_destinations
                (id, supplier_id, channel, label, enabled)
            VALUES (1, 1, 'SPOD', 'SPOD schema destination', TRUE),
                   (2, 1, 'SPOD', 'Second SPOD schema destination', FALSE);
            INSERT INTO voenix.article_categories (id, name, position)
            VALUES (1, 'Shirts', 1), (2, 'Posters', 2);
            INSERT INTO voenix.article_subcategories (id, category_id, name, position)
            VALUES (1, 1, 'Unisex', 1);
            INSERT INTO voenix.article_identities (id, article_type)
            VALUES (1, 'SPOD'), (2, 'SPOD');
            INSERT INTO voenix.article_spod_articles (
                id, position, name, description_short, description_long, active,
                category_id, subcategory_id, supplier_id, price_id,
                print_frame_left_pct, print_frame_top_pct,
                print_frame_width_pct, print_frame_height_pct, spod_hotspot,
                spod_destination_id, spod_environment, spod_article_id, spod_synced_at,
                spod_product_type_id, spod_product_type_name
            ) VALUES
                (1, 1, 'Classic shirt', 'Short', 'Long', TRUE, 1, 1, 1, 1,
                 35.60, 18.90, 28.40, 28.40, 'MEDIUM_FRONT',
                 1, 'PRODUCTION', 'a-1', TIMESTAMPTZ '2026-08-24 09:00:00+00',
                 300, 'T-Shirt');
            -- A draft no run has measured yet: no frame, no hotspot, and no product type name.
            INSERT INTO voenix.article_spod_articles (
                id, position, name, description_short, description_long, active, supplier_id,
                spod_destination_id, spod_environment, spod_article_id, spod_synced_at,
                spod_product_type_id, spod_product_type_name
            ) VALUES (2, 2, 'Draft bag', 'Short', 'Long', FALSE, 1,
                      1, 'PRODUCTION', 'a-2', TIMESTAMPTZ '2026-08-24 09:00:00+00', 2973, '');
            INSERT INTO voenix.article_spod_appearances
                (id, article_id, spod_appearance_id, color_name, color_hex, position)
            VALUES (1, 1, 4, 'Black', '#000000', 1), (2, 2, 4, 'Natural', '#f0e8d8', 1);
            INSERT INTO voenix.article_variant_identities (id, article_id, article_type)
            VALUES (1, 1, 'SPOD');
            INSERT INTO voenix.article_spod_variants (
                id, article_id, appearance_id, size_label, spod_product_type_id, spod_size_id,
                spod_variant_id, is_default, active
            ) VALUES (1, 1, 1, 'M', 300, 12, 'v-1', TRUE, TRUE);
            """,
        )
    }

    /** The type row is what an identity references and what a position writer locks. */
    private fun assertTheTypeIsRegistered(connection: Connection) {
        assertEquals(1, countOf(connection, "voenix.article_types", "article_type = 'SPOD'"))
        assertEquals(0, countOf(connection, "voenix.article_types", "article_type = 'TSHIRT'"))
        assertSqlState(
            "23001",
            connection,
            "DELETE FROM voenix.article_types WHERE article_type = 'SPOD'",
        )
    }

    private fun assertIdentitiesAreRequired(connection: Connection) {
        assertSqlState("23503", connection, articleSql(id = 91, position = 91))
        // The identity of a mug cannot be adopted by a SPOD article: the composite foreign key
        // carries the type, and the constant column says which type this table is.
        assertSqlState(
            "23514",
            connection,
            articleSql(id = 91, position = 91, columns = mapOf("article_type" to "'MUG'")),
        )

        // Every rejected row below carries this registered identity, so the rejection can only come
        // from the rule the assertion is about.
        execute(
            connection,
            "INSERT INTO voenix.article_identities (id, article_type) VALUES (90, 'SPOD')",
        )
    }

    /**
     * A frame is measured as a whole: the four percentages are set together or not at all, and the
     * hotspot they were measured for exists exactly when they do.
     */
    private fun assertThePrintFrameIsMeasuredAsAWhole(connection: Connection) {
        // Half a frame.
        assertSqlState(
            "23514",
            connection,
            articleSql(
                id = 90,
                position = 92,
                columns =
                    mapOf("print_frame_left_pct" to "10.00", "spod_hotspot" to "'FULL_FRONT'"),
            ),
        )
        // A hotspot without a frame.
        assertSqlState(
            "23514",
            connection,
            articleSql(id = 90, position = 92, columns = mapOf("spod_hotspot" to "'FULL_FRONT'")),
        )
        // A frame without the hotspot it belongs to.
        assertSqlState(
            "23514",
            connection,
            articleSql(id = 90, position = 92, columns = MEASURED_FRAME - "spod_hotspot"),
        )
    }

    /** A measured frame is a rectangle inside the mockup: no negative edge, no edge outside. */
    private fun assertPrintFrameStaysInsideTheMockup(connection: Connection) {
        listOf(
                "print_frame_left_pct",
                "print_frame_top_pct",
                "print_frame_width_pct",
                "print_frame_height_pct",
            )
            .forEach { column ->
                assertSqlState(
                    "23514",
                    connection,
                    articleSql(
                        id = 90,
                        position = 92,
                        columns = MEASURED_FRAME + (column to "-0.01"),
                    ),
                )
            }
        assertSqlState(
            "23514",
            connection,
            articleSql(
                id = 90,
                position = 92,
                columns =
                    MEASURED_FRAME +
                        mapOf(
                            "print_frame_left_pct" to "60.01",
                            "print_frame_width_pct" to "40.00",
                        ),
            ),
        )
        assertSqlState(
            "23514",
            connection,
            articleSql(
                id = 90,
                position = 92,
                columns =
                    MEASURED_FRAME +
                        mapOf(
                            "print_frame_top_pct" to "60.01",
                            "print_frame_height_pct" to "40.00",
                        ),
            ),
        )

        // The frame that fills the whole mockup is the boundary case, and it is allowed.
        execute(
            connection,
            articleSql(
                id = 90,
                position = 92,
                columns =
                    mapOf(
                        "print_frame_left_pct" to "0.00",
                        "print_frame_top_pct" to "0.00",
                        "print_frame_width_pct" to "100.00",
                        "print_frame_height_pct" to "100.00",
                        "spod_hotspot" to "'FULL_FRONT'",
                    ),
            ),
        )
        execute(connection, "DELETE FROM voenix.article_spod_articles WHERE id = 90")
    }

    /** A visible article has a price, a category, and a measured frame with its hotspot. */
    private fun assertActiveArticlesAreComplete(connection: Connection) {
        val active = MEASURED_FRAME + ("active" to "TRUE")
        // A category and a frame, but no price.
        assertSqlState(
            "23514",
            connection,
            articleSql(id = 90, position = 93, columns = active + ("category_id" to "1")),
        )
        // A price and a frame, but no category.
        assertSqlState(
            "23514",
            connection,
            articleSql(id = 90, position = 93, columns = active + ("price_id" to "2")),
        )
        // A price and a category, but no measured frame.
        assertSqlState(
            "23514",
            connection,
            articleSql(
                id = 90,
                position = 93,
                columns = mapOf("active" to "TRUE", "category_id" to "1", "price_id" to "2"),
            ),
        )
        assertSqlState(
            "23514",
            connection,
            "UPDATE voenix.article_spod_articles SET active = TRUE, category_id = 1, " +
                "price_id = 2 WHERE id = 2",
        )
        // A subcategory without its category.
        assertSqlState(
            "23514",
            connection,
            articleSql(id = 90, position = 93, columns = mapOf("subcategory_id" to "1")),
        )
    }

    private fun assertReferencesAreRestricted(connection: Connection) {
        assertSqlState("23001", connection, "DELETE FROM voenix.suppliers WHERE id = 1")
        assertSqlState("23001", connection, "DELETE FROM voenix.prices WHERE id = 1")
        assertSqlState("23001", connection, "DELETE FROM voenix.article_categories WHERE id = 1")
        assertSqlState("23001", connection, "DELETE FROM voenix.article_subcategories WHERE id = 1")

        // The subcategory belongs to category 1, so it cannot be used under category 2.
        assertSqlState(
            "23503",
            connection,
            "UPDATE voenix.article_spod_articles SET category_id = 2, subcategory_id = 1 " +
                "WHERE id = 2",
        )
        // A price belongs to exactly one article.
        assertSqlState(
            "23505",
            connection,
            "UPDATE voenix.article_spod_articles SET price_id = 1 WHERE id = 2",
        )
    }

    /**
     * The identity of the synced article: which installation of which destination the article came
     * from, and which article it is over there.
     */
    private fun assertTheSyncedIdentityIsRequiredAndUnique(connection: Connection) {
        // The two installations a destination is switched between, and nothing else.
        assertSqlState(
            "23514",
            connection,
            articleSql(id = 90, position = 94, columns = mapOf("spod_environment" to "'SANDBOX'")),
        )
        // An article is produced by the supplier its destination belongs to, so it always has one.
        assertSqlState(
            "23502",
            connection,
            articleSql(id = 90, position = 94, columns = mapOf("supplier_id" to "NULL")),
        )
        // The product type is part of what the partner owns, and it is a real id.
        assertSqlState(
            "23514",
            connection,
            articleSql(id = 90, position = 94, columns = mapOf("spod_product_type_id" to "0")),
        )
        // The destination an article was synced from can be disabled, but not deleted under it.
        assertSqlState(
            "23001",
            connection,
            "DELETE FROM voenix.production_destinations WHERE id = 1",
        )

        // One synced article is one shop article...
        assertSqlState(
            "23505",
            connection,
            articleSql(id = 90, position = 94, columns = mapOf("spod_article_id" to "'a-1'")),
        )
        // ...but the very same article id names another article behind another destination, and
        // another one again in the other installation of the same destination.
        listOf(
                mapOf("spod_article_id" to "'a-1'", "spod_destination_id" to "2"),
                mapOf("spod_article_id" to "'a-1'", "spod_environment" to "'STAGING'"),
            )
            .forEach { columns ->
                execute(connection, articleSql(id = 90, position = 94, columns = columns))
                execute(connection, "DELETE FROM voenix.article_spod_articles WHERE id = 90")
            }
    }

    /** A colour is the partner's appearance, once per article, under a name used once. */
    private fun assertAppearancesAreUniquePerArticle(connection: Connection) {
        assertSqlState("23505", connection, appearanceSql(articleId = 1, spodAppearanceId = 4))
        assertSqlState(
            "23505",
            connection,
            appearanceSql(articleId = 1, spodAppearanceId = 5, colorName = "Black"),
        )
        assertSqlState("23514", connection, appearanceSql(articleId = 1, spodAppearanceId = 0))
        // The same partner colour and the same name are free in another article.
        execute(
            connection,
            appearanceSql(articleId = 2, spodAppearanceId = 5, colorName = "Black", id = 3),
        )
    }

    private fun assertVariantsBelongToTheirArticle(connection: Connection) {
        // A size nothing else uses, so only the identity rule can reject these rows.
        assertSqlState(
            "23503",
            connection,
            variantSql(id = 90, articleId = 1, sizeLabel = "S", sizeId = 11, isDefault = false),
        )

        execute(
            connection,
            """
            INSERT INTO voenix.article_variant_identities (id, article_id, article_type)
            VALUES (90, 2, 'SPOD')
            """,
        )
        // The variant identity says the variant belongs to article 2, so article 1 cannot claim it.
        assertSqlState(
            "23503",
            connection,
            variantSql(id = 90, articleId = 1, sizeLabel = "S", sizeId = 11, isDefault = false),
        )
        execute(connection, "DELETE FROM voenix.article_variant_identities WHERE id = 90")
    }

    private fun assertSpodIdsArePositive(connection: Connection) {
        execute(
            connection,
            """
            INSERT INTO voenix.article_variant_identities (id, article_id, article_type)
            VALUES (2, 1, 'SPOD'), (3, 1, 'SPOD')
            """,
        )

        assertSqlState(
            "23514",
            connection,
            variantSql(id = 2, articleId = 1, sizeLabel = "L", productTypeId = 0),
        )
        assertSqlState(
            "23514",
            connection,
            variantSql(id = 2, articleId = 1, sizeLabel = "L", sizeId = -1),
        )
    }

    private fun assertVariantsAreUniquePerArticle(connection: Connection) {
        // The same printable product twice under one article, sold under two names.
        assertSqlState(
            "23505",
            connection,
            variantSql(id = 2, articleId = 1, sizeLabel = "L", isDefault = false),
        )
        // The same size of the same colour twice.
        assertSqlState(
            "23505",
            connection,
            variantSql(id = 2, articleId = 1, sizeLabel = "M", sizeId = 13, isDefault = false),
        )
        execute(
            connection,
            variantSql(id = 2, articleId = 1, sizeLabel = "L", sizeId = 13, isDefault = false),
        )
    }

    private fun assertOneDefaultVariantPerArticle(connection: Connection) {
        assertSqlState(
            "23505",
            connection,
            variantSql(id = 3, articleId = 1, sizeLabel = "XL", sizeId = 14, isDefault = true),
        )
        execute(
            connection,
            variantSql(id = 3, articleId = 1, sizeLabel = "XL", sizeId = 14, isDefault = false),
        )
    }

    /** An image is a listing image or a mockup, at one place, and a colour has one mockup. */
    private fun assertImagesHaveARoleAPlaceAndOneMockup(connection: Connection) {
        execute(connection, imageSql(role = "LISTING", position = 1))
        execute(connection, imageSql(role = "MOCKUP", position = 1))

        assertSqlState("23514", connection, imageSql(role = "BACK", position = 2))
        assertSqlState("23505", connection, imageSql(role = "LISTING", position = 1))
        assertSqlState("23505", connection, imageSql(role = "MOCKUP", position = 2))
        execute(connection, imageSql(role = "LISTING", position = 2))
    }

    private fun assertDeletingTheIdentityRemovesTheArticle(connection: Connection) {
        execute(connection, "DELETE FROM voenix.article_identities WHERE id = 1")

        assertEquals(0, countOf(connection, "voenix.article_spod_articles", "id = 1"))
        assertEquals(0, countOf(connection, "voenix.article_spod_appearances", "article_id = 1"))
        assertEquals(0, countOf(connection, "voenix.article_spod_images", "appearance_id = 1"))
        assertEquals(0, countOf(connection, "voenix.article_spod_variants", "article_id = 1"))
        assertEquals(0, countOf(connection, "voenix.article_variant_identities", "article_id = 1"))
        assertEquals(1, countOf(connection, "voenix.article_spod_articles", "id = 2"))
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

    private fun articleSql(
        id: Int,
        position: Int,
        columns: Map<String, String> = emptyMap(),
    ): String =
        insertSql(
            "voenix.article_spod_articles",
            mapOf(
                "id" to "$id",
                "position" to "$position",
                "name" to "'Article $position'",
                "description_short" to "'Short'",
                "description_long" to "'Long'",
                "active" to "FALSE",
                "supplier_id" to "1",
                "spod_destination_id" to "1",
                "spod_environment" to "'PRODUCTION'",
                "spod_article_id" to "'a-$position'",
                "spod_synced_at" to "TIMESTAMPTZ '2026-08-24 09:00:00+00'",
                "spod_product_type_id" to "300",
                "spod_product_type_name" to "'T-Shirt'",
            ) + columns,
        )

    private fun appearanceSql(
        articleId: Int,
        spodAppearanceId: Int,
        colorName: String = "Colour $spodAppearanceId",
        id: Int = 90,
    ): String =
        insertSql(
            "voenix.article_spod_appearances",
            mapOf(
                "id" to "$id",
                "article_id" to "$articleId",
                "spod_appearance_id" to "$spodAppearanceId",
                "color_name" to "'$colorName'",
                "color_hex" to "'#000000'",
                "position" to "2",
            ),
        )

    @Suppress("LongParameterList")
    private fun variantSql(
        id: Int,
        articleId: Int,
        sizeLabel: String = "M",
        productTypeId: Int = 300,
        sizeId: Int = 12,
        isDefault: Boolean = true,
    ): String =
        insertSql(
            "voenix.article_spod_variants",
            mapOf(
                "id" to "$id",
                "article_id" to "$articleId",
                "appearance_id" to "1",
                "size_label" to "'$sizeLabel'",
                "spod_product_type_id" to "$productTypeId",
                "spod_size_id" to "$sizeId",
                "spod_variant_id" to "'v-$id'",
                "is_default" to "$isDefault",
                "active" to "TRUE",
            ),
        )

    private fun imageSql(
        role: String,
        position: Int,
    ): String =
        insertSql(
            "voenix.article_spod_images",
            mapOf(
                "appearance_id" to "1",
                "role" to "'$role'",
                "position" to "$position",
                "filename" to "'$role-$position.webp'",
            ),
        )

    private fun insertSql(
        table: String,
        values: Map<String, String>,
    ): String =
        "INSERT INTO $table (${values.keys.joinToString(", ")}) " +
            "VALUES (${values.values.joinToString(", ")})"

    private fun execute(
        connection: Connection,
        sql: String,
    ) {
        connection.createStatement().use { statement -> statement.execute(sql.trimIndent()) }
    }

    private fun assertSqlState(
        expected: String,
        connection: Connection,
        sql: String,
    ) = ArticleTestSchema.assertSqlState(expected, connection, sql)

    private companion object {
        /** A frame a measuring run would write, with the hotspot it was measured for. */
        val MEASURED_FRAME =
            mapOf(
                "print_frame_left_pct" to "35.60",
                "print_frame_top_pct" to "18.90",
                "print_frame_width_pct" to "28.40",
                "print_frame_height_pct" to "28.40",
                "spod_hotspot" to "'MEDIUM_FRONT'",
            )
    }
}
