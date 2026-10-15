package shop.voenix.article.spod

import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import shop.voenix.article.ArticleTestSchema
import shop.voenix.article.SyncedSpodArticles
import shop.voenix.article.SyncedSpodVariant
import shop.voenix.spod.SpodError
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The half of the sync suite about what the partner's answers can do to a run: listings that are
 * not complete catalogs, pictures and downloads that fail or race a delete, ids that do not fit,
 * the size chart, and the second run of a destination that is refused while the first one works.
 * The fixtures are in `SpodCatalogSyncTestSupport.kt`; the create, update, and missing cases are in
 * [SpodCatalogSyncIntegrationTest].
 */
internal class SpodCatalogSyncListingIntegrationTest : PostgresIntegrationTest() {
    private val fixtures = SyncFixtures()

    @Test
    fun `a listing that could not be read writes nothing and sweeps nothing`() = runBlocking {
        migratedDataSource("spod-sync-listing-failure-test").use { dataSource ->
            seed(dataSource)
            SyncedSpodArticles.insert(
                dataSource,
                id = 7,
                supplierId = SUPPLIER_ID,
                spodArticleId = "gone",
                variants = listOf(SyncedSpodVariant(id = 70)),
            )
            val fixture =
                fixtures.of(dataSource) { request ->
                    if (request.url.encodedPath == "/articles") {
                        respondError(HttpStatusCode.InternalServerError)
                    } else {
                        respondImage()
                    }
                }

            val report = fixture.run()

            assertEquals(SpodSyncStatus.FAILED, report.status)
            assertEquals(SpodError.PROVIDER_UNAVAILABLE, report.failure)
            assertEquals(0, report.fetchedArticles)
            assertEquals(emptyList(), report.deactivated)
            assertEquals(1, ArticleTestSchema.rowCount(dataSource, "article_spod_articles"))
            assertNull(storedArticle(dataSource, 7).getObject("spod_missing_since"))
            assertEquals(
                Instant.parse(SyncedSpodArticles.SYNCED_AT),
                storedArticle(dataSource, 7).getInstant("spod_synced_at"),
            )
            assertTrue(variants(dataSource, 7).all(StoredVariant::active))
        }
    }

    /**
     * A page that states no total says nothing about the size of the catalog. Treating it as
     * complete would sweep every article the shop has on the strength of an answer that promised
     * nothing.
     */
    @Test
    fun `a listing page without a count writes nothing and sweeps nothing`() = runBlocking {
        migratedDataSource("spod-sync-no-count-test").use { dataSource ->
            seed(dataSource)
            SyncedSpodArticles.insert(
                dataSource,
                id = 7,
                supplierId = SUPPLIER_ID,
                spodArticleId = "gone",
                variants = listOf(SyncedSpodVariant(id = 70)),
            )
            val fixture =
                fixtures.of(dataSource) { request ->
                    if (request.url.encodedPath == "/articles") {
                        respondJson("{}")
                    } else {
                        respondImage()
                    }
                }

            val report = fixture.run()

            assertEquals(SpodSyncStatus.FAILED, report.status)
            assertEquals(SpodError.PROVIDER_ANSWER_UNREADABLE, report.failure)
            assertEquals(0, report.fetchedArticles)
            assertEquals(emptyList(), report.deactivated)
            assertEquals(1, ArticleTestSchema.rowCount(dataSource, "article_spod_articles"))
            assertNull(storedArticle(dataSource, 7).getObject("spod_missing_since"))
            assertEquals(0, fixture.storage.storeCalls)
        }
    }

    /** Repeated articles would otherwise add up to the promised total and pass as the catalog. */
    @Test
    fun `a listing that repeats an article across pages is not a complete catalog`() = runBlocking {
        migratedDataSource("spod-sync-repeated-id-test").use { dataSource ->
            seed(dataSource)
            val listed = listOf(twoColourShirt(), twoColourShirt(id = "a-2"))
            val pages = ArrayDeque(listOf(catalog(listed, count = 4), catalog(listed, count = 4)))
            val fixture =
                fixtures.of(dataSource) { request ->
                    when {
                        request.url.encodedPath == "/articles" -> respondJson(pages.removeFirst())
                        request.url.encodedPath.endsWith("/size-chart") ->
                            respondJson(SIZE_CHART_ANSWER)
                        else -> respondImage()
                    }
                }

            val report = fixture.run()

            assertEquals(SpodSyncStatus.FAILED, report.status)
            assertEquals(SpodError.PROVIDER_ANSWER_UNREADABLE, report.failure)
            assertEquals(0, ArticleTestSchema.rowCount(dataSource, "article_spod_articles"))
        }
    }

