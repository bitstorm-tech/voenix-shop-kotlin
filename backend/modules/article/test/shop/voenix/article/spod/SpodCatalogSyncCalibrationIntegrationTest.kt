package shop.voenix.article.spod

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import shop.voenix.article.ArticleTestSchema
import shop.voenix.testing.PostgresIntegrationTest

/**
 * How a sync run measures the print frame (SPOD sync decision 7): once per product type and per
 * run, from the partner's previews of the calibration design, picking the largest printable front
 * hotspot; and how the mockup of every colour comes from the partner's views (issue #260, decision
 * 15). The partner is a `MockEngine` whose calibration answers are in `CalibrationFixtures.kt`,
 * with preview pictures the test draws itself, so the expected frame is known to the pixel.
 */
internal class SpodCatalogSyncCalibrationIntegrationTest : PostgresIntegrationTest() {
    private val fixtures = SyncFixtures()

    /**
     * Two articles of one product type cost one views call, one design upload, one hotspot check,
     * and one preview per printable hotspot — and both get the same frame, as do both colours of
     * each article, because the frame is a property of the product type.
     */
    @Test
    fun `a product type is measured once per run and every article of it gets the frame`() =
        runBlocking {
            migratedDataSource("spod-sync-calibration-once-test").use { dataSource ->
                seed(dataSource)
                val fixture =
                    fixtures.of(
                        dataSource,
                        catalog(twoColourShirt(), twoColourShirt(id = "a-2", title = "Second")),
                    )

                val report = fixture.run()

                assertEquals(listOf("FULL_FRONT", "FULL_FRONT"), report.created.map { it.hotspot })
                assertEquals(TEE_STORED_FRAME, storedFrame(dataSource, 1))
                assertEquals(TEE_STORED_FRAME, storedFrame(dataSource, 2))
                assertEquals(1, fixture.hits.count { path -> path == "/designs/upload" })
                assertEquals(1, fixture.hits.count { path -> path.endsWith("/views") })
                assertEquals(1, fixture.hits.count { path -> path.contains("/hotspots/design/") })
                assertEquals(
                    listOf(
                        "/previews/812/FULL_FRONT,mediaType=png",
                        "/previews/812/MEDIUM_FRONT,mediaType=png",
                    ),
                    fixture.hits.filter { path -> path.startsWith("/previews/") },
                    "Only the two hotspots the design is printable on are previewed",
                )
            }
        }

    /** A second product type in the same run is measured on its own; the design is shared. */
    @Test
    fun `each product type of a run gets its own frame from one shared design`() = runBlocking {
        migratedDataSource("spod-sync-calibration-two-types-test").use { dataSource ->
            seed(dataSource)
            val fixture = fixtures.of(dataSource, catalog(twoColourShirt(), tote()))

            val report = fixture.run()

            assertEquals(TEE_STORED_FRAME, storedFrame(dataSource, 1))
            assertEquals(
                StoredFrame("FULL_FRONT", "33.80", "53.60", "32.40", "32.40"),
                storedFrame(dataSource, 2),
            )
            assertEquals(TOTE_FRAME, report.created.last().printFrame)
            assertEquals(1, fixture.hits.count { path -> path == "/designs/upload" })
            assertEquals(
                1,
                fixture.hits.count { path -> path == "/previews/2973/FULL_FRONT,mediaType=png" },
            )
        }
    }

