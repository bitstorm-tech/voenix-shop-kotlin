package shop.voenix.article.spod

import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import shop.voenix.article.ArticleTestSchema
import shop.voenix.article.SyncedSpodArticles
import shop.voenix.article.SyncedSpodVariant
import shop.voenix.spod.SpodClient
import shop.voenix.testing.PostgresIntegrationTest

/**
 * What a sync run does to the database, driven through the real [SpodClient] against a `MockEngine`
 * and a real PostgreSQL schema.
 *
 * The tests are written against the two things the SPOD article sync promises an operator. The
 * partner owns the product: an article appears, changes, loses a colour, disappears, and comes back
 * without anyone typing anything. The shop owns the rest: whatever an admin decided about an
 * article survives every run, and no run ever activates an article or deletes a variant.
 *
 * The engine records every request, which is how the third promise is checked at all — a second
 * identical run downloads no picture. A picture that did not change is not fetched again, and the
 * file the shop stored for it is still the file the row points at. What every run *does* do again
 * is measure the print frame; that is [SpodCatalogSyncCalibrationIntegrationTest]'s subject.
 *
 * This class holds the create, update, missing, and default-variant cases; listing failures,
 * pictures and downloads, the size chart, and the busy refusal are in
 * [SpodCatalogSyncListingIntegrationTest], and the fixtures of all three are in
 * `SpodCatalogSyncTestSupport.kt` and `CalibrationFixtures.kt`.
 */
internal class SpodCatalogSyncIntegrationTest : PostgresIntegrationTest() {
    private val fixtures = SyncFixtures()

    @Test
    fun `a first run creates the articles the backoffice lists`() = runBlocking {
        migratedDataSource("spod-sync-create-test").use { dataSource ->
            seed(dataSource)
            val fixture = fixtures.of(dataSource, catalog(twoColourShirt()))

            val report = fixture.run()

            assertEquals(SpodSyncStatus.COMPLETED, report.status)
            assertEquals(1, report.fetchedArticles)
            assertEquals(listOf(1L), report.created.map(SpodSyncLine::articleId))
            assertEquals(4, report.created.single().variantsCreated)
            assertEquals("FULL_FRONT", report.created.single().hotspot)
            assertEquals(TEE_FRAME, report.created.single().printFrame)
            assertEquals(emptyList(), report.warnings)

            val article = storedArticle(dataSource, 1)
            assertEquals("Classic Shirt", article.getString("name"))
            assertEquals("A shirt", article.getString("description_long"))
            assertEquals(1, article.getInt("position"))
            assertEquals(false, article.getBoolean("active"))
            assertEquals(SUPPLIER_ID, article.getLong("supplier_id"))
            assertNull(article.getObject("price_id"))
            assertNull(article.getObject("category_id"))
            // The frame is the larger of the two printable front hotspots, measured to two
            // decimals.
            assertEquals(TEE_STORED_FRAME, storedFrame(dataSource, 1))
            assertEquals(PRODUCT_TYPE_ID, article.getLong("spod_product_type_id"))
            assertEquals(PRODUCT_TYPE_NAME, article.getString("spod_product_type_name"))
            assertEquals("a-1", article.getString("spod_article_id"))
            assertEquals("PRODUCTION", article.getString("spod_environment"))
            assertNull(article.getObject("spod_missing_since"))
            assertEquals(SIZE_CHART_URL, article.getString("spod_size_chart_url"))

            assertEquals(
                listOf(
                    StoredAppearance(5, "Black", "#101010", position = 1),
                    StoredAppearance(6, "White", "#ffffff", position = 2),
                ),
                appearances(dataSource, 1),
            )
            assertEquals(
                classicShirtRows(blackActive = true, whiteActive = true),
                variants(dataSource, 1),
            )
            // Every image the partner lists for a colour, in its order and shared by that colour's
            // sizes, the mockup of each colour, plus the size chart — each listing image fetched
            // with the product id in place of the partner's `lookupId` placeholder.
            assertEquals(6, fixture.storage.storeCalls)
            assertEquals(
                listOf(
                    "/image-server/v1/products/$PRODUCT_ID/views/1,appearanceId=5,mediaType=png/a-1-i-1.png",
                    "/image-server/v1/products/$PRODUCT_ID/views/1,appearanceId=5,mediaType=png/a-1-i-2.png",
                    "/image-server/v1/products/$PRODUCT_ID/views/1,appearanceId=6,mediaType=png/a-1-i-3.png",
                ),
                fixture.hits.filter { path -> path.contains("/image-server/") },
            )
            assertNotNull(article.getString("size_chart_image_filename"))
            assertEquals(
                listOf(
                    StoredListingImage(5, 1, "a-1-i-1", filename(0)),
                    StoredListingImage(5, 2, "a-1-i-2", filename(1)),
                    StoredListingImage(6, 1, "a-1-i-3", filename(3)),
                ),
                listingImages(dataSource, 1),
            )
            assertEquals(
                listOf(
                    StoredMockup(5, filename(2), mockupUrl(PRODUCT_TYPE_ID, 5)),
                    StoredMockup(6, filename(4), mockupUrl(PRODUCT_TYPE_ID, 6)),
                ),
                mockups(dataSource, 1),
            )
        }
    }

