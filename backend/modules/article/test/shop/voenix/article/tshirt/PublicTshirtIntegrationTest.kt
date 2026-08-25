package shop.voenix.article.tshirt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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
import shop.voenix.article.SyncedTshirtVariant
import shop.voenix.article.SyncedTshirts
import shop.voenix.article.installArticleModule
import shop.voenix.article.unreachableSpodClient
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
 * The storefront half of the t-shirt slice against real Ktor routes and a real PostgreSQL database.
 *
 * It asks the same questions of the shirts that `PublicMugIntegrationTest` asks of the mugs — the
 * visibility matrix, the display order, the variant filter, and a statement count that does not
 * grow with the catalog — because the two lists apply one rule that is written once per type.
 *
 * The catalog is built the way it really comes into being since ADR 0003: the garment half of every
 * shirt is inserted as a sync run would insert it (`SyncedTshirts`), and the shop half —
 * visibility, category path, frame, ratio, default variant, and price — is written through the
 * surviving admin `PUT`. The storefront answer itself is unchanged by that ticket, which is what
 * these tests pin.
 *
 * The question that is this file's alone is the last one: **a customer must not learn that the
 * print-on-demand partner exists.** The whole-document comparison is what enforces it. The three
 * SPOD ids are stored on every variant the sync wrote, and they may not appear in a single byte the
 * anonymous client receives.
 */