    /**
     * A box that is not a flat square — here one with a hole, so its fill is below the threshold —
     * is no frame at all (issue #260, decision 12): the article loses frame and hotspot, goes
     * inactive, and the report says why and which hotspots were tried.
     */
    @Test
    fun `a preview whose box does not fill fails the calibration and deactivates the article`() =
        runBlocking {
            migratedDataSource("spod-sync-calibration-hole-test").use { dataSource ->
                seed(dataSource)
                fixtures.of(dataSource, catalog(twoColourShirt())).run()
                makeShopOwnedDecisions(dataSource)

                val report =
                    fixtures
                        .of(
                            dataSource,
                            catalog(twoColourShirt()),
                            calibration = CalibrationAnswers(holed = true),
                        )
                        .run()

                assertEquals(listOf(1L), report.updated.map(SpodSyncLine::articleId))
                assertNull(report.updated.single().hotspot)
                assertNull(report.updated.single().printFrame)
                assertEquals(NO_STORED_FRAME, storedFrame(dataSource, 1))
                assertEquals(false, storedArticle(dataSource, 1).getBoolean("active"))
                val warning = report.warnings.single()
                assertEquals(SpodSyncWarningCode.CALIBRATION_FAILED, warning.code)
                assertEquals("a-1", warning.spodArticleId)
                assertTrue(
                    warning.detail.contains("FULL_FRONT: LOW_FILL") &&
                        warning.detail.contains("MEDIUM_FRONT: LOW_FILL"),
                    warning.detail,
                )
                assertTrue(
                    variants(dataSource, 1).all(StoredVariant::active),
                    "The colours are still for sale; only the article cannot be",
                )
            }
        }

    /**
     * A partner that does not answer the views says nothing about the product type — not that it
     * cannot be measured. The article is skipped like an unreadable listing: the stored row stays
     * as it is, and not a single picture is downloaded for it.
     */
    @Test
    fun `an article whose views the partner refuses is skipped and nothing is written`() =
        runBlocking {
            migratedDataSource("spod-sync-calibration-no-views-test").use { dataSource ->
                seed(dataSource)
                fixtures.of(dataSource, catalog(twoColourShirt())).run()
                makeShopOwnedDecisions(dataSource)
                val before = storedArticle(dataSource, 1).getInstant("spod_synced_at")
                val fixture =
                    fixtures.of(
                        dataSource,
                        catalog(twoColourShirt()),
                        calibration = CalibrationAnswers(viewsRefused = true),
                    )

                val report = fixture.run()

                assertEquals(SpodSyncStatus.COMPLETED, report.status)
                assertEquals(listOf("a-1"), report.failed.map(SpodSyncLine::spodArticleId))
                assertEquals(emptyList(), report.updated + report.unchanged + report.deactivated)
                val warning = report.warnings.single()
                assertEquals(SpodSyncWarningCode.CALIBRATION_UNAVAILABLE, warning.code)
                assertEquals("views: PROVIDER_UNAVAILABLE", warning.detail)
                val stored = storedArticle(dataSource, 1)
                assertEquals(true, stored.getBoolean("active"), "the row is left as it was")
                assertEquals(before, stored.getInstant("spod_synced_at"))
                assertEquals(0, fixture.storage.storeCalls, "nothing is downloaded for it")
                assertEquals(1, fixture.hits.count { path -> path.endsWith("/views") })
                assertEquals(0, fixture.hits.count { path -> path == "/designs/upload" })
            }
        }

    /**
     * A refused call is not remembered for the rest of the run: the first article's design upload
     * fails and skips it, and the next product type uploads the design again and is measured.
     */
    @Test
    fun `a refused design upload skips its article and the next product type tries again`() =
        runBlocking {
            migratedDataSource("spod-sync-calibration-upload-retry-test").use { dataSource ->
                seed(dataSource)
                val fixture =
                    fixtures.of(
                        dataSource,
                        catalog(twoColourShirt(), tote()),
                        calibration = CalibrationAnswers(refusedDesignUploads = 1),
                    )

                val report = fixture.run()

                assertEquals(listOf("a-1"), report.failed.map(SpodSyncLine::spodArticleId))
                assertEquals(
                    "designs/upload: PROVIDER_UNAVAILABLE",
                    report.warnings.single().detail,
                )
                assertEquals(listOf("a-tote"), report.created.map(SpodSyncLine::spodArticleId))
                assertEquals(TOTE_FRAME, report.created.single().printFrame)
                assertEquals(1, ArticleTestSchema.rowCount(dataSource, "article_spod_articles"))
                assertEquals(2, fixture.hits.count { path -> path == "/designs/upload" })
                assertTrue(
                    fixture.hits.none { path -> path.contains("/a-1-i-") },
                    "nothing is downloaded for the skipped article",
                )
            }
        }