    /**
     * A colour whose only image cannot be fetched is degraded, not fatal: the article is written,
     * the other colour keeps the images the run stored for it, and only the unfetched colour's
     * variants go inactive without a picture.
     */
    @Test
    fun `a colour whose picture cannot be fetched goes inactive, the other keeps its picture`() =
        runBlocking {
            migratedDataSource("spod-sync-orphan-test").use { dataSource ->
                seed(dataSource)
                val page = catalog(twoColourShirt())
                val fixture =
                    fixtures.of(dataSource) { request ->
                        when {
                            request.url.encodedPath == "/articles" -> respondJson(page)
                            request.url.encodedPath.endsWith("/size-chart") ->
                                respondJson(SIZE_CHART_ANSWER)
                            request.url.encodedPath.endsWith("a-1-i-3.png") ->
                                respondError(HttpStatusCode.NotFound)
                            else -> respondImage()
                        }
                    }

                val report = fixture.run()

                assertEquals(listOf("a-1"), report.created.map(SpodSyncLine::spodArticleId))
                assertEquals(emptyList(), report.failed)
                assertEquals(
                    listOf(
                        SpodSyncWarningCode.IMAGE_DOWNLOAD_FAILED,
                        SpodSyncWarningCode.COLOR_WITHOUT_IMAGE,
                    ),
                    report.warnings.map(SpodSyncWarning::code),
                )
                assertEquals(
                    classicShirtRows(blackActive = true, whiteActive = false),
                    variants(dataSource, 1),
                )
                assertEquals(
                    5,
                    fixture.storage.storeCalls,
                    "the first colour, both mockups, and the size chart",
                )
                assertEquals(emptyList(), fixture.storage.deleted)
                assertEquals(
                    listOf(filename(0), filename(1)),
                    listingImages(dataSource, 1).map(StoredListingImage::filename),
                )
            }
        }

    /**
     * One image of a colour that cannot be fetched is left out: the colour keeps the images that
     * were stored and stays for sale, and the next run asks for the missing one again because the
     * stored list of image ids differs from the listed one.
     */
    @Test
    fun `an image that cannot be fetched is left out and asked for again`() = runBlocking {
        migratedDataSource("spod-sync-partial-images-test").use { dataSource ->
            seed(dataSource)
            val page = catalog(twoColourShirt())
            var cdnAnswersAll = false
            val fixture =
                fixtures.of(dataSource) { request ->
                    when {
                        request.url.encodedPath == "/articles" -> respondJson(page)
                        request.url.encodedPath.endsWith("/size-chart") ->
                            respondJson(SIZE_CHART_ANSWER)
                        request.url.encodedPath.endsWith("a-1-i-1.png") && !cdnAnswersAll ->
                            respondError(HttpStatusCode.NotFound)
                        else -> respondImage()
                    }
                }

            val first = fixture.run()

            assertEquals(
                listOf(SpodSyncWarningCode.IMAGE_DOWNLOAD_FAILED),
                first.warnings.map(SpodSyncWarning::code),
            )
            assertEquals(
                classicShirtRows(blackActive = true, whiteActive = true),
                variants(dataSource, 1),
            )
            assertEquals(
                listOf("a-1-i-2", "a-1-i-3"),
                listingImages(dataSource, 1).map(StoredListingImage::spodImageId),
            )

            cdnAnswersAll = true
            fixture.hits.clear()
            fixture.run()

            assertEquals(
                listOf("a-1-i-1", "a-1-i-2", "a-1-i-3"),
                listingImages(dataSource, 1).map(StoredListingImage::spodImageId),
            )
            assertEquals(
                1,
                fixture.hits.count { path -> path.contains("/image-server/") },
                "Only the missing image is fetched; the stored ones are reused",
            )
            assertEquals(emptyList(), fixture.storage.deleted)
        }
    }

