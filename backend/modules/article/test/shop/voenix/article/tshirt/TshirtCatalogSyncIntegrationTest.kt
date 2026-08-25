package shop.voenix.article.tshirt

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleTestSchema
import shop.voenix.article.RecordingPublicImageStorage
import shop.voenix.article.SyncedTshirtVariant
import shop.voenix.article.SyncedTshirts
import shop.voenix.article.persistence.ArticleTshirtSyncRepository
import shop.voenix.spod.SpodAccess
import shop.voenix.spod.SpodCatalogArticle
import shop.voenix.spod.SpodCatalogImage
import shop.voenix.spod.SpodCatalogPage
import shop.voenix.spod.SpodCatalogVariant
import shop.voenix.spod.SpodClient
import shop.voenix.spod.SpodEnvironment
import shop.voenix.spod.SpodError
import shop.voenix.testing.PostgresIntegrationTest

/**
 * What a sync run does to the database, driven through the real [SpodClient] against a `MockEngine`
 * and a real PostgreSQL schema.
 *
 * The tests are written against the two things ADR 0003 promises an operator. The partner owns the
 * garment: a shirt appears, changes, loses a colour, disappears, and comes back without anyone
 * typing anything. The shop owns the rest: whatever an admin decided about a shirt survives every
 * run, and no run ever activates a shirt or deletes a variant.
 *
 * The engine records every request, which is how the third promise is checked at all — a second
 * identical run downloads nothing. A picture that did not change is not fetched again, and the file
 * the shop stored for it is still the file the row points at.
 */
internal class TshirtCatalogSyncIntegrationTest : PostgresIntegrationTest() {
    private val fixtures = SyncFixtures()

    @Test
    fun `a first run creates the shirts the backoffice lists`() = runBlocking {
        migratedDataSource("tshirt-sync-create-test").use { dataSource ->
            seed(dataSource)
            val fixture = fixtures.of(dataSource, catalog(twoColourShirt()))

            val report = fixture.run()

            assertEquals(TshirtSyncStatus.COMPLETED, report.status)
            assertEquals(1, report.fetchedArticles)
            assertEquals(listOf(1L), report.created.map(TshirtSyncLine::articleId))
            assertEquals(4, report.created.single().variantsCreated)
            assertEquals(emptyList(), report.warnings)

            val article = storedArticle(dataSource, 1)
            assertEquals("Classic Shirt", article.getString("name"))
            assertEquals("A shirt", article.getString("description_long"))
            assertEquals(1, article.getInt("position"))
            assertEquals(false, article.getBoolean("active"))
            assertEquals(SUPPLIER_ID, article.getLong("supplier_id"))
            assertNull(article.getObject("price_id"))
            assertNull(article.getObject("category_id"))
            assertEquals("1:1", article.getString("print_aspect_ratio"))
            assertEquals("30.00", article.getString("print_frame_left_pct"))
            assertEquals("a-1", article.getString("spod_article_id"))
            assertEquals("PRODUCTION", article.getString("spod_environment"))
            assertNull(article.getObject("spod_missing_since"))
            assertEquals(SIZE_CHART_URL, article.getString("spod_size_chart_url"))

            assertEquals(
                classicShirtRows(blackActive = true, whiteActive = true),
                variants(dataSource, 1),
            )
            // One picture per colour, shared by that colour's sizes, plus the size chart — each
            // picture fetched with the product id in place of the partner's `lookupId` placeholder.
            assertEquals(3, fixture.storage.storeCalls)
            assertEquals(
                listOf(
                    "/image-server/v1/products/$PRODUCT_ID/views/1,appearanceId=5,mediaType=png/a-1-i-2.png",
                    "/image-server/v1/products/$PRODUCT_ID/views/1,appearanceId=6,mediaType=png/a-1-i-3.png",
                ),
                fixture.hits.filter { path -> path.contains("/image-server/") },
            )
            assertNotNull(article.getString("size_chart_image_filename"))
            assertEquals(2, exampleImages(dataSource, 1).size)
        }
    }