    /**
     * The product kind is the partner's word for it, stored as the subtitle a storefront shows: cut
     * to its column, and an empty string when the partner names none.
     */
    @Test
    fun `the product type name is stored as the partner names it`() = runBlocking {
        migratedDataSource("spod-sync-product-type-name-test").use { dataSource ->
            seed(dataSource)
            val long = "T".repeat(300)
            val named =
                article(
                    variants = listOf(variant("v-1", appearanceId = 5, productTypeName = long)),
                    images = listOf(image("i-1", appearanceId = 5)),
                )
            val blank =
                article(
                    id = "a-2",
                    variants = listOf(variant("v-2", appearanceId = 5, productTypeName = "  ")),
                    images = listOf(image("i-2", appearanceId = 5)),
                )

            fixtures.of(dataSource, catalog(named, blank)).run()

            assertEquals(
                long.take(255),
                storedArticle(dataSource, 1).getString("spod_product_type_name"),
            )
            assertEquals("", storedArticle(dataSource, 2).getString("spod_product_type_name"))
        }
    }

    /**
     * The second run asks the partner for the listing, the views, and the size chart, and measures
     * the frame again — one design upload, one preview per printable hotspot (issue #260, decision
     * 11) — but fetches no picture it already has: neither a listing image nor a mockup.
     */
    @Test
    fun `an identical second run writes nothing and downloads no picture`() = runBlocking {
        migratedDataSource("spod-sync-noop-test").use { dataSource ->
            seed(dataSource)
            val fixture = fixtures.of(dataSource, catalog(twoColourShirt()))
            val first = fixture.run()
            val syncedAt = storedArticle(dataSource, 1).getInstant("spod_synced_at")
            val storedVariants = variants(dataSource, 1)
            fixture.hits.clear()

            val second = fixture.run()

            assertEquals(listOf(1L), second.unchanged.map(SpodSyncLine::articleId))
            assertEquals(emptyList(), second.created)
            assertEquals(emptyList(), second.updated)
            assertEquals(emptyList(), second.deactivated)
            assertEquals(emptyList(), second.warnings)
            assertEquals("FULL_FRONT", second.unchanged.single().hotspot)
            assertEquals(
                listOf(
                    "/articles",
                    "/productTypes/812/views",
                    "/designs/upload",
                    "/productTypes/812/hotspots/design/$DESIGN_ID",
                    "/productTypes/812/previews",
                    "/previews/812/FULL_FRONT,mediaType=png",
                    "/productTypes/812/previews",
                    "/previews/812/MEDIUM_FRONT,mediaType=png",
                    "/productTypes/812/size-chart",
                ),
                fixture.hits,
                "The second run measures again but must not download a picture it already has",
            )
            assertEquals(6, fixture.storage.storeCalls)
            assertEquals(emptyList(), fixture.storage.deleted)
            assertEquals(storedVariants, variants(dataSource, 1))
            assertTrue(
                storedArticle(dataSource, 1).getInstant("spod_synced_at") > syncedAt,
                "The run stamps the article even when nothing else changed",
            )
            assertEquals(first.created.single().articleId, second.unchanged.single().articleId)
        }
    }