    /** An id longer than its column is an identity this shop could never match a run against. */
    @Test
    fun `an article whose id does not fit the column is skipped and nothing is written`() =
        runBlocking {
            migratedDataSource("spod-sync-long-id-test").use { dataSource ->
                seed(dataSource)
                val overlong = "a".repeat(65)

                val fixture = fixtures.of(dataSource, catalog(twoColourShirt(id = overlong)))
                val report = fixture.run()

                assertEquals(SpodSyncStatus.COMPLETED, report.status)
                assertEquals(listOf(overlong), report.failed.map(SpodSyncLine::spodArticleId))
                assertEquals(
                    listOf(SpodSyncWarningCode.SPOD_ID_UNUSABLE),
                    report.warnings.map(SpodSyncWarning::code),
                )
                assertEquals(0, ArticleTestSchema.rowCount(dataSource, "article_spod_articles"))
                assertEquals(0, fixture.storage.storeCalls, "nothing is downloaded for it either")
            }
        }

    /**
     * The race the reading half of the sync cannot avoid: `findForSync` reads which pictures the
     * article already has, the run reuses those file names instead of downloading again, and an
     * admin deletes the article — and with it those files — before the write.
     *
     * The second run keeps the listing images and only moves the size chart, so the colours are
     * reused and the one download of the run is the chart, which is what the delete happens during.
     * Writing the row back now would point it at files that are gone, so the article is prepared
     * once more from nothing: every picture of the re-created row is one *this* run stored.
     */
    @Test
    fun `an article deleted while a picture downloads is prepared again from nothing`() =
        runBlocking {
            migratedDataSource("spod-sync-delete-race-test").use { dataSource ->
                seed(dataSource)
                val first = fixtures.of(dataSource, catalog(twoColourShirt()))
                first.run()

                val page = catalog(twoColourShirt())
                var deleted = false
                val fixture =
                    fixtures.of(dataSource) { request ->
                        when {
                            request.url.encodedPath == "/articles" -> respondJson(page)
                            request.url.encodedPath.endsWith("/size-chart") ->
                                respondJson("""{"sizeImageUrl":"$MOVED_SIZE_CHART_URL"}""")
                            else -> {
                                if (!deleted) {
                                    deleted = true
                                    deleteArticle(dataSource, 1)
                                }
                                respondImage()
                            }
                        }
                    }

                val report = fixture.run()

                assertTrue(deleted, "the article really was deleted mid-run")
                val articleId = checkNotNull(report.created.single().articleId)
                val pictures =
                    listingImages(dataSource, articleId).map(StoredListingImage::filename)
                assertEquals(3, pictures.size)
                assertTrue(
                    pictures.all { picture -> picture in fixture.storage.files },
                    "the re-created row points at pictures this run downloaded and stored",
                )
                assertTrue(
                    pictures.none { picture -> picture in first.storage.files },
                    "and never at the files the delete took with it",
                )
                val chart =
                    storedArticle(dataSource, articleId).getString("size_chart_image_filename")
                assertTrue(chart in fixture.storage.files)
            }
        }

    @Test
    fun `an article with more than one product type is skipped and the others are written`() =
        runBlocking {
            migratedDataSource("spod-sync-mixed-test").use { dataSource ->
                seed(dataSource)
                val mixed =
                    article(
                        id = "a-mixed",
                        variants =
                            listOf(
                                variant("v-1", appearanceId = 5),
                                variant("v-2", appearanceId = 5, sizeId = 92, productTypeId = 999),
                            ),
                        images = listOf(image("i-1", appearanceId = 5)),
                    )

                val report = fixtures.of(dataSource, catalog(mixed, twoColourShirt())).run()

                assertEquals(listOf("a-mixed"), report.failed.map(SpodSyncLine::spodArticleId))
                assertNull(report.failed.single().articleId)
                assertEquals(
                    listOf(SpodSyncWarningCode.MIXED_PRODUCT_TYPES),
                    report.warnings.map(SpodSyncWarning::code),
                )
                assertEquals(listOf("a-1"), report.created.map(SpodSyncLine::spodArticleId))
                assertEquals(1, ArticleTestSchema.rowCount(dataSource, "article_spod_articles"))
            }
        }