internal class PublicTshirtIntegrationTest : PostgresIntegrationTest() {
    /**
     * The filter matrix. Six shirts, one per combination the visibility rule distinguishes, and
     * only the two visible ones come back.
     */
    @Test
    fun `the public list shows only shirts whose whole category path is active`() {
        migratedDataSource("article-public-tshirt-filter-test").use { dataSource ->
            seedCatalog(dataSource)
            // 1: active, active category, no subcategory.
            seedShirt(dataSource, id = 1)
            // 2: active, active category, active subcategory.
            seedShirt(dataSource, id = 2)
            // 3: active, active category, inactive subcategory.
            seedShirt(dataSource, id = 3)
            // 4: active, inactive category.
            seedShirt(dataSource, id = 4)
            // 5: complete but switched off after it was written.
            seedShirt(dataSource, id = 5)
            // 6: a draft — inactive, and therefore without category and price.
            seedShirt(dataSource, id = 6)

            storefrontApplication(dataSource, "article-public-tshirt-filter-integration-secret") {
                fixture ->
                fixture.activate(id = 1, categoryId = 1)
                fixture.activate(id = 2, categoryId = 1, subcategoryId = 1)
                fixture.activate(id = 3, categoryId = 1, subcategoryId = 2)
                fixture.activate(id = 4, categoryId = 2)
                fixture.activate(id = 5, categoryId = 1, subcategoryId = 1)

                ArticleTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.article_categories SET active = FALSE WHERE id = 2;
                    UPDATE voenix.article_subcategories SET active = FALSE WHERE id = 2;
                    UPDATE voenix.article_tshirts SET active = FALSE WHERE id = 5;
                    """
                        .trimIndent(),
                )

                assertEquals(listOf(1L, 2L), fixture.listedIds())

                // Switching the subcategory back on makes exactly its shirt visible again.
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
        migratedDataSource("article-public-tshirt-shape-test").use { dataSource ->
            seedCatalog(dataSource)
            seedShapeShirt(dataSource)
            seedShirt(dataSource, id = 2, position = 2, name = "Second tee", firstVariantId = 4)

            storefrontApplication(dataSource, "article-public-tshirt-shape-integration-secret") {
                fixture ->
                fixture.images.put(FIRST_IMAGE, SECOND_IMAGE)
                fixture.activate(
                    id = 1,
                    categoryId = 1,
                    subcategoryId = 1,
                    defaultVariantId = 1,
                    printAspectRatio = "16:9",
                )
                fixture.activate(id = 2, categoryId = 1, defaultVariantId = 4)

                assertEquals(
                    Json.parseToJsonElement(DOCUMENTED_PUBLIC_LIST),
                    Json.parseToJsonElement(fixture.list().bodyAsText()),
                )

                // The order is the position: swapping the two in one statement — the unique rule is
                // deferred to COMMIT — turns the list around.
                ArticleTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.article_tshirts
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
     * The rule of this slice: the printer's vocabulary stays inside the backend. The shape test
     * above compares whole documents, so this one names what must stay gone — a reader should not
     * have to diff two long JSON literals to see it.
     */
    @Test
    fun `the public payload carries no SPOD ids, no supplier fields, and no active flags`() {
        migratedDataSource("article-public-tshirt-contract-test").use { dataSource ->
            seedCatalog(dataSource)
            seedShapeShirt(dataSource)

            storefrontApplication(
                dataSource,
                "article-public-tshirt-contract-integration-secret",
            ) { fixture ->
                fixture.images.put(FIRST_IMAGE, SECOND_IMAGE)
                fixture.activate(id = 1, categoryId = 1, subcategoryId = 1, defaultVariantId = 1)

                val shirt = fixture.listedItems().single()
                listOf("active", "priceId", "supplierId", "sync").forEach { field ->
                    assertTrue(field !in shirt.keys, "The public shirt must not carry `$field`")
                }
                val variant = shirt.getValue("variants").jsonArray.first().jsonObject
                listOf(
                        "active",
                        "spodProductTypeId",
                        "spodAppearanceId",
                        "spodSizeId",
                        "spodVariantId",
                        "sku",
                        "sizeLabel",
                    )
                    .forEach { field ->
                        assertTrue(
                            field !in variant.keys,
                            "A public variant must not carry `$field`",
                        )
                    }

                // Not one byte of the answer names the printer, whatever it is nested in.
                val body = fixture.list().bodyAsText()
                assertTrue("spod" !in body.lowercase(), "The storefront answer named SPOD: $body")
                assertTrue(SPOD_PRODUCT_TYPE_ID !in body, "The storefront answer leaked a SPOD id")

                // The stored ids and the supplier are real: they are left out of the answer, not
                // missing from the article.
                assertEquals(
                    listOf(SPOD_PRODUCT_TYPE_ID.toLong()),
                    ArticleTestSchema.storedTshirtProductTypeIds(dataSource).distinct(),
                )
            }
        }
    }

    /**
     * The variants a customer sees: the active ones, the default first, then by colour and size.
     */
    @Test
    fun `the public variants are the active ones with the default first`() {
        migratedDataSource("article-public-tshirt-variants-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedTshirts.insert(
                dataSource,
                id = 1,
                name = "Classic tee",
                variants =
                    listOf(
                        variant(id = 1, colorName = "White", sizeLabel = "L", appearanceId = 6),
                        variant(
                            id = 2,
                            colorName = "Grey",
                            sizeLabel = "S",
                            appearanceId = 7,
                            active = false,
                        ),
                        variant(id = 3, colorName = "White", sizeLabel = "M", appearanceId = 6),
                        variant(
                            id = 4,
                            colorName = "Black",
                            sizeLabel = "M",
                            appearanceId = 5,
                            isDefault = true,
                        ),
                    ),
            )

            storefrontApplication(
                dataSource,
                "article-public-tshirt-variants-integration-secret",
            ) { fixture ->
                fixture.activate(id = 1, categoryId = 1, defaultVariantId = 4)

                assertEquals(
                    listOf("Black / M", "White / L", "White / M"),
                    fixture.listedVariantNames(),
                )
            }
        }
    }

    /**
     * The list may not read anything per shirt. Three shirts must cost the same statements as one,
     * and every price of the page must be resolved by exactly one batched lookup.
     */
    @Test
    fun `the public list runs three data accesses for one shirt and for three`() {
        migratedDataSource("article-public-tshirt-statements-test").use { dataSource ->
            seedCatalog(dataSource)
            seedShirt(dataSource, id = 1, name = "First")
            seedShirt(dataSource, id = 2, position = 2, name = "Second", firstVariantId = 2)
            seedShirt(dataSource, id = 3, position = 3, name = "Third", firstVariantId = 3)
            val counting = CountingDataSource(dataSource)

            storefrontApplication(
                counting,
                "article-public-tshirt-statements-integration-secret",
            ) { fixture ->
                fixture.activate(id = 1, categoryId = 1, subcategoryId = 1)
                counting.statements.clear()
                fixture.prices.requestedIds.clear()
                assertEquals(1, fixture.listedIds().size)
                val forOneShirt = counting.normalizedStatements()
                assertEquals(1, fixture.prices.requestedIds.size)

                fixture.activate(id = 2, categoryId = 1, defaultVariantId = 2)
                fixture.activate(id = 3, categoryId = 1, subcategoryId = 1, defaultVariantId = 3)
                counting.statements.clear()
                fixture.prices.requestedIds.clear()
                assertEquals(3, fixture.listedIds().size)
                val forThreeShirts = counting.normalizedStatements()

                assertEquals(
                    forOneShirt,
                    forThreeShirts,
                    "The public list must run the same statements regardless of how many shirts " +
                        "it answers",
                )
                assertEquals(
                    PUBLIC_LIST_STATEMENT_COUNT,
                    forOneShirt.size,
                    "Statements: $forOneShirt",
                )
                // One lookup for three prices, carrying the three distinct ids.
                assertEquals(listOf(setOf(1L, 2L, 3L)), fixture.prices.requestedIds.toList())
            }
        }
    }

    /** Anonymous access is the point of this route; the admin subtree stays closed. */
    @Test
    fun `the public route answers without a session while the admin routes do not`() {
        migratedDataSource("article-public-tshirt-access-test").use { dataSource ->
            seedCatalog(dataSource)
            seedShirt(dataSource, id = 1, name = "Classic tee")

            storefrontApplication(dataSource, "article-public-tshirt-access-integration-secret") {
                fixture ->
                fixture.activate(id = 1, categoryId = 1, subcategoryId = 1)

                assertEquals(HttpStatusCode.OK, fixture.list().status)
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    fixture.anonymous.get("/api/admin/articles/tshirts").status,
                )
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    fixture.anonymous.get("/api/admin/articles/tshirts/1").status,
                )
            }
        }
    }

    /** An empty catalog answers an empty array and asks the pricing module nothing. */
    @Test
    fun `an empty catalog answers an empty array without a price lookup`() {
        migratedDataSource("article-public-tshirt-empty-test").use { dataSource ->
            seedCatalog(dataSource)

            storefrontApplication(dataSource, "article-public-tshirt-empty-integration-secret") {
                fixture ->
                assertEquals("[]", fixture.list().bodyAsText())
                assertEquals(emptyList<Set<Long>>(), fixture.prices.requestedIds.toList())
            }
        }
    }

    /**
     * A discount is configured on the price, and the storefront answers both amounts: `price` is
     * what the customer pays, `regularPrice` is the amount a shop strikes through. A shirt without
     * a discount still carries the key — it answers `null`, so a client never has to ask whether
     * the field exists.
     */
    @Test
    fun `a discounted shirt answers the effective price next to the regular one`() {
        migratedDataSource("article-public-tshirt-discount-test").use { dataSource ->
            seedCatalog(dataSource)
            seedShirt(dataSource, id = 1)
            seedShirt(dataSource, id = 2, position = 2, name = "Second tee", firstVariantId = 4)

            storefrontApplication(
                dataSource,
                "article-public-tshirt-discount-integration-secret",
            ) { fixture ->
                fixture.activate(id = 1, categoryId = 1, price = DISCOUNTED_PRICE)
                fixture.activate(id = 2, categoryId = 1, defaultVariantId = 4)

                val (discounted, regular) = fixture.listedItems()
                assertEquals(1592, discounted.getValue("price").jsonPrimitive.int)
                assertEquals(1990, discounted.getValue("regularPrice").jsonPrimitive.int)
                assertEquals(1990, regular.getValue("price").jsonPrimitive.int)
                assertEquals(JsonNull, regular.getValue("regularPrice"))
            }
        }
    }

    private fun seedCatalog(dataSource: DataSource) {
        ArticleTestSchema.reset(dataSource)
        ArticleTestSchema.seedVat(dataSource)
        ArticleTestSchema.seedCategories(dataSource, "Shirts", "Posters", "Empty")
        ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "Classic", "Slim")
        ArticleTestSchema.seedSubcategories(dataSource, categoryId = 2, "Premium")
        ArticleTestSchema.seedSuppliers(dataSource, "Print Partner Ltd")
        SyncedTshirts.seedSpodDestination(dataSource, label = "Print Partner Ltd")
    }

    /** A synced shirt with one active default variant, as a sync run would have left it. */
    private fun seedShirt(
        dataSource: DataSource,
        id: Long,
        position: Int = id.toInt(),
        name: String = "Shirt $id",
        firstVariantId: Long = id,
    ) {
        SyncedTshirts.insert(
            dataSource,
            id = id,
            position = position,
            name = name,
            variants = listOf(variant(id = firstVariantId, isDefault = true, sizeId = 77)),
        )
    }

    /** The shirt of the documented answer: a size chart, an image, and a hidden third variant. */
    private fun seedShapeShirt(dataSource: DataSource) {
        SyncedTshirts.insert(
            dataSource,
            id = 1,
            name = "Classic tee",
            sizeChartImageFilename = SECOND_IMAGE,
            variants =
                listOf(
                    variant(
                        id = 1,
                        appearanceId = 5,
                        isDefault = true,
                        exampleImageFilename = FIRST_IMAGE,
                    ),
                    variant(id = 2, colorName = "White", sizeLabel = "L", appearanceId = 6),
                    variant(
                        id = 3,
                        colorName = "Grey",
                        sizeLabel = "S",
                        appearanceId = 7,
                        active = false,
                    ),
                ),
        )
    }

    private fun variant(
        id: Long,
        colorName: String = "Black",
        sizeLabel: String = "M",
        appearanceId: Long = 5,
        sizeId: Long = sizeLabel.first().code.toLong(),
        isDefault: Boolean = false,
        active: Boolean = true,
        exampleImageFilename: String? = null,
    ): SyncedTshirtVariant =
        SyncedTshirtVariant(
            id = id,
            colorName = colorName,
            colorHex = VARIANT_COLOR_HEX,
            sizeLabel = sizeLabel,
            spodProductTypeId = SPOD_PRODUCT_TYPE_ID.toLong(),
            spodAppearanceId = appearanceId,
            spodSizeId = sizeId,
            isDefault = isDefault,
            active = active,
            exampleImageFilename = exampleImageFilename,
        )

    /**
     * Runs [block] against the real module installed on [dataSource], with an admin client that
     * writes the shop half of the catalog and an anonymous client that reads it.
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
                RecordingSupplierReader(mapOf(1L to "Print Partner Ltd")),
                unreachableSpodClient(),
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
        /** Writes the shop half of a synced shirt: everything a customer needs it to have. */
        suspend fun activate(
            id: Long,
            categoryId: Long,
            subcategoryId: Long? = null,
            defaultVariantId: Long = id,
            printAspectRatio: String = "1:1",
            price: String = REGULAR_PRICE,
        ) {
            val body =
                """{"active":true,"categoryId":$categoryId""" +
                    (subcategoryId?.let { value -> ""","subcategoryId":$value""" } ?: "") +
                    ""","defaultVariantId":$defaultVariantId""" +
                    ""","printAspectRatio":"$printAspectRatio",$PRINT_FRAME,""" +
                    """"price":$price}"""
            val updated =
                admin.put("/api/admin/articles/tshirts/$id") {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        }

        suspend fun list(): HttpResponse = anonymous.get(PUBLIC_PATH)

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
    }

    private companion object {
        const val PUBLIC_PATH = "/api/articles/tshirts"
        const val FIRST_IMAGE = RecordingPublicImageStorage.FIRST_FILENAME
        const val SECOND_IMAGE = RecordingPublicImageStorage.SECOND_FILENAME

        /** The one SPOD product type every variant of these tests is printed on. */
        const val SPOD_PRODUCT_TYPE_ID = "812"

        const val VARIANT_COLOR_HEX = "#101010"

        /** A price without a discount: what the admin enters is what the customer pays. */
        const val REGULAR_PRICE =
            """{"purchaseVatId":1,"salesVatId":1,""" +
                """"purchasePriceInputCents":500,"salesTotalInputCents":1990}"""

        /** 19,90 € with 20 % off, so the customer pays 15,92 €. */
        const val DISCOUNTED_PRICE =
            """{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":500,""" +
                """"salesTotalInputCents":1990,"discountType":"PERCENTAGE","discountValue":20}"""

        /**
         * Two statements of this module — the visible shirts with their categories and the active
         * variants of all of them — plus the two the one batched `PriceCatalog.find` runs for the
         * prices and their VAT entries.
         */
        const val PUBLIC_LIST_STATEMENT_COUNT = 4

        const val PRINT_FRAME =
            """"printFrame":{"leftPct":25,"topPct":20,"widthPct":50,"heightPct":40.5}"""

        val DOCUMENTED_PUBLIC_LIST =
            """
            [
              {
                "articleType": "TSHIRT",
                "id": 1,
                "position": 1,
                "name": "Classic tee",
                "descriptionShort": "Short",
                "descriptionLong": "Long",
                "categoryId": 1,
                "subcategoryId": 1,
                "price": 1990,
                "regularPrice": null,
                "printAspectRatio": "16:9",
                "sizeChartImageFilename": "$SECOND_IMAGE",
                "printFrame": {
                  "leftPct": 25.0,
                  "topPct": 20.0,
                  "widthPct": 50.0,
                  "heightPct": 40.5
                },
                "variants": [
                  {
                    "id": 1,
                    "name": "Black / M",
                    "colorName": "Black",
                    "colorHex": "$VARIANT_COLOR_HEX",
                    "size": "M",
                    "isDefault": true,
                    "exampleImageFilename": "$FIRST_IMAGE"
                  },
                  {
                    "id": 2,
                    "name": "White / L",
                    "colorName": "White",
                    "colorHex": "$VARIANT_COLOR_HEX",
                    "size": "L",
                    "isDefault": false,
                    "exampleImageFilename": null
                  }
                ]
              },
              {
                "articleType": "TSHIRT",
                "id": 2,
                "position": 2,
                "name": "Second tee",
                "descriptionShort": "Short",
                "descriptionLong": "Long",
                "categoryId": 1,
                "subcategoryId": null,
                "price": 1990,
                "regularPrice": null,
                "printAspectRatio": "1:1",
                "sizeChartImageFilename": null,
                "printFrame": {
                  "leftPct": 25.0,
                  "topPct": 20.0,
                  "widthPct": 50.0,
                  "heightPct": 40.5
                },
                "variants": [
                  {
                    "id": 4,
                    "name": "Black / M",
                    "colorName": "Black",
                    "colorHex": "$VARIANT_COLOR_HEX",
                    "size": "M",
                    "isDefault": true,
                    "exampleImageFilename": null
                  }
                ]
              }
            ]
            """
                .trimIndent()
    }
}