    @Test
    fun `a run overwrites the product and leaves everything the shop owns alone`() = runBlocking {
        migratedDataSource("spod-sync-ownership-test").use { dataSource ->
            seed(dataSource)
            fixtures.of(dataSource, catalog(twoColourShirt())).run()
            makeShopOwnedDecisions(dataSource)

            val renamed = twoColourShirt(title = "Renamed Shirt", description = "Another text")
            fixtures.of(dataSource, catalog(renamed)).run()

            val article = storedArticle(dataSource, 1)
            assertEquals("Renamed Shirt", article.getString("name"))
            assertEquals("Another text", article.getString("description_long"))
            assertEquals(true, article.getBoolean("active"))
            assertEquals(1L, article.getLong("category_id"))
            assertEquals(1L, article.getLong("price_id"))
            // The frame is the partner's half: the run overwrites what was there.
            assertEquals(TEE_STORED_FRAME, storedFrame(dataSource, 1))
            assertEquals(
                listOf(false, false, true, false),
                variants(dataSource, 1).map(StoredVariant::isDefault),
                "The default an admin chose stays the default",
            )
        }
    }

    @Test
    fun `a colour the backoffice dropped leaves its variants inactive and keeps the rows`() =
        runBlocking {
            migratedDataSource("spod-sync-colour-test").use { dataSource ->
                seed(dataSource)
                fixtures.of(dataSource, catalog(twoColourShirt())).run()

                val fixture = fixtures.of(dataSource, catalog(blackOnlyShirt()))
                val report = fixture.run()

                assertEquals(2, report.updated.single().variantsDeactivated)
                assertEquals(
                    classicShirtRows(blackActive = true, whiteActive = false),
                    variants(dataSource, 1),
                    "A variant is deactivated, never deleted",
                )
            }
        }

    @Test
    fun `a dropped colour that carried the default hands it to an active variant`() = runBlocking {
        migratedDataSource("spod-sync-default-test").use { dataSource ->
            seed(dataSource)
            fixtures.of(dataSource, catalog(twoColourShirt())).run()
            ArticleTestSchema.execute(
                dataSource,
                """
                UPDATE voenix.article_spod_variants SET is_default = FALSE WHERE id = 1;
                UPDATE voenix.article_spod_variants SET is_default = TRUE WHERE id = 3;
                """
                    .trimIndent(),
            )

            val report = fixtures.of(dataSource, catalog(blackOnlyShirt())).run()

            assertEquals(
                listOf(true, false, false, false),
                variants(dataSource, 1).map(StoredVariant::isDefault),
            )
            assertEquals(
                listOf(
                    SpodSyncWarningCode.DEFAULT_VARIANT_REPLACED,
                    SpodSyncWarningCode.EXAMPLE_IMAGE_REPLACED,
                ),
                report.warnings.map(SpodSyncWarning::code),
            )
        }
    }