    /**
     * A partner whose CDN answers nothing usable — SPOD's staging installation lists images that do
     * not exist — still gets its article written, with every variant inactive. The pictures are
     * asked for again by the next run, because nothing was stored that could mark them as known.
     */
    @Test
    fun `an article without fetchable pictures is written inactive and completed next time`() =
        runBlocking {
            migratedDataSource("spod-sync-image-failure-test").use { dataSource ->
                seed(dataSource)
                val page = catalog(twoColourShirt())
                var cdnAnswers = false
                val fixture =
                    fixtures.of(dataSource) { request ->
                        when {
                            request.url.encodedPath == "/articles" -> respondJson(page)
                            request.url.encodedPath.endsWith("/size-chart") ->
                                respondJson(SIZE_CHART_ANSWER)
                            cdnAnswers -> respondImage()
                            else -> respondError(HttpStatusCode.NotFound)
                        }
                    }

                val first = fixture.run()

                assertEquals(SpodSyncStatus.COMPLETED, first.status)
                assertEquals(listOf("a-1"), first.created.map(SpodSyncLine::spodArticleId))
                assertEquals(emptyList(), first.failed)
                assertEquals(
                    listOf(
                        SpodSyncWarningCode.IMAGE_DOWNLOAD_FAILED,
                        SpodSyncWarningCode.IMAGE_DOWNLOAD_FAILED,
                        SpodSyncWarningCode.COLOR_WITHOUT_IMAGE,
                        SpodSyncWarningCode.MOCKUP_UNAVAILABLE,
                        SpodSyncWarningCode.IMAGE_DOWNLOAD_FAILED,
                        SpodSyncWarningCode.COLOR_WITHOUT_IMAGE,
                        SpodSyncWarningCode.MOCKUP_UNAVAILABLE,
                        SpodSyncWarningCode.SIZE_CHART_UNAVAILABLE,
                    ),
                    first.warnings.map(SpodSyncWarning::code),
                )
                assertEquals(
                    classicShirtRows(blackActive = false, whiteActive = false),
                    variants(dataSource, 1),
                )
                assertEquals(emptyList(), listingImages(dataSource, 1))
                assertEquals(0, fixture.storage.storeCalls)

                cdnAnswers = true
                val second = fixture.run()

                assertEquals(listOf("a-1"), second.updated.map(SpodSyncLine::spodArticleId))
                assertEquals(
                    listOf(SpodSyncWarningCode.EXAMPLE_IMAGE_REPLACED),
                    second.warnings.map(SpodSyncWarning::code),
                )
                assertEquals(
                    classicShirtRows(blackActive = true, whiteActive = true),
                    variants(dataSource, 1),
                )
                assertEquals(3, listingImages(dataSource, 1).size)
                assertEquals(2, mockups(dataSource, 1).size)
                assertEquals(
                    6,
                    fixture.storage.storeCalls,
                    "three images, two mockups, and the size chart",
                )
            }
        }

    @Test
    fun `the size chart is fetched once per product type and again when its URL changes`() =
        runBlocking {
            migratedDataSource("spod-sync-size-chart-test").use { dataSource ->
                seed(dataSource)
                val second = twoColourShirt(id = "a-2", title = "Second Shirt")
                val fixture = fixtures.of(dataSource, catalog(twoColourShirt(), second))
                fixture.run()

                assertEquals(
                    1,
                    fixture.hits.count { path -> path.endsWith("/size-chart") },
                    "Two articles of one product type share one size chart",
                )
                val chart = storedArticle(dataSource, 1).getString("size_chart_image_filename")
                assertEquals(
                    chart,
                    storedArticle(dataSource, 2).getString("size_chart_image_filename"),
                )

                val movedFixture =
                    fixtures.of(
                        dataSource,
                        catalog(twoColourShirt(), second),
                        sizeChartUrl = MOVED_SIZE_CHART_URL,
                    )
                movedFixture.run()

                val moved = storedArticle(dataSource, 1).getString("size_chart_image_filename")
                assertTrue(moved != chart, "A new chart URL is a new stored file")
                assertEquals(
                    moved,
                    storedArticle(dataSource, 2).getString("size_chart_image_filename"),
                )
                assertEquals(
                    MOVED_SIZE_CHART_URL,
                    storedArticle(dataSource, 1).getString("spod_size_chart_url"),
                )
                assertEquals(listOf(chart), movedFixture.storage.deleted.toList())
            }
        }

    @Test
    fun `a second run of the same destination is refused while the first one is working`() =
        runBlocking {
            migratedDataSource("spod-sync-busy-test").use { dataSource ->
                seed(dataSource)
                val listing = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                val page = catalog(twoColourShirt())
                val fixture =
                    fixtures.of(dataSource) { request ->
                        if (request.url.encodedPath == "/articles") {
                            started.complete(Unit)
                            listing.await()
                            respondJson(page)
                        } else {
                            respondImage()
                        }
                    }

                val running = async { fixture.sync.sync(source()) }
                started.await()
                val refused = fixture.sync.sync(source())
                listing.complete(Unit)

                assertIs<SpodSyncResult.Reported>(running.await())
                assertEquals(SpodSyncResult.Busy, refused)
            }
        }
}