    /**
     * The mockup of a colour is the blank front picture of the views answer, stored in the mockup
     * folder — one file per colour, so switching the colour in the editor swaps the backdrop while
     * the frame, a property of the article, stays. A run that finds the same URL stored keeps the
     * file and asks the image server for nothing.
     */
    @Test
    fun `every colour gets its own mockup in the mockup folder, reused while its URL stays`() =
        runBlocking {
            migratedDataSource("spod-sync-calibration-mockups-test").use { dataSource ->
                seed(dataSource)
                val fixture = fixtures.of(dataSource, catalog(twoColourShirt()))
                fixture.run()

                val stored = mockups(dataSource, 1)
                assertEquals(listOf(5L, 6L), stored.map(StoredMockup::spodAppearanceId))
                assertEquals(2, stored.map(StoredMockup::filename).distinct().size)
                stored.forEach { mockup ->
                    assertEquals("articles/spod/mockups", fixture.storage.folders[mockup.filename])
                }
                assertEquals(
                    listOf(mockupUrl(PRODUCT_TYPE_ID, 5), mockupUrl(PRODUCT_TYPE_ID, 6)),
                    stored.map(StoredMockup::sourceUrl),
                )

                fixture.hits.clear()
                val second = fixture.run()

                assertEquals(listOf(1L), second.unchanged.map(SpodSyncLine::articleId))
                assertEquals(
                    1,
                    fixture.hits.count { path -> path == "/designs/upload" },
                    "nothing of the calibration survives a run, so the second one measures again",
                )
                assertTrue(fixture.hits.any { path -> path.endsWith("/previews") })
                assertEquals(stored, mockups(dataSource, 1))
                assertEquals(0, fixture.hits.count { path -> path.startsWith("/mockups/") })
                assertEquals(emptyList(), fixture.storage.deleted)
            }
        }

    /** A mockup the partner moved to another URL is fetched again, and the old file is dropped. */
    @Test
    fun `a mockup with a new URL replaces the stored one`() = runBlocking {
        migratedDataSource("spod-sync-calibration-moved-mockup-test").use { dataSource ->
            seed(dataSource)
            val first = fixtures.of(dataSource, catalog(twoColourShirt()))
            first.run()
            val before = mockups(dataSource, 1)
            val movedTo = mockupUrl(PRODUCT_TYPE_ID, 5).replace("/5.png", "/5-moved.png")

            val moved =
                CalibrationAnswers(
                    mockupOf = { productTypeId, appearanceId ->
                        if (appearanceId == 5L) movedTo else mockupUrl(productTypeId, appearanceId)
                    }
                )
            val fixture = fixtures.of(dataSource, catalog(twoColourShirt()), calibration = moved)
            val report = fixture.run()

            assertEquals(listOf(1L), report.updated.map(SpodSyncLine::articleId))
            val after = mockups(dataSource, 1)
            assertEquals(movedTo, after.first().sourceUrl)
            assertTrue(after.first().filename != before.first().filename)
            assertEquals(before.last(), after.last(), "the other colour's mockup is untouched")
            assertEquals(listOf(before.first().filename), fixture.storage.deleted)
        }
    }
}

/** A one-colour tote bag, the second product type of these tests. */
private fun tote() =
    article(
        id = "a-tote",
        title = "Tote",
        variants =
            listOf(
                variant(
                    "t-v-1",
                    appearanceId = 5,
                    sizeId = 29,
                    sizeName = "One size",
                    productTypeId = TOTE_PRODUCT_TYPE_ID,
                    productTypeName = "Tote Bag",
                )
            ),
        images = listOf(image("t-i-1", appearanceId = 5)),
    )
