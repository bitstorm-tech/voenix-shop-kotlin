package shop.voenix.article

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.pricing.installPricingModule
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.installVatModule

/**
 * The exported [ArticleCatalog] against the real module and a real PostgreSQL database.
 *
 * Every mug the test resolves is written through the admin routes, so the answers describe articles
 * that really exist the way an admin can create them. That matters for one case in particular: an
 * *active* article without a price is refused by the database, so "no price" is only reachable
 * while the article is inactive. The test therefore isolates the three reasons for `purchasable =
 * false` in three different mugs — an inactive article that has everything else, an active article
 * with an inactive variant, and the draft that owns no price at all — instead of pretending a
 * fourth combination exists.
 */
internal class ArticleCatalogIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `find answers a batch with purchasability, price, supplier, and layout data`() {
        migratedDataSource("article-catalog-batch-test").use { dataSource ->
            seedCatalog(dataSource)

            catalogApplication(dataSource, "article-catalog-batch-integration-secret") { fixture ->
                fixture.createCatalog()

                val found =
                    fixture.catalog.find(
                        setOf(
                            PURCHASABLE,
                            INACTIVE_ARTICLE,
                            INACTIVE_VARIANT,
                            WITHOUT_PRICE,
                            UNKNOWN_VARIANT,
                            MISMATCHED_PAIR,
                        )
                    )

                assertEquals(
                    setOf(PURCHASABLE, INACTIVE_ARTICLE, INACTIVE_VARIANT, WITHOUT_PRICE),
                    found.keys,
                    "Unknown references and mismatched pairs must be absent, never null-valued",
                )

                // The complete answer: every ProductionItem field, the supplier data, and the
                // gross sales total in cents.
                assertEquals(
                    CatalogVariant(
                        articleType = ArticleType.MUG,
                        articleName = "Classic mug",
                        variantName = "White",
                        purchasable = true,
                        grossSalesPriceCents = 1490,
                        supplierId = 1,
                        supplierArticleNumber = "4711",
                        printTemplateWidthMm = 200,
                        printTemplateHeightMm = 90,
                        documentFormatWidthMm = 210,
                        documentFormatHeightMm = 297,
                        documentFormatMarginBottomMm = 15,
                        // Two different codes, so a swapped mapping cannot pass this test.
                        outsideColorCode = "#fffffd",
                        insideColorCode = "#fffffe",
                        spodProduct = null,
                    ),
                    found[PURCHASABLE],
                )

                // An inactive article keeps its price and its layout data — it is only not
                // buyable. Production data of an article already ordered stays readable.
                assertEquals(
                    CatalogVariant(
                        articleType = ArticleType.MUG,
                        articleName = "Retired mug",
                        variantName = "Solo",
                        purchasable = false,
                        grossSalesPriceCents = 2990,
                        supplierId = 2,
                        supplierArticleNumber = "9000",
                        printTemplateWidthMm = 200,
                        printTemplateHeightMm = 90,
                        documentFormatWidthMm = null,
                        documentFormatHeightMm = null,
                        documentFormatMarginBottomMm = null,
                        outsideColorCode = "#000",
                        insideColorCode = "#000",
                        spodProduct = null,
                    ),
                    found[INACTIVE_ARTICLE],
                )

                // The article is active and priced; this one variant is not sold any more.
                assertEquals(
                    CatalogVariant(
                        articleType = ArticleType.MUG,
                        articleName = "Two-tone mug",
                        variantName = "Retired",
                        purchasable = false,
                        grossSalesPriceCents = 990,
                        supplierId = null,
                        supplierArticleNumber = null,
                        printTemplateWidthMm = 200,
                        printTemplateHeightMm = 90,
                        documentFormatWidthMm = null,
                        documentFormatHeightMm = null,
                        documentFormatMarginBottomMm = null,
                        outsideColorCode = "#0f0",
                        insideColorCode = "#0f0",
                        spodProduct = null,
                    ),
                    found[INACTIVE_VARIANT],
                )

                // A draft: no price, and therefore no amount at all instead of a `0`. Its colors
                // are still answered — they sit on the variant row, so the article without its
                // details loses the layout measurements and nothing else a stored reference needs
                // to be rendered.
                assertEquals(
                    CatalogVariant(
                        articleType = ArticleType.MUG,
                        articleName = "Draft mug",
                        variantName = "Draft",
                        purchasable = false,
                        grossSalesPriceCents = null,
                        supplierId = null,
                        supplierArticleNumber = null,
                        printTemplateWidthMm = null,
                        printTemplateHeightMm = null,
                        documentFormatWidthMm = null,
                        documentFormatHeightMm = null,
                        documentFormatMarginBottomMm = null,
                        outsideColorCode = "#eee",
                        insideColorCode = "#fff",
                        spodProduct = null,
                    ),
                    found[WITHOUT_PRICE],
                )

                // The active variant of the same article is the one that can be bought.
                val sibling =
                    fixture.catalog.find(
                        setOf(ArticleVariantReference(articleId = 3, variantId = 5))
                    )
                assertEquals(
                    true,
                    sibling.values.single().purchasable,
                    "The active variant of an active, priced article is purchasable",
                )
            }
        }
    }

    /**
     * The rule every reader capability follows: whatever the batch holds, the prices behind it are
     * resolved in exactly one lookup, and a batch without a single price asks the pricing module
     * nothing at all.
     */
    @Test
    fun `find resolves every price of a batch in one lookup`() {
        migratedDataSource("article-catalog-price-test").use { dataSource ->
            seedCatalog(dataSource)

            catalogApplication(dataSource, "article-catalog-price-integration-secret") { fixture ->
                fixture.createCatalog()
                fixture.prices.requestedIds.clear()

                fixture.catalog.find(
                    setOf(PURCHASABLE, INACTIVE_ARTICLE, INACTIVE_VARIANT, WITHOUT_PRICE)
                )

                // One call for four references, carrying the distinct price ids only. The draft
                // owns no price and contributes nothing to it.
                assertEquals(
                    listOf(ArticleTestSchema.storedPriceIds(dataSource).toSet()),
                    fixture.prices.requestedIds.toList(),
                )
                assertEquals(3, ArticleTestSchema.storedPriceIds(dataSource).size)

                // References that resolve no priced article ask the pricing module nothing.
                fixture.prices.requestedIds.clear()
                assertEquals(
                    setOf(WITHOUT_PRICE),
                    fixture.catalog.find(setOf(WITHOUT_PRICE, UNKNOWN_VARIANT)).keys,
                )
                assertEquals(emptyList(), fixture.prices.requestedIds.toList())
            }
        }
    }

    /**
     * The second lookup of the capability, with the same two rules as [ArticleCatalog.find]: an
     * article id nobody minted is absent from the answer, and an empty set is answered without a
     * single statement.
     */
    @Test
    fun `printFormats answers the ratio of every known article and nothing for the rest`() {
        migratedDataSource("article-catalog-print-format-test").use { dataSource ->
            seedCatalog(dataSource)
            val counting = CountingDataSource(dataSource)

            catalogApplication(counting, "article-catalog-format-integration-secret") { fixture ->
                fixture.createCatalog()
                // The square one is the only mug that asks for a ratio of its own.
                fixture.createMug(SQUARE_MUG)
                seedShirt(dataSource)
                counting.statements.clear()
                fixture.prices.requestedIds.clear()

                assertEquals(
                    mapOf(
                        1L to PrintAspectRatio.WIDE_16_9,
                        4L to PrintAspectRatio.WIDE_16_9,
                        5L to PrintAspectRatio.SQUARE,
                        SHIRT_ARTICLE_ID to PrintAspectRatio.SQUARE,
                    ),
                    fixture.catalog.printFormats(setOf(1L, 4L, 5L, SHIRT_ARTICLE_ID, 404L)),
                    "A mug that says nothing about its ratio is printed 16:9, a shirt 1:1",
                )
                // One query per article type, merged into the one map.
                assertEquals(2, counting.statements.size, "Statements: ${counting.statements}")

                counting.statements.clear()
                assertEquals(emptyMap(), fixture.catalog.printFormats(emptySet()))
                assertEquals(emptyList(), counting.statements.toList())

                // The ratio never asks the pricing module anything: it is stored on the article.
                assertEquals(emptyList(), fixture.prices.requestedIds.toList())
            }
        }
    }

    /**
     * The batch that mixes the two article types. It is the whole point of the per-type queries:
     * one read per type inside one transaction, one price lookup for everything they found
     * together, and answers that differ exactly where the types differ — a shirt carries its
     * print-on-demand product and no colours or PDF measurements, a mug the other way round.
     *
     * The shirt is written with SQL rather than through an admin route, because the shirt slice has
     * no admin route yet. The rows are the ones the migration allows, ids far away from the
     * generated ones so the identity sequences keep answering for the mugs.
     */
    @Test
    fun `find answers a mixed batch of a mug and a shirt with one query per type`() {
        migratedDataSource("article-catalog-mixed-test").use { dataSource ->
            seedCatalog(dataSource)
            val counting = CountingDataSource(dataSource)

            catalogApplication(counting, "article-catalog-mixed-integration-secret") { fixture ->
                fixture.createCatalog()
                seedShirt(dataSource)
                counting.statements.clear()
                fixture.prices.requestedIds.clear()

                val found =
                    fixture.catalog.find(
                        setOf(PURCHASABLE, SHIRT, RETIRED_SHIRT, UNKNOWN_SHIRT_VARIANT)
                    )

                assertEquals(
                    setOf(PURCHASABLE, SHIRT, RETIRED_SHIRT),
                    found.keys,
                    "An unknown shirt variant is absent from the mixed answer as well",
                )
                assertEquals(
                    CatalogVariant(
                        articleType = ArticleType.TSHIRT,
                        articleName = "Classic shirt",
                        variantName = "Black / M",
                        purchasable = true,
                        grossSalesPriceCents = 2490,
                        supplierId = 1,
                        // A shirt is ordered from the printer, so it has neither a supplier
                        // article number nor a PDF layout — and its colour is part of its name.
                        supplierArticleNumber = null,
                        printTemplateWidthMm = null,
                        printTemplateHeightMm = null,
                        documentFormatWidthMm = null,
                        documentFormatHeightMm = null,
                        documentFormatMarginBottomMm = null,
                        outsideColorCode = null,
                        insideColorCode = null,
                        spodProduct =
                            SpodProductRef(productTypeId = 300, appearanceId = 4, sizeId = 12),
                    ),
                    found[SHIRT],
                )
                assertEquals(
                    false,
                    found[RETIRED_SHIRT]?.purchasable,
                    "The switched-off variant of an active, priced shirt is not buyable",
                )
                assertEquals(ArticleType.MUG, found[PURCHASABLE]?.articleType)
                assertNull(found[PURCHASABLE]?.spodProduct, "A mug has no print-on-demand product")

                // One query per article type, and one price lookup carrying the prices of both.
                val articleReads =
                    counting.normalizedStatements().filter { sql -> sql.contains("article_") }
                assertEquals(2, articleReads.size, "Article reads: $articleReads")
                assertEquals(
                    listOf(setOf(1L, SHIRT_PRICE_ID)),
                    fixture.prices.requestedIds.toList(),
                )
            }
        }
    }

    @Test
    fun `find answers an empty reference set without touching the database`() {
        migratedDataSource("article-catalog-empty-test").use { dataSource ->
            seedCatalog(dataSource)
            val counting = CountingDataSource(dataSource)

            catalogApplication(counting, "article-catalog-empty-integration-secret") { fixture ->
                fixture.createCatalog()
                counting.statements.clear()
                fixture.prices.requestedIds.clear()

                assertEquals(emptyMap(), fixture.catalog.find(emptySet()))

                assertEquals(emptyList(), counting.statements.toList())
                assertEquals(emptyList(), fixture.prices.requestedIds.toList())

                // Unknown references cost one article read per type and nothing else.
                assertEquals(emptyMap(), fixture.catalog.find(setOf(UNKNOWN_VARIANT)))
                assertEquals(2, counting.statements.size, "Statements: ${counting.statements}")
                assertTrue(fixture.prices.requestedIds.isEmpty())
                assertNull(fixture.catalog.find(setOf(MISMATCHED_PAIR))[MISMATCHED_PAIR])
            }
        }
    }

    /**
     * The catalog answers what a customer pays. A discount lives on the price, and the exported
     * variant carries the reduced gross total without knowing that there is a discount at all —
     * which is why no consumer of this capability had to change for the feature.
     */
    @Test
    fun `find answers the effective gross price of a discounted article`() {
        migratedDataSource("article-catalog-discount-test").use { dataSource ->
            seedCatalog(dataSource)

            catalogApplication(dataSource, "article-catalog-discount-integration-secret") { fixture
                ->
                fixture.createMug(DISCOUNTED_MUG)

                val found = fixture.catalog.find(setOf(DISCOUNTED))
                assertEquals(1592, found.getValue(DISCOUNTED).grossSalesPriceCents)
            }
        }
    }

    /**
     * One active, priced shirt with two variants: the default one is sold, the second is retired.
     * Both carry their own SPOD product, because the migration allows a printable product only once
     * per article.
     */
    private fun seedShirt(dataSource: DataSource) {
        ArticleTestSchema.execute(
            dataSource,
            """
            INSERT INTO voenix.prices (
                id, purchase_vat_id, purchase_calculation_mode, purchase_active_row,
                purchase_price_input_cents, purchase_cost_input_cents, purchase_cost_percent,
                sales_vat_id, sales_calculation_mode, sales_active_row,
                sales_margin_input_cents, sales_margin_percent, sales_total_input_cents
            ) VALUES ($SHIRT_PRICE_ID, 1, 'NET', 'COST', 900, 0, 0, 1, 'GROSS', 'TOTAL', 0, 0, 2490);
            """
                .trimIndent(),
        )
        SyncedTshirts.insert(
            dataSource,
            id = SHIRT_ARTICLE_ID,
            name = "Classic shirt",
            active = true,
            categoryId = 1,
            priceId = SHIRT_PRICE_ID,
            variants =
                listOf(
                    SyncedTshirtVariant(
                        id = SHIRT_VARIANT_ID,
                        spodProductTypeId = 300,
                        spodAppearanceId = 4,
                        spodSizeId = 12,
                        isDefault = true,
                    ),
                    SyncedTshirtVariant(
                        id = RETIRED_SHIRT_VARIANT_ID,
                        sizeLabel = "L",
                        spodProductTypeId = 300,
                        spodAppearanceId = 4,
                        spodSizeId = 13,
                        active = false,
                    ),
                ),
        )
    }

    private fun seedCatalog(dataSource: DataSource) {
        ArticleTestSchema.reset(dataSource)
        ArticleTestSchema.seedVat(dataSource)
        ArticleTestSchema.seedCategories(dataSource, "Mugs")
        ArticleTestSchema.seedSuppliers(dataSource, "Porcelain Ltd", "Glass Co")
        SyncedTshirts.seedSpodDestination(dataSource)
    }

    /** Runs [block] against the real module installed on [dataSource], signed in as an admin. */
    private fun catalogApplication(
        dataSource: DataSource,
        sessionSecret: String,
        block: suspend (CatalogFixture) -> Unit,
    ) = testApplication {
        lateinit var prices: CountingPriceCatalog
        lateinit var catalog: ArticleCatalog
        application {
            installHttpRuntime()
            install(RequestValidation) { validateArticleRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            val database = Database.connect(datasource = dataSource)
            prices =
                CountingPriceCatalog(installPricingModule(database, installVatModule(database)))
            catalog =
                installArticleModule(
                        database,
                        RecordingPublicImageStorage(),
                        prices,
                        RecordingSupplierReader(),
                        unreachableSpodClient(),
                    )
                    .catalog
            routing {
                post("/test/sign-in") {
                    call.sessions.set(UserSession(userId = "11", role = "ADMIN"))
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        val admin = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, admin.post("/test/sign-in").status)
        val token =
            Json.parseToJsonElement(admin.get("/api/antiforgery/token").bodyAsText())
                .jsonObject
                .getValue("requestToken")
                .jsonPrimitive
                .content
        block(CatalogFixture(admin, token, prices, catalog))
    }

    /** The signed-in admin client, the counted price capability, and the capability under test. */
    private class CatalogFixture(
        val admin: HttpClient,
        val token: String,
        val prices: CountingPriceCatalog,
        val catalog: ArticleCatalog,
    ) {
        /**
         * The four mugs every test resolves, in the order that fixes their ids: articles 1 to 4 and
         * variants 1 to 6, both minted by the identity registries in write order.
         */
        suspend fun createCatalog() {
            createMug(PURCHASABLE_MUG)
            createMug(INACTIVE_MUG)
            createMug(TWO_TONE_MUG)
            createMug(DRAFT_MUG)
        }

        suspend fun createMug(body: String) {
            val created =
                admin.post(BASE_PATH) {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        }
    }

    private companion object {
        const val BASE_PATH = "/api/admin/articles/mugs"

        /** Article 1, variant 2: active article, active variant, price — the only buyable one. */
        val PURCHASABLE = ArticleVariantReference(articleId = 1, variantId = 2)

        /** Article 2, variant 3: everything complete, but the article is switched off. */
        val INACTIVE_ARTICLE = ArticleVariantReference(articleId = 2, variantId = 3)

        /** Article 3, variant 4: an active, priced article whose variant is switched off. */
        val INACTIVE_VARIANT = ArticleVariantReference(articleId = 3, variantId = 4)

        /** Article 4, variant 6: the draft, which owns no price row. */
        val WITHOUT_PRICE = ArticleVariantReference(articleId = 4, variantId = 6)

        /** The ids of the seeded shirt, far away from the ones the identity sequences mint. */
        const val SHIRT_ARTICLE_ID = 900L
        const val SHIRT_VARIANT_ID = 901L
        const val RETIRED_SHIRT_VARIANT_ID = 902L
        const val SHIRT_PRICE_ID = 900L

        /** The shirt variant a customer may buy, and the one that is not sold any more. */
        val SHIRT =
            ArticleVariantReference(articleId = SHIRT_ARTICLE_ID, variantId = SHIRT_VARIANT_ID)
        val RETIRED_SHIRT =
            ArticleVariantReference(
                articleId = SHIRT_ARTICLE_ID,
                variantId = RETIRED_SHIRT_VARIANT_ID,
            )

        /** A shirt variant that was never minted. */
        val UNKNOWN_SHIRT_VARIANT =
            ArticleVariantReference(articleId = SHIRT_ARTICLE_ID, variantId = 909)

        /** A variant that was never minted. */
        val UNKNOWN_VARIANT = ArticleVariantReference(articleId = 4, variantId = 404)

        /** Variant 3 exists, but it belongs to article 2 — the pair is what is unknown. */
        val MISMATCHED_PAIR = ArticleVariantReference(articleId = 1, variantId = 3)

        /** The complete mug: supplier data, all nine measurements, and a price. */
        const val PURCHASABLE_MUG =
            """{"name":"Classic mug","descriptionShort":"Short","descriptionLong":"Long",""" +
                """"active":true,"categoryId":1,"supplierId":1,""" +
                """"supplierArticleName":"Classic 300","supplierArticleNumber":"4711",""" +
                """"mugDetails":{"heightMm":95,"diameterMm":82,"printTemplateWidthMm":200,""" +
                """"printTemplateHeightMm":90,"dishwasherSafe":true,"fillingQuantity":"300 ml",""" +
                """"documentFormatWidthMm":210,"documentFormatHeightMm":297,""" +
                """"documentFormatMarginBottomMm":15},""" +
                """"mugVariants":[""" +
                """{"name":"Black","insideColorCode":"#000","outsideColorCode":"#000",""" +
                """"isDefault":false,"active":true},""" +
                """{"name":"White","insideColorCode":"#fffffe","outsideColorCode":"#fffffd",""" +
                """"isDefault":true,"active":true}],""" +
                """"price":{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":500,""" +
                """"salesTotalInputCents":1490}}"""

        /** The same completeness with `active: false` — the one difference the answer reports. */
        const val INACTIVE_MUG =
            """{"name":"Retired mug","descriptionShort":"Short","descriptionLong":"Long",""" +
                """"active":false,"categoryId":1,"supplierId":2,""" +
                """"supplierArticleName":"Retired 400","supplierArticleNumber":"9000",""" +
                """"mugDetails":{"heightMm":95,"diameterMm":82,"printTemplateWidthMm":200,""" +
                """"printTemplateHeightMm":90,"dishwasherSafe":true},""" +
                """"mugVariants":[""" +
                """{"name":"Solo","insideColorCode":"#000","outsideColorCode":"#000",""" +
                """"isDefault":true,"active":true}],""" +
                """"price":{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":500,""" +
                """"salesTotalInputCents":2990}}"""

        /** An active, priced article with one retired and one sold variant. */
        const val TWO_TONE_MUG =
            """{"name":"Two-tone mug","descriptionShort":"Short","descriptionLong":"Long",""" +
                """"active":true,"categoryId":1,""" +
                """"mugDetails":{"heightMm":95,"diameterMm":82,"printTemplateWidthMm":200,""" +
                """"printTemplateHeightMm":90,"dishwasherSafe":true},""" +
                """"mugVariants":[""" +
                """{"name":"Retired","insideColorCode":"#0f0","outsideColorCode":"#0f0",""" +
                """"isDefault":false,"active":false},""" +
                """{"name":"Live","insideColorCode":"#00f","outsideColorCode":"#00f",""" +
                """"isDefault":true,"active":true}],""" +
                """"price":{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":100,""" +
                """"salesTotalInputCents":990}}"""

        /** Article 5: the one mug an admin asked to be printed square instead of wide. */
        const val SQUARE_MUG =
            """{"name":"Square mug","descriptionShort":"Short","descriptionLong":"Long",""" +
                """"active":false,"printAspectRatio":"1:1","mugVariants":[""" +
                """{"name":"Square","insideColorCode":"#fff","outsideColorCode":"#fff",""" +
                """"isDefault":true,"active":true}]}"""

        /** Article 1, variant 1 when the discounted mug is the only one written. */
        val DISCOUNTED = ArticleVariantReference(articleId = 1, variantId = 1)

        /** An active mug of 19,90 € with 20 % off, so the customer pays 15,92 €. */
        const val DISCOUNTED_MUG =
            """{"name":"Sale mug","descriptionShort":"Short","descriptionLong":"Long",""" +
                """"active":true,"categoryId":1,""" +
                """"mugDetails":{"heightMm":95,"diameterMm":82,"printTemplateWidthMm":200,""" +
                """"printTemplateHeightMm":90,"dishwasherSafe":true},""" +
                """"mugVariants":[""" +
                """{"name":"White","insideColorCode":"#fff","outsideColorCode":"#fff",""" +
                """"isDefault":true,"active":true}],""" +
                """"price":{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":500,""" +
                """"salesTotalInputCents":1990,"discountType":"PERCENTAGE","discountValue":20}}"""

        /** A draft: no category, no details, no supplier, and no price. */
        const val DRAFT_MUG =
            """{"name":"Draft mug","descriptionShort":"Short","descriptionLong":"Long",""" +
                """"active":false,"mugVariants":[""" +
                """{"name":"Draft","insideColorCode":"#fff","outsideColorCode":"#eee",""" +
                """"isDefault":true,"active":true}]}"""
    }
}
