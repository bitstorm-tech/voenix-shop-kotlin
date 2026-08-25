package shop.voenix.article

import javax.sql.DataSource

/**
 * Writes t-shirts the way the only writer of t-shirts writes them: straight into the tables.
 *
 * Since ADR 0003 a shirt is created by a sync run against the Spreadconnect backoffice, so the
 * admin API has no create route any more and a test cannot ask one for a fixture. These helpers
 * insert the rows instead — the identity, the article with its SPOD identity columns, and one
 * variant row per colour and size — and hand back nothing but the ids the test chose itself.
 *
 * They are deliberately plain SQL with named defaults rather than a fixture framework: a test says
 * only what its own subject needs, everything else is a sensible constant, and the next suite that
 * needs synced shirts copies the three calls instead of learning an abstraction.
 *
 * A shirt references the production destination it was synced from, so [seedSpodDestination] has to
 * run first, and the supplier it names has to exist (`ArticleTestSchema.seedSuppliers`).
 */
internal object SyncedTshirts {
    /** The timestamp every fixture shirt was last synced at, unless a test names another one. */
    const val SYNCED_AT: String = "2026-08-24T09:00:00Z"

    /** The SPOD destination the fixture shirts belong to. */
    fun seedSpodDestination(
        dataSource: DataSource,
        id: Long = 1,
        supplierId: Long = 1,
        label: String = "Spreadconnect",
    ) {
        ArticleTestSchema.execute(
            dataSource,
            """
            INSERT INTO voenix.production_destinations (id, supplier_id, channel, label)
            VALUES ($id, $supplierId, 'SPOD', '$label')
            """
                .trimIndent(),
        )
    }

    /**
     * One synced t-shirt with [id] as its article id, at [position] in the display order.
     *
     * The ids are chosen by the caller rather than generated, because every assertion about a shirt
     * names one, and a fixture whose ids depend on insertion order makes a failing test hard to
     * read. The variant ids are chosen the same way, in [variants].
     */
    @Suppress("LongParameterList")
    fun insert(
        dataSource: DataSource,
        id: Long,
        position: Int = id.toInt(),
        name: String = "Shirt $id",
        active: Boolean = false,
        categoryId: Long? = null,
        subcategoryId: Long? = null,
        supplierId: Long = 1,
        priceId: Long? = null,
        sizeChartImageFilename: String? = null,
        destinationId: Long = 1,
        environment: String = "PRODUCTION",
        spodArticleId: String = "spod-article-$id",
        syncedAt: String = SYNCED_AT,
        missingSince: String? = null,
        variants: List<SyncedTshirtVariant> = emptyList(),
    ) {
        ArticleTestSchema.execute(
            dataSource,
            """
            INSERT INTO voenix.article_identities (id, article_type) VALUES ($id, 'TSHIRT');
            INSERT INTO voenix.article_tshirts (
                id, position, name, description_short, description_long, active,
                category_id, subcategory_id, supplier_id, price_id,
                size_chart_image_filename,
                print_frame_left_pct, print_frame_top_pct,
                print_frame_width_pct, print_frame_height_pct,
                spod_destination_id, spod_environment, spod_article_id,
                spod_synced_at, spod_missing_since
            ) VALUES (
                $id, $position, '$name', 'Short', 'Long', $active,
                ${categoryId ?: "NULL"}, ${subcategoryId ?: "NULL"}, $supplierId,
                ${priceId ?: "NULL"}, ${sizeChartImageFilename.quoted()},
                25.00, 20.00, 50.00, 40.00,
                $destinationId, '$environment', '$spodArticleId',
                '$syncedAt', ${missingSince.quoted()}
            );
            """
                .trimIndent(),
        )
        variants.forEach { variant -> insertVariant(dataSource, id, variant) }
    }

    private fun insertVariant(
        dataSource: DataSource,
        articleId: Long,
        variant: SyncedTshirtVariant,
    ) {
        ArticleTestSchema.execute(
            dataSource,
            """
            INSERT INTO voenix.article_variant_identities (id, article_id, article_type)
            VALUES (${variant.id}, $articleId, 'TSHIRT');
            INSERT INTO voenix.article_tshirt_variants (
                id, article_id, color_name, color_hex, size_label,
                spod_product_type_id, spod_appearance_id, spod_size_id,
                spod_variant_id, sku, is_default, active, example_image_filename
            ) VALUES (
                ${variant.id}, $articleId, '${variant.colorName}', '${variant.colorHex}',
                '${variant.sizeLabel}', ${variant.spodProductTypeId}, ${variant.spodAppearanceId},
                ${variant.spodSizeId}, 'spod-variant-${variant.id}', ${variant.sku.quoted()},
                ${variant.isDefault}, ${variant.active},
                ${variant.exampleImageFilename.quoted()}
            );
            """
                .trimIndent(),
        )
    }

    private fun String?.quoted(): String = this?.let { value -> "'$value'" } ?: "NULL"
}

/**
 * One variant of a fixture shirt: a colour in a size, and the printable product the two name at the
 * partner.
 *
 * The three SPOD ids default to one garment in one colour, so a test that needs two variants only
 * says what makes them different — usually the size and its `spodSizeId`.
 */
internal data class SyncedTshirtVariant(
    val id: Long,
    val colorName: String = "Black",
    val colorHex: String = "#000000",
    val sizeLabel: String = "M",
    val spodProductTypeId: Long = 812,
    val spodAppearanceId: Long = 5,
    val spodSizeId: Long = 91,
    val sku: String? = null,
    val isDefault: Boolean = false,
    val active: Boolean = true,
    val exampleImageFilename: String? = null,
)