    @Test
    fun `an article the backoffice dropped is marked missing and comes back inactive`() =
        runBlocking {
            migratedDataSource("spod-sync-missing-test").use { dataSource ->
                seed(dataSource)
                fixtures.of(dataSource, catalog(twoColourShirt())).run()
                makeShopOwnedDecisions(dataSource)

                val swept = fixtures.of(dataSource, catalog()).run()

                assertEquals(listOf(1L), swept.deactivated.map(SpodSyncLine::articleId))
                assertEquals(4, swept.deactivated.single().variantsDeactivated)
                assertEquals(false, storedArticle(dataSource, 1).getBoolean("active"))
                assertNotNull(storedArticle(dataSource, 1).getString("spod_missing_since"))
                assertTrue(variants(dataSource, 1).none(StoredVariant::active))

                val returned = fixtures.of(dataSource, catalog(twoColourShirt())).run()

                assertEquals(listOf(1L), returned.updated.map(SpodSyncLine::articleId))
                assertEquals(
                    listOf(SpodSyncWarningCode.ARTICLE_REAPPEARED),
                    returned.warnings.map(SpodSyncWarning::code),
                )
                assertNull(storedArticle(dataSource, 1).getObject("spod_missing_since"))
                assertEquals(
                    false,
                    storedArticle(dataSource, 1).getBoolean("active"),
                    "Only an admin activates an article",
                )
            }
        }

    @Test
    fun `an article of the other installation is missing even when its id is listed again`() =
        runBlocking {
            migratedDataSource("spod-sync-environment-switch-test").use { dataSource ->
                seed(dataSource)
                SyncedSpodArticles.insert(
                    dataSource,
                    id = 7,
                    supplierId = SUPPLIER_ID,
                    environment = "STAGING",
                    spodArticleId = "a-1",
                    variants = listOf(SyncedSpodVariant(id = 70)),
                )

                val report = fixtures.of(dataSource, catalog(twoColourShirt(id = "a-1"))).run()

                assertEquals(listOf(7L), report.deactivated.map(SpodSyncLine::articleId))
                assertNotNull(storedArticle(dataSource, 7).getString("spod_missing_since"))
                val createdId = checkNotNull(report.created.single().articleId)
                assertEquals(
                    "PRODUCTION",
                    storedArticle(dataSource, createdId).getString("spod_environment"),
                )
                assertEquals(
                    "a-1",
                    storedArticle(dataSource, createdId).getString("spod_article_id"),
                )
            }
        }

    @Test
    fun `a run marks a missing article once and reports it once`() = runBlocking {
        migratedDataSource("spod-sync-missing-once-test").use { dataSource ->
            seed(dataSource)
            fixtures.of(dataSource, catalog(twoColourShirt())).run()
            fixtures.of(dataSource, catalog()).run()
            val markedAt = storedArticle(dataSource, 1).getString("spod_missing_since")

            val again = fixtures.of(dataSource, catalog()).run()

            assertEquals(emptyList(), again.deactivated)
            assertEquals(markedAt, storedArticle(dataSource, 1).getString("spod_missing_since"))
        }
    }

    @Test
    fun `a colour without a readable value or a picture makes its variants unsellable`() =
        runBlocking {
            migratedDataSource("spod-sync-degraded-test").use { dataSource ->
                seed(dataSource)
                val degraded =
                    article(
                        variants =
                            listOf(
                                variant("v-1", appearanceId = 5, colorValue = "sky blue"),
                                variant(
                                    "v-2",
                                    appearanceId = 6,
                                    appearanceName = "White",
                                    colorValue = "#fff",
                                ),
                            ),
                        images = listOf(image("i-1", appearanceId = 5)),
                    )
                val fixture = fixtures.of(dataSource, catalog(degraded))

                val report = fixture.run()

                assertEquals(
                    listOf(
                        variantRow("Black", "#cccccc", "M", active = false, isDefault = true),
                        variantRow("White", "#ffffff", "M", active = false, isDefault = false),
                    ),
                    variants(dataSource, 1),
                )
                assertEquals(
                    listOf(
                        SpodSyncWarningCode.COLOR_VALUE_UNREADABLE,
                        SpodSyncWarningCode.COLOR_WITHOUT_IMAGE,
                    ),
                    report.warnings.map(SpodSyncWarning::code),
                )
                assertEquals(
                    listOf("a-1", "a-1"),
                    report.warnings.map(SpodSyncWarning::spodArticleId),
                )
            }
        }
}
