package shop.voenix.article.mug

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleTestSchema
import shop.voenix.article.CountingDataSource
import shop.voenix.article.CountingPriceCatalog
import shop.voenix.article.RecordingPublicImageStorage
import shop.voenix.article.RecordingSupplierReader
import shop.voenix.article.installArticleModule
import shop.voenix.article.validateArticleRequests
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.pricing.installPricingModule
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.installVatModule

/**
 * The storefront half of the mug slice against real Ktor routes and a real PostgreSQL database.
 *
 * The visibility matrix is what this file is mostly about, and it is the matrix of the legacy
 * `ArticleService` tests: a mug is listed while it, its category, and — if it has one — its
 * subcategory are active. Every cell of that matrix is written by the admin routes and then read by
 * an anonymous client, so what is asserted is the answer a customer's browser gets.
 *
 * The two response bodies are compared as **whole** JSON documents, because that is what catches a
 * field the public contract must not have: any supplier field, any `active` flag, and `priceId`.
 */
internal class PublicMugIntegrationTest : PostgresIntegrationTest() {
    /**
     * The filter matrix. Six mugs, one per combination the legacy service distinguished, and only
     * the two visible ones come back.
     */
    @Test
    fun `the public list shows only mugs whose whole taxonomy is active`() {
        migratedDataSource("article-public-mug-filter-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "article-public-mug-filter-integration-secret") {
                fixture ->
                // 1: active, active category, no subcategory.
                fixture.createMug(visibleMugBody("Without a subcategory", categoryId = 1))
                // 2: active, active category, active subcategory.
                fixture.createMug(
                    visibleMugBody("In an active subcategory", categoryId = 1, subcategoryId = 1)
                )
                // 3: active, active category, inactive subcategory.
                fixture.createMug(
                    visibleMugBody("In an inactive subcategory", categoryId = 1, subcategoryId = 2)
                )
                // 4: active, inactive category.
                fixture.createMug(visibleMugBody("In an inactive category", categoryId = 2))
                // 5: complete but switched off after it was written.
                fixture.createMug(visibleMugBody("Deactivated", categoryId = 1, subcategoryId = 1))
                // 6: a draft — inactive, and therefore without category, details, and price.
                fixture.createMug(draftMugBody("Draft"))

                ArticleTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.article_categories SET active = FALSE WHERE id = 2;
                    UPDATE voenix.article_subcategories SET active = FALSE WHERE id = 2;
                    UPDATE voenix.article_mugs SET active = FALSE WHERE id = 5;
                    """
                        .trimIndent(),
                )

                assertEquals(listOf(1L, 2L), fixture.listedIds())

                // Switching the subcategory back on makes exactly its mug visible again.
                ArticleTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.article_subcategories SET active = TRUE WHERE id = 2",
                )
                assertEquals(listOf(1L, 2L, 3L), fixture.listedIds())

                // And so does switching the category back on.
                ArticleTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.article_categories SET active = TRUE WHERE id = 2",
                )
                assertEquals(listOf(1L, 2L, 3L, 4L), fixture.listedIds())
            }
        }
    }

    @Test
    fun `the public list is in display order and answers the documented shape`() {
        migratedDataSource("article-public-mug-shape-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "article-public-mug-shape-integration-secret") {
                fixture ->
                fixture.images.put(FIRST_IMAGE)
                fixture.createMug(shapeMugBody())
                fixture.createMug(visibleMugBody("Second mug", categoryId = 1))

                assertEquals(
                    Json.parseToJsonElement(DOCUMENTED_PUBLIC_LIST),
                    Json.parseToJsonElement(fixture.list().bodyAsText()),
                )

                // The order is the position: swapping the two in one statement — the unique rule is
                // deferred to COMMIT — turns the list around.
                ArticleTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.article_mugs
                    SET position = CASE id WHEN 1 THEN 2 ELSE 1 END
                    WHERE id IN (1, 2)
                    """
                        .trimIndent(),
                )
                assertEquals(listOf(2L to 1, 1L to 2), fixture.listedPositions())
            }
        }
    }

    /**
     * The public contract has no supplier and no `active`, and it never carries a price id. The
     * shape test above compares whole documents, so this one only names the fields that must stay
     * gone — a reader of the test should not have to diff two long JSON literals to see the rule.
     */
    @Test
    fun `the public payload carries no supplier fields, no active flags, and no price id`() {
        migratedDataSource("article-public-mug-contract-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "article-public-mug-contract-integration-secret") {
                fixture ->
                fixture.images.put(FIRST_IMAGE)
                fixture.createMug(shapeMugBody())

                val mug = fixture.listedItems().single()
                val forbidden =
                    listOf(
                        "active",
                        "priceId",
                        "supplierId",
                        "supplierArticleName",
                        "supplierArticleNumber",
                    )
                forbidden.forEach { field ->
                    assertTrue(field !in mug.keys, "The public mug must not carry `$field`")
                }
                val variant = mug.getValue("variants").jsonArray.first().jsonObject
                assertTrue("active" !in variant.keys, "A public variant must not carry `active`")

                // The stored supplier is real: it is left out of the answer, not missing from the
                // article.
                assertEquals(listOf(1L), ArticleTestSchema.storedMugSupplierIds(dataSource))
            }
        }
    }

    @Test
    fun `the public categories are the taxonomy that visible mugs use`() {
        migratedDataSource("article-public-mug-categories-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "article-public-mug-categories-integration-secret") {
                fixture ->
                // Two visible mugs in the first category: one in `Classic`, one without a
                // subcategory. `Travel` is used by a mug that is switched off afterwards, the
                // second category by a mug whose category is, and `Empty` by nobody at all.
                fixture.createMug(visibleMugBody("Classic mug", categoryId = 1, subcategoryId = 1))
                fixture.createMug(visibleMugBody("Plain mug", categoryId = 1))
                fixture.createMug(visibleMugBody("Travel mug", categoryId = 1, subcategoryId = 2))
                fixture.createMug(visibleMugBody("Poster mug", categoryId = 2, subcategoryId = 3))

                ArticleTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.article_mugs SET active = FALSE WHERE id = 3;
                    UPDATE voenix.article_categories SET active = FALSE WHERE id = 2;
                    UPDATE voenix.article_subcategories
                    SET example_image_filename = '$FIRST_IMAGE'
                    WHERE id = 1;
                    """
                        .trimIndent(),
                )

                assertEquals(
                    Json.parseToJsonElement(DOCUMENTED_PUBLIC_CATEGORIES),
                    Json.parseToJsonElement(fixture.categories().bodyAsText()),
                )

                // The mug in `Travel` coming back makes its subcategory a navigation entry again,
                // and it sorts behind `Classic` by position.
                ArticleTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.article_mugs SET active = TRUE WHERE id = 3",
                )
                assertEquals(listOf(1L to listOf(1L, 2L)), fixture.categoryTree())

                // With every mug of the first category hidden, the category disappears with them.
                ArticleTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.article_mugs SET active = FALSE WHERE id IN (1, 2, 3)",
                )
                assertEquals(emptyList<Pair<Long, List<Long>>>(), fixture.categoryTree())
            }
        }
    }

    /** The variants a customer sees: the active ones, the default first, then by name. */
    @Test
    fun `the public variants are the active ones with the default first`() {
        migratedDataSource("article-public-mug-variants-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "article-public-mug-variants-integration-secret") {
                fixture ->
                fixture.createMug(
                    visibleMugBody(
                        "Classic mug",
                        categoryId = 1,
                        variants =
                            """{"name":"Blue","insideColorCode":"#00f",""" +
                                """"outsideColorCode":"#00f","isDefault":false,"active":true},""" +
                                """{"name":"Hidden","insideColorCode":"#000",""" +
                                """"outsideColorCode":"#000","isDefault":false,"active":false},""" +
                                """{"name":"Amber","insideColorCode":"#fb0",""" +
                                """"outsideColorCode":"#fb0","isDefault":false,"active":true},""" +
                                """{"name":"Zebra","insideColorCode":"#fff",""" +
                                """"outsideColorCode":"#000","isDefault":true,"active":true}""",
                    )
                )

                assertEquals(listOf("Zebra", "Amber", "Blue"), fixture.listedVariantNames())
            }
        }
    }

    /**
     * The list may not read anything per mug. Three mugs must cost the same statements as one, and
     * every price of the page must be resolved by exactly one batched lookup.
     */
    @Test
    fun `the public list runs three data accesses for one mug and for three`() {
        migratedDataSource("article-public-mug-statements-test").use { dataSource ->
            seedCatalog(dataSource)
            val counting = CountingDataSource(dataSource)

            storefrontApplication(counting, "article-public-mug-statements-integration-secret") {
                fixture ->
                fixture.createMug(visibleMugBody("First", categoryId = 1, subcategoryId = 1))
                counting.statements.clear()
                fixture.prices.requestedIds.clear()
                assertEquals(1, fixture.listedIds().size)
                val forOneMug = counting.normalizedStatements()
                assertEquals(1, fixture.prices.requestedIds.size)

                fixture.createMug(visibleMugBody("Second", categoryId = 1))
                fixture.createMug(visibleMugBody("Third", categoryId = 1, subcategoryId = 1))
                counting.statements.clear()
                fixture.prices.requestedIds.clear()
                assertEquals(3, fixture.listedIds().size)
                val forThreeMugs = counting.normalizedStatements()

                assertEquals(
                    forOneMug,
                    forThreeMugs,
                    "The public list must run the same statements regardless of how many mugs it " +
                        "answers",
                )
                assertEquals(PUBLIC_LIST_STATEMENT_COUNT, forOneMug.size, "Statements: $forOneMug")
                // One lookup for three prices, carrying the three distinct ids.
                assertEquals(
                    listOf(setOf(1L, 2L, 3L)),
                    fixture.prices.requestedIds.toList(),
                )
            }
        }
    }

    /** Anonymous access is the point of these two routes; the admin subtree stays closed. */
    @Test
    fun `the public routes answer without a session while the admin routes do not`() {
        migratedDataSource("article-public-mug-access-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "article-public-mug-access-integration-secret") {
                fixture ->
                fixture.createMug(visibleMugBody("Classic mug", categoryId = 1, subcategoryId = 1))

                assertEquals(HttpStatusCode.OK, fixture.list().status)
                assertEquals(HttpStatusCode.OK, fixture.categories().status)
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    fixture.anonymous.get("/api/admin/articles/mugs").status,
                )
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    fixture.anonymous.get("/api/admin/articles/mugs/1").status,
                )
            }
        }
    }

    /** An empty catalog answers two empty arrays and asks the pricing module nothing. */
    @Test
    fun `an empty catalog answers empty arrays without a price lookup`() {
        migratedDataSource("article-public-mug-empty-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "article-public-mug-empty-integration-secret") {
                fixture ->
                assertEquals("[]", fixture.list().bodyAsText())
                assertEquals("[]", fixture.categories().bodyAsText())
                assertEquals(emptyList<Set<Long>>(), fixture.prices.requestedIds.toList())
            }
        }
    }

    private fun seedCatalog(dataSource: DataSource) {
        ArticleTestSchema.reset(dataSource)
        ArticleTestSchema.seedVat(dataSource)
        ArticleTestSchema.seedCategories(dataSource, "Mugs", "Posters", "Empty")
        ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "Classic", "Travel")
        ArticleTestSchema.seedSubcategories(dataSource, categoryId = 2, "Premium")
        ArticleTestSchema.seedSuppliers(dataSource, "Porcelain Ltd")
    }

    /** A complete, visible mug: active, with a category, its details, and its price. */
    private fun visibleMugBody(
        name: String,
        categoryId: Long,
        subcategoryId: Long? = null,
        variants: String = SINGLE_VARIANT,
    ): String =
        """{"name":"$name","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":true,"categoryId":$categoryId""" +
            (subcategoryId?.let { id -> ""","subcategoryId":$id""" } ?: "") +
            ""","mugDetails":{"heightMm":95,"diameterMm":82,"printTemplateWidthMm":200,""" +
            """"printTemplateHeightMm":90,"dishwasherSafe":true,"fillingQuantity":"300 ml"},""" +
            """"mugVariants":[$variants],""" +
            """"price":{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":500,""" +
            """"salesTotalInputCents":1490}}"""

    /** The mug of the documented answer: with a supplier, an inactive variant, and an image. */
    private fun shapeMugBody(): String =
        """{"name":"Classic mug","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":true,"categoryId":1,"subcategoryId":1,"supplierId":1,""" +
            """"supplierArticleName":"Classic 300","supplierArticleNumber":"4711",""" +
            """"mugDetails":{"heightMm":95,"diameterMm":82,"printTemplateWidthMm":200,""" +
            """"printTemplateHeightMm":90,"dishwasherSafe":true,"fillingQuantity":"300 ml"},""" +
            """"mugVariants":[""" +
            """{"name":"Black","insideColorCode":"#000","outsideColorCode":"#000",""" +
            """"isDefault":false,"active":true},""" +
            """{"name":"Hidden","insideColorCode":"#0f0","outsideColorCode":"#0f0",""" +
            """"isDefault":false,"active":false},""" +
            """{"name":"White","insideColorCode":"#fff","outsideColorCode":"#fff",""" +
            """"isDefault":true,"active":true,"exampleImageFilename":"$FIRST_IMAGE"}],""" +
            """"price":{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":500,""" +
            """"salesTotalInputCents":1490}}"""

    /** An invisible mug: a draft has no category, no details, and no price. */
    private fun draftMugBody(name: String): String =
        """{"name":"$name","descriptionShort":"Short","descriptionLong":"Long","active":false}"""

    /**
     * Runs [block] against the real module installed on [dataSource], with an admin client that
     * writes the catalog and an anonymous client that reads it.
     */
    private fun storefrontApplication(
        dataSource: DataSource,
        sessionSecret: String,
        block: suspend (PublicFixture) -> Unit,
    ) = testApplication {
        val images = RecordingPublicImageStorage()
        lateinit var prices: CountingPriceCatalog
        application {
            installHttpRuntime()
            install(RequestValidation) { validateArticleRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            val database = Database.connect(datasource = dataSource)
            prices =
                CountingPriceCatalog(installPricingModule(database, installVatModule(database)))
            installArticleModule(
                database,
                images,
                prices,
                RecordingSupplierReader(mapOf(1L to "Porcelain Ltd")),
            )
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
        // The storefront client has no cookie jar, so it never carries the admin session.
        block(PublicFixture(admin, token, client, images, prices))
    }

    /** The two clients a storefront test drives, plus the doubles it counts calls on. */
    private class PublicFixture(
        val admin: HttpClient,
        val token: String,
        val anonymous: HttpClient,
        val images: RecordingPublicImageStorage,
        val prices: CountingPriceCatalog,
    ) {
        suspend fun createMug(body: String) {
            val created =
                admin.post("/api/admin/articles/mugs") {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        }

        suspend fun list(): HttpResponse = anonymous.get(PUBLIC_PATH)

        suspend fun categories(): HttpResponse = anonymous.get("$PUBLIC_PATH/categories")

        suspend fun listedItems(): List<JsonObject> =
            Json.parseToJsonElement(list().bodyAsText()).jsonArray.map { item -> item.jsonObject }

        suspend fun listedIds(): List<Long> =
            listedItems().map { item -> item.getValue("id").jsonPrimitive.long }

        suspend fun listedPositions(): List<Pair<Long, Int>> =
            listedItems().map { item ->
                item.getValue("id").jsonPrimitive.long to
                    item.getValue("position").jsonPrimitive.content.toInt()
            }

        suspend fun listedVariantNames(): List<String> =
            listedItems().single().getValue("variants").jsonArray.map { variant ->
                variant.jsonObject.getValue("name").jsonPrimitive.content
            }

        /** The navigation as `category id to subcategory ids`, in the order it is answered. */
        suspend fun categoryTree(): List<Pair<Long, List<Long>>> =
            Json.parseToJsonElement(categories().bodyAsText()).jsonArray.map { category ->
                category.jsonObject.getValue("id").jsonPrimitive.long to
                    category.jsonObject.getValue("subcategories").jsonArray.map { subcategory ->
                        subcategory.jsonObject.getValue("id").jsonPrimitive.long
                    }
            }
    }

    private companion object {
        const val PUBLIC_PATH = "/api/articles/mugs"
        const val FIRST_IMAGE = RecordingPublicImageStorage.FIRST_FILENAME

        /**
         * Two statements of this module — the visible mugs with their taxonomy and the active
         * variants of all of them — plus the two the one batched `PriceCatalog.find` runs for the
         * prices and their VAT entries.
         */
        const val PUBLIC_LIST_STATEMENT_COUNT = 4

        const val SINGLE_VARIANT =
            """{"name":"White","insideColorCode":"#fff","outsideColorCode":"#fff",""" +
                """"isDefault":true,"active":true}"""

        val DOCUMENTED_PUBLIC_LIST =
            """
            [
              {
                "id": 1,
                "position": 1,
                "name": "Classic mug",
                "descriptionShort": "Short",
                "descriptionLong": "Long",
                "categoryId": 1,
                "subcategoryId": 1,
                "price": 1490,
                "mugDetails": {
                  "heightMm": 95,
                  "diameterMm": 82,
                  "printTemplateWidthMm": 200,
                  "printTemplateHeightMm": 90,
                  "fillingQuantity": "300 ml",
                  "dishwasherSafe": true,
                  "documentFormatWidthMm": null,
                  "documentFormatHeightMm": null,
                  "documentFormatMarginBottomMm": null
                },
                "variants": [
                  {
                    "id": 3,
                    "name": "White",
                    "insideColorCode": "#fff",
                    "outsideColorCode": "#fff",
                    "isDefault": true,
                    "exampleImageFilename": "$FIRST_IMAGE"
                  },
                  {
                    "id": 1,
                    "name": "Black",
                    "insideColorCode": "#000",
                    "outsideColorCode": "#000",
                    "isDefault": false,
                    "exampleImageFilename": null
                  }
                ]
              },
              {
                "id": 2,
                "position": 2,
                "name": "Second mug",
                "descriptionShort": "Short",
                "descriptionLong": "Long",
                "categoryId": 1,
                "subcategoryId": null,
                "price": 1490,
                "mugDetails": {
                  "heightMm": 95,
                  "diameterMm": 82,
                  "printTemplateWidthMm": 200,
                  "printTemplateHeightMm": 90,
                  "fillingQuantity": "300 ml",
                  "dishwasherSafe": true,
                  "documentFormatWidthMm": null,
                  "documentFormatHeightMm": null,
                  "documentFormatMarginBottomMm": null
                },
                "variants": [
                  {
                    "id": 4,
                    "name": "White",
                    "insideColorCode": "#fff",
                    "outsideColorCode": "#fff",
                    "isDefault": true,
                    "exampleImageFilename": null
                  }
                ]
              }
            ]
            """
                .trimIndent()

        val DOCUMENTED_PUBLIC_CATEGORIES =
            """
            [
              {
                "id": 1,
                "name": "Mugs",
                "position": 1,
                "subcategories": [
                  {
                    "id": 1,
                    "name": "Classic",
                    "exampleImageFilename": "$FIRST_IMAGE",
                    "position": 1
                  }
                ]
              }
            ]
            """
                .trimIndent()
    }
}