    @Test
    fun `an identical second run writes nothing and downloads nothing`() = runBlocking {
        migratedDataSource("tshirt-sync-noop-test").use { dataSource ->
            seed(dataSource)
            val fixture = fixtures.of(dataSource, catalog(twoColourShirt()))
            val first = fixture.run()
            val syncedAt = storedArticle(dataSource, 1).getInstant("spod_synced_at")
            val storedVariants = variants(dataSource, 1)
            fixture.hits.clear()

            val second = fixture.run()

            assertEquals(listOf(1L), second.unchanged.map(TshirtSyncLine::articleId))
            assertEquals(emptyList(), second.created)
            assertEquals(emptyList(), second.updated)
            assertEquals(emptyList(), second.deactivated)
            assertEquals(emptyList(), second.warnings)
            assertEquals(
                listOf("/articles", "/productTypes/812/size-chart"),
                fixture.hits,
                "The second run must not download a picture it already has",
            )
            assertEquals(3, fixture.storage.storeCalls)
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
    fun `a run overwrites the garment and leaves everything the shop owns alone`() = runBlocking {
        migratedDataSource("tshirt-sync-ownership-test").use { dataSource ->
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
            assertEquals("16:9", article.getString("print_aspect_ratio"))
            assertEquals("11.00", article.getString("print_frame_left_pct"))
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
            migratedDataSource("tshirt-sync-colour-test").use { dataSource ->
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
        migratedDataSource("tshirt-sync-default-test").use { dataSource ->
            seed(dataSource)
            fixtures.of(dataSource, catalog(twoColourShirt())).run()
            ArticleTestSchema.execute(
                dataSource,
                """
                UPDATE voenix.article_tshirt_variants SET is_default = FALSE WHERE id = 1;
                UPDATE voenix.article_tshirt_variants SET is_default = TRUE WHERE id = 3;
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
                    TshirtSyncWarningCode.DEFAULT_VARIANT_REPLACED,
                    TshirtSyncWarningCode.EXAMPLE_IMAGE_REPLACED,
                ),
                report.warnings.map(TshirtSyncWarning::code),
            )
        }
    }

    @Test
    fun `an article the backoffice dropped is marked missing and comes back inactive`() =
        runBlocking {
            migratedDataSource("tshirt-sync-missing-test").use { dataSource ->
                seed(dataSource)
                fixtures.of(dataSource, catalog(twoColourShirt())).run()
                makeShopOwnedDecisions(dataSource)

                val swept = fixtures.of(dataSource, catalog()).run()

                assertEquals(listOf(1L), swept.deactivated.map(TshirtSyncLine::articleId))
                assertEquals(4, swept.deactivated.single().variantsDeactivated)
                assertEquals(false, storedArticle(dataSource, 1).getBoolean("active"))
                assertNotNull(storedArticle(dataSource, 1).getString("spod_missing_since"))
                assertTrue(variants(dataSource, 1).none(StoredVariant::active))

                val returned = fixtures.of(dataSource, catalog(twoColourShirt())).run()

                assertEquals(listOf(1L), returned.updated.map(TshirtSyncLine::articleId))
                assertEquals(
                    listOf(TshirtSyncWarningCode.ARTICLE_REAPPEARED),
                    returned.warnings.map(TshirtSyncWarning::code),
                )
                assertNull(storedArticle(dataSource, 1).getObject("spod_missing_since"))
                assertEquals(
                    false,
                    storedArticle(dataSource, 1).getBoolean("active"),
                    "Only an admin activates a shirt",
                )
            }
        }

    @Test
    fun `a shirt of the other installation is missing even when its id is listed again`() =
        runBlocking {
            migratedDataSource("tshirt-sync-environment-switch-test").use { dataSource ->
                seed(dataSource)
                SyncedTshirts.insert(
                    dataSource,
                    id = 7,
                    supplierId = SUPPLIER_ID,
                    environment = "STAGING",
                    spodArticleId = "a-1",
                    variants = listOf(SyncedTshirtVariant(id = 70)),
                )

                val report = fixtures.of(dataSource, catalog(twoColourShirt(id = "a-1"))).run()

                assertEquals(listOf(7L), report.deactivated.map(TshirtSyncLine::articleId))
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
        migratedDataSource("tshirt-sync-missing-once-test").use { dataSource ->
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
            migratedDataSource("tshirt-sync-degraded-test").use { dataSource ->
                seed(dataSource)
                val shirt =
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
                val fixture = fixtures.of(dataSource, catalog(shirt))

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
                        TshirtSyncWarningCode.COLOR_VALUE_UNREADABLE,
                        TshirtSyncWarningCode.COLOR_WITHOUT_IMAGE,
                    ),
                    report.warnings.map(TshirtSyncWarning::code),
                )
                assertEquals(
                    listOf("a-1", "a-1"),
                    report.warnings.map(TshirtSyncWarning::spodArticleId),
                )
            }
        }

    @Test
    fun `a listing that could not be read writes nothing and sweeps nothing`() = runBlocking {
        migratedDataSource("tshirt-sync-listing-failure-test").use { dataSource ->
            seed(dataSource)
            SyncedTshirts.insert(
                dataSource,
                id = 7,
                supplierId = SUPPLIER_ID,
                spodArticleId = "gone",
                variants = listOf(SyncedTshirtVariant(id = 70)),
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

            assertEquals(TshirtSyncStatus.FAILED, report.status)
            assertEquals(SpodError.PROVIDER_UNAVAILABLE, report.failure)
            assertEquals(0, report.fetchedArticles)
            assertEquals(emptyList(), report.deactivated)
            assertEquals(1, ArticleTestSchema.rowCount(dataSource, "article_tshirts"))
            assertNull(storedArticle(dataSource, 7).getObject("spod_missing_since"))
            assertEquals(
                Instant.parse(SyncedTshirts.SYNCED_AT),
                storedArticle(dataSource, 7).getInstant("spod_synced_at"),
            )
            assertTrue(variants(dataSource, 7).all(StoredVariant::active))
        }
    }

    /**
     * A page that states no total says nothing about the size of the catalog. Treating it as
     * complete would sweep every shirt the shop has on the strength of an answer that promised
     * nothing.
     */
    @Test
    fun `a listing page without a count writes nothing and sweeps nothing`() = runBlocking {
        migratedDataSource("tshirt-sync-no-count-test").use { dataSource ->
            seed(dataSource)
            SyncedTshirts.insert(
                dataSource,
                id = 7,
                supplierId = SUPPLIER_ID,
                spodArticleId = "gone",
                variants = listOf(SyncedTshirtVariant(id = 70)),
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

            assertEquals(TshirtSyncStatus.FAILED, report.status)
            assertEquals(SpodError.PROVIDER_ANSWER_UNREADABLE, report.failure)
            assertEquals(0, report.fetchedArticles)
            assertEquals(emptyList(), report.deactivated)
            assertEquals(1, ArticleTestSchema.rowCount(dataSource, "article_tshirts"))
            assertNull(storedArticle(dataSource, 7).getObject("spod_missing_since"))
            assertEquals(0, fixture.storage.storeCalls)
        }
    }

    /** Repeated articles would otherwise add up to the promised total and pass as the catalog. */
    @Test
    fun `a listing that repeats an article across pages is not a complete catalog`() = runBlocking {
        migratedDataSource("tshirt-sync-repeated-id-test").use { dataSource ->
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

            assertEquals(TshirtSyncStatus.FAILED, report.status)
            assertEquals(SpodError.PROVIDER_ANSWER_UNREADABLE, report.failure)
            assertEquals(0, ArticleTestSchema.rowCount(dataSource, "article_tshirts"))
        }
    }

    /**
     * A colour whose picture cannot be fetched is degraded, not fatal: the article is written, the
     * other colour keeps the picture the run stored for it, and only the unfetched colour's
     * variants go inactive without a picture.
     */
    @Test
    fun `a colour whose picture cannot be fetched goes inactive, the other keeps its picture`() =
        runBlocking {
            migratedDataSource("tshirt-sync-orphan-test").use { dataSource ->
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

                assertEquals(listOf("a-1"), report.created.map(TshirtSyncLine::spodArticleId))
                assertEquals(emptyList(), report.failed)
                assertEquals(
                    listOf(TshirtSyncWarningCode.IMAGE_DOWNLOAD_FAILED),
                    report.warnings.map(TshirtSyncWarning::code),
                )
                assertEquals(
                    classicShirtRows(blackActive = true, whiteActive = false),
                    variants(dataSource, 1),
                )
                assertEquals(2, fixture.storage.storeCalls, "the first colour and the size chart")
                assertEquals(emptyList(), fixture.storage.deleted)
                assertEquals(listOf(filename(0)), exampleImages(dataSource, 1))
            }
        }

    /** An id longer than its column is an identity this shop could never match a run against. */
    @Test
    fun `an article whose id does not fit the column is skipped and nothing is written`() =
        runBlocking {
            migratedDataSource("tshirt-sync-long-id-test").use { dataSource ->
                seed(dataSource)
                val overlong = "a".repeat(65)

                val fixture = fixtures.of(dataSource, catalog(twoColourShirt(id = overlong)))
                val report = fixture.run()

                assertEquals(TshirtSyncStatus.COMPLETED, report.status)
                assertEquals(listOf(overlong), report.failed.map(TshirtSyncLine::spodArticleId))
                assertEquals(
                    listOf(TshirtSyncWarningCode.SPOD_ID_UNUSABLE),
                    report.warnings.map(TshirtSyncWarning::code),
                )
                assertEquals(0, ArticleTestSchema.rowCount(dataSource, "article_tshirts"))
                assertEquals(0, fixture.storage.storeCalls, "nothing is downloaded for it either")
            }
        }

    /**
     * The race the reading half of the sync cannot avoid: `findForSync` reads which pictures the
     * shirt already has, the run reuses those file names instead of downloading again, and an admin
     * deletes the shirt — and with it those files — before the write.
     *
     * The second run keeps the mockups and only moves the size chart, so the colours are reused and
     * the one download of the run is the chart, which is what the delete happens during. Writing
     * the row back now would point it at files that are gone, so the article is prepared once more
     * from nothing: every picture of the re-created row is one *this* run stored.
     */
    @Test
    fun `an article deleted while a picture downloads is prepared again from nothing`() =
        runBlocking {
            migratedDataSource("tshirt-sync-delete-race-test").use { dataSource ->
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
                val pictures = exampleImages(dataSource, articleId)
                assertEquals(2, pictures.size)
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
            migratedDataSource("tshirt-sync-mixed-test").use { dataSource ->
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

                assertEquals(listOf("a-mixed"), report.failed.map(TshirtSyncLine::spodArticleId))
                assertNull(report.failed.single().articleId)
                assertEquals(
                    listOf(TshirtSyncWarningCode.MIXED_PRODUCT_TYPES),
                    report.warnings.map(TshirtSyncWarning::code),
                )
                assertEquals(listOf("a-1"), report.created.map(TshirtSyncLine::spodArticleId))
                assertEquals(1, ArticleTestSchema.rowCount(dataSource, "article_tshirts"))
            }
        }

    /**
     * A partner whose CDN answers nothing usable — SPOD's staging installation lists mockups that
     * do not exist — still gets its article written, with every variant inactive. The pictures are
     * asked for again by the next run, because nothing was stored that could mark them as known.
     */
    @Test
    fun `an article without fetchable pictures is written inactive and completed next time`() =
        runBlocking {
            migratedDataSource("tshirt-sync-image-failure-test").use { dataSource ->
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

                assertEquals(TshirtSyncStatus.COMPLETED, first.status)
                assertEquals(listOf("a-1"), first.created.map(TshirtSyncLine::spodArticleId))
                assertEquals(emptyList(), first.failed)
                assertEquals(
                    listOf(
                        TshirtSyncWarningCode.IMAGE_DOWNLOAD_FAILED,
                        TshirtSyncWarningCode.IMAGE_DOWNLOAD_FAILED,
                        TshirtSyncWarningCode.SIZE_CHART_UNAVAILABLE,
                    ),
                    first.warnings.map(TshirtSyncWarning::code),
                )
                assertEquals(
                    classicShirtRows(blackActive = false, whiteActive = false),
                    variants(dataSource, 1),
                )
                assertEquals(emptyList(), exampleImages(dataSource, 1))
                assertEquals(0, fixture.storage.storeCalls)

                cdnAnswers = true
                val second = fixture.run()

                assertEquals(listOf("a-1"), second.updated.map(TshirtSyncLine::spodArticleId))
                assertEquals(
                    listOf(TshirtSyncWarningCode.EXAMPLE_IMAGE_REPLACED),
                    second.warnings.map(TshirtSyncWarning::code),
                )
                assertEquals(
                    classicShirtRows(blackActive = true, whiteActive = true),
                    variants(dataSource, 1),
                )
                assertEquals(2, exampleImages(dataSource, 1).size)
                assertEquals(3, fixture.storage.storeCalls, "two pictures and the size chart")
            }
        }

    @Test
    fun `the size chart is fetched once per product type and again when its URL changes`() =
        runBlocking {
            migratedDataSource("tshirt-sync-size-chart-test").use { dataSource ->
                seed(dataSource)
                val second = twoColourShirt(id = "a-2", title = "Second Shirt")
                val fixture = fixtures.of(dataSource, catalog(twoColourShirt(), second))
                fixture.run()

                assertEquals(
                    1,
                    fixture.hits.count { path -> path.endsWith("/size-chart") },
                    "Two shirts of one product type share one size chart",
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
            migratedDataSource("tshirt-sync-busy-test").use { dataSource ->
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

                assertIs<TshirtSyncResult.Reported>(running.await())
                assertEquals(TshirtSyncResult.Busy, refused)
            }
        }
}

/**
 * The fixture of one run: the service under test, the image storage it stores into, and every path
 * the client asked the partner for.
 */
private class SyncFixture(
    val sync: TshirtCatalogSync,
    val storage: RecordingPublicImageStorage,
    val hits: MutableList<String>,
) {
    suspend fun run(): TshirtSyncReport =
        assertIs<TshirtSyncResult.Reported>(sync.sync(source())).report
}

/**
 * Builds the fixtures of one test. Every fixture mints from its own stretch of the file-name list,
 * so a name says which run stored it; the counter lives per test instance, so the first fixture of
 * every test starts at [filename] `0`.
 */
private class SyncFixtures {
    private var mintedFilenames = 0

    fun of(
        dataSource: DataSource,
        page: String,
        sizeChartUrl: String = SIZE_CHART_URL,
    ): SyncFixture =
        of(dataSource) { request ->
            when {
                request.url.encodedPath == "/articles" -> respondJson(page)
                request.url.encodedPath.endsWith("/size-chart") ->
                    respondJson("""{"sizeImageUrl":"$sizeChartUrl"}""")
                else -> respondImage()
            }
        }

    fun of(
        dataSource: DataSource,
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SyncFixture {
        val hits = mutableListOf<String>()
        val storage =
            RecordingPublicImageStorage(
                List(FILENAMES_PER_FIXTURE) { index -> filename(mintedFilenames + index) }
            )
        mintedFilenames += FILENAMES_PER_FIXTURE
        val engine = MockEngine { request ->
            hits += request.url.encodedPath
            answer(request)
        }
        val service =
            TshirtCatalogSyncService(
                ArticleTshirtSyncRepository(Database.connect(datasource = dataSource)),
                SpodClient(engine = engine, nowMillis = { 0 }, pause = {}),
                storage,
            )
        return SyncFixture(service, storage, hits)
    }
}

private const val SUPPLIER_ID = 1L
private const val DESTINATION_ID = 1L
private const val PRODUCT_TYPE_ID = 812L

/** The Spreadshirt product every mockup of these tests renders; its URLs name it as `lookupId`. */
private const val PRODUCT_ID = 598279462L
private const val SIZE_CHART_URL = "https://cdn.example.test/chart-1.png"
private const val SIZE_CHART_ANSWER = """{"sizeImageUrl":"$SIZE_CHART_URL"}"""

/** The chart of the same product type after the partner moved it to another file. */
private const val MOVED_SIZE_CHART_URL = "https://cdn.example.test/chart-2.png"

/** More names than any single run of these tests stores, so no fixture runs out of them. */
private const val FILENAMES_PER_FIXTURE = 8

private fun filename(index: Int): String = "%08d-0000-4000-8000-000000000000.webp".format(index)

private fun source(): SpodCatalogSource =
    SpodCatalogSource(
        supplierId = SUPPLIER_ID,
        access =
            SpodAccess(
                destinationId = DESTINATION_ID,
                environment = SpodEnvironment.PRODUCTION,
                accessToken = "sync-token",
                timeoutSeconds = 30,
            ),
    )

private fun seed(dataSource: DataSource) {
    ArticleTestSchema.reset(dataSource)
    ArticleTestSchema.seedSuppliers(dataSource, "Shirt supplier")
    ArticleTestSchema.seedCategories(dataSource, "Shirts")
    SyncedTshirts.seedSpodDestination(dataSource, id = DESTINATION_ID, supplierId = SUPPLIER_ID)
}

/**
 * Everything an admin decides about a shirt, decided: it is on sale, in a category, with a price, a
 * frame of its own, a wide print, and a default variant that is not the first one.
 */
private fun makeShopOwnedDecisions(dataSource: DataSource) {
    ArticleTestSchema.seedVat(dataSource)
    ArticleTestSchema.execute(
        dataSource,
        """
        INSERT INTO voenix.prices (
            id, purchase_vat_id, purchase_calculation_mode, purchase_active_row,
            purchase_price_input_cents, purchase_cost_input_cents, purchase_cost_percent,
            sales_vat_id, sales_calculation_mode, sales_active_row,
            sales_margin_input_cents, sales_margin_percent, sales_total_input_cents
        ) VALUES (1, 1, 'NET', 'COST', 500, 0, 0, 1, 'NET', 'MARGIN', 500, 0, 1000);
        UPDATE voenix.article_tshirts SET
            active = TRUE, category_id = 1, price_id = 1, print_aspect_ratio = '16:9',
            print_frame_left_pct = 11.00
        WHERE id = 1;
        UPDATE voenix.article_tshirt_variants SET is_default = FALSE WHERE id = 1;
        UPDATE voenix.article_tshirt_variants SET is_default = TRUE WHERE id = 3;
        """
            .trimIndent(),
    )
}

private fun catalog(vararg articles: SpodCatalogArticle): String =
    catalog(articles.toList(), count = articles.size)

/** One listing page, with a [count] a test may state differently from what the page carries. */
private fun catalog(
    articles: List<SpodCatalogArticle>,
    count: Int,
): String =
    Json.encodeToString(
        SpodCatalogPage.serializer(),
        SpodCatalogPage(items = articles, count = count),
    )

private fun article(
    id: String = "a-1",
    title: String = "Classic Shirt",
    description: String = "A shirt",
    variants: List<SpodCatalogVariant>,
    images: List<SpodCatalogImage>,
): SpodCatalogArticle =
    SpodCatalogArticle(
        id = id,
        title = title,
        description = description,
        variants = variants,
        images = images,
    )

/** Two colours in two sizes, each colour with a front and a back mockup. */
private fun twoColourShirt(
    id: String = "a-1",
    title: String = "Classic Shirt",
    description: String = "A shirt",
): SpodCatalogArticle =
    article(
        id = id,
        title = title,
        description = description,
        variants =
            listOf(
                variant("$id-v-1", appearanceId = 5, sizeId = 91, sizeName = "M"),
                variant("$id-v-2", appearanceId = 5, sizeId = 92, sizeName = "L"),
                variant(
                    "$id-v-3",
                    appearanceId = 6,
                    sizeId = 91,
                    sizeName = "M",
                    appearanceName = "White",
                    colorValue = "#FFFFFF",
                ),
                variant(
                    "$id-v-4",
                    appearanceId = 6,
                    sizeId = 92,
                    sizeName = "L",
                    appearanceName = "White",
                    colorValue = "#FFFFFF",
                ),
            ),
        images =
            listOf(
                image("$id-i-1", appearanceId = 5, perspective = "back"),
                image("$id-i-2", appearanceId = 5, perspective = "FRONT"),
                image("$id-i-3", appearanceId = 6, perspective = "front_top"),
            ),
    )

/** Deletes one synced shirt the way the admin API does: its variants, its rows, its identities. */
private fun deleteArticle(
    dataSource: DataSource,
    articleId: Long,
) {
    ArticleTestSchema.execute(
        dataSource,
        """
        DELETE FROM voenix.article_tshirt_variants WHERE article_id = $articleId;
        DELETE FROM voenix.article_variant_identities WHERE article_id = $articleId;
        DELETE FROM voenix.article_tshirts WHERE id = $articleId;
        DELETE FROM voenix.article_identities WHERE id = $articleId;
        """
            .trimIndent(),
    )
}

/** The same shirt after the merchant switched the white colour off. */
private fun blackOnlyShirt(): SpodCatalogArticle =
    article(
        variants =
            listOf(
                variant("a-1-v-1", appearanceId = 5, sizeId = 91, sizeName = "M"),
                variant("a-1-v-2", appearanceId = 5, sizeId = 92, sizeName = "L"),
            ),
        images = listOf(image("a-1-i-2", appearanceId = 5, perspective = "FRONT")),
    )

private fun variant(
    id: String,
    appearanceId: Long,
    sizeId: Long = 91,
    sizeName: String = "M",
    appearanceName: String = "Black",
    colorValue: String? = "#101010",
    productTypeId: Long = PRODUCT_TYPE_ID,
): SpodCatalogVariant =
    SpodCatalogVariant(
        id = id,
        productTypeId = productTypeId,
        appearanceId = appearanceId,
        appearanceName = appearanceName,
        appearanceColorValue = colorValue,
        sizeId = sizeId,
        sizeName = sizeName,
        sku = "SKU-$id",
    )

private fun image(
    id: String,
    appearanceId: Long,
    perspective: String? = "front",
): SpodCatalogImage =
    SpodCatalogImage(
        id = id,
        productId = PRODUCT_ID,
        appearanceId = appearanceId,
        perspective = perspective,
        imageUrl =
            "https://cdn.example.test/image-server/v1/products/lookupId/views/1," +
                "appearanceId=$appearanceId,mediaType=png/$id.png",
    )

private fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
    respond(content = body, headers = headersOf(HttpHeaders.ContentType, "application/json"))

private fun MockRequestHandleScope.respondImage(): HttpResponseData =
    respond(
        content = byteArrayOf(1, 2, 3, 4),
        headers = headersOf(HttpHeaders.ContentType, "image/png"),
    )

/** One stored variant, in the shape every assertion about the variant matrix is written in. */
private data class StoredVariant(
    val colorName: String,
    val colorHex: String,
    val sizeLabel: String,
    val active: Boolean,
    val isDefault: Boolean,
)

private fun variantRow(
    colorName: String,
    colorHex: String,
    sizeLabel: String,
    active: Boolean,
    isDefault: Boolean,
): StoredVariant = StoredVariant(colorName, colorHex, sizeLabel, active, isDefault)

/**
 * The four variants of [twoColourShirt] as the shop stores them: Black M (the default) and L, then
 * White M and L, each colour with its own `active`.
 */
private fun classicShirtRows(
    blackActive: Boolean,
    whiteActive: Boolean,
): List<StoredVariant> =
    listOf(
        variantRow("Black", "#101010", "M", active = blackActive, isDefault = true),
        variantRow("Black", "#101010", "L", active = blackActive, isDefault = false),
        variantRow("White", "#ffffff", "M", active = whiteActive, isDefault = false),
        variantRow("White", "#ffffff", "L", active = whiteActive, isDefault = false),
    )

private fun variants(
    dataSource: DataSource,
    articleId: Long,
): List<StoredVariant> =
    query(
        dataSource,
        """
        SELECT color_name, color_hex, size_label, active, is_default
        FROM voenix.article_tshirt_variants
        WHERE article_id = $articleId
        ORDER BY id
        """
            .trimIndent(),
    ) { rows ->
        StoredVariant(
            colorName = rows.getString("color_name"),
            colorHex = rows.getString("color_hex"),
            sizeLabel = rows.getString("size_label"),
            active = rows.getBoolean("active"),
            isDefault = rows.getBoolean("is_default"),
        )
    }

/** One stored shirt as a map of column to value, so an assertion names the column it means. */
private fun storedArticle(
    dataSource: DataSource,
    articleId: Long,
): StoredArticle =
    StoredArticle(
        query(dataSource, "SELECT * FROM voenix.article_tshirts WHERE id = $articleId") { rows ->
                (1..rows.metaData.columnCount).associate { index ->
                    rows.metaData.getColumnLabel(index) to rows.getObject(index)
                }
            }
            .single()
    )

private class StoredArticle(private val columns: Map<String, Any?>) {
    fun getObject(column: String): Any? = columns[column]

    fun getString(column: String): String? = columns[column]?.toString()

    fun getInt(column: String): Int = (columns[column] as Number).toInt()

    fun getLong(column: String): Long = (columns[column] as Number).toLong()

    fun getBoolean(column: String): Boolean = columns[column] as Boolean

    fun getInstant(column: String): Instant = (columns[column] as Timestamp).toInstant()
}

/** The distinct pictures the variants of one shirt point at. */
private fun exampleImages(
    dataSource: DataSource,
    articleId: Long,
): List<String> =
    query(
        dataSource,
        """
        SELECT DISTINCT example_image_filename
        FROM voenix.article_tshirt_variants
        WHERE article_id = $articleId AND example_image_filename IS NOT NULL
        """
            .trimIndent(),
    ) { rows ->
        rows.getString("example_image_filename")
    }

private fun <T> query(
    dataSource: DataSource,
    sql: String,
    row: (ResultSet) -> T,
): List<T> =
    dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(row(rows))
                    }
                }
            }
        }
    }
