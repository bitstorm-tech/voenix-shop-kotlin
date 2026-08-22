package shop.voenix.article.mug

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
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
 * The mug read slice against real Ktor routes and a real PostgreSQL database.
 *
 * Two things are asserted here that a field-by-field check would not catch. The response bodies are
 * compared as **whole** JSON documents, so a field that is added, renamed, or left over — `priceId`
 * and `articleType` are the two the migration dropped — fails the test rather than passing
 * unnoticed. And every lookup that could become a query per row is counted: the supplier names, the
 * price of a mug, and the SQL statements the list itself runs.
 */
internal class MugArticleReadIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the list is in display order and answers the documented shape`() {
        migratedDataSource("article-mug-list-shape-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-list-shape-integration-secret") { fixture ->
                fixture.images.put(FIRST_IMAGE, SECOND_IMAGE)
                fixture.createMug(completeMugBody(withVariantImages = true))
                fixture.createMug(draftMugBody("Draft mug"))

                assertEquals(
                    Json.parseToJsonElement(DOCUMENTED_LIST),
                    Json.parseToJsonElement(fixture.list().bodyAsText()),
                )

                // The order is the position, not the id: swapping the two positions in one
                // statement — the unique rule is deferred to COMMIT — turns the list around.
                ArticleTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.article_mugs
                    SET position = CASE id WHEN 1 THEN 2 ELSE 1 END
                    WHERE id IN (1, 2)
                    """
                        .trimIndent(),
                )
                assertEquals(
                    listOf(2L to 1, 1L to 2),
                    fixture.listedPositions(),
                    "The list must be ordered by position",
                )
            }
        }
    }

    /**
     * The picture the overview shows for a mug. The legacy list looked at the variants *that have
     * an image*, preferred the default one, and otherwise took the oldest — so a default variant
     * without a picture does not hide the picture of another variant.
     */
    @Test
    fun `the list example image prefers the default variant and falls back by id`() {
        migratedDataSource("article-mug-list-image-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-list-image-integration-secret") { fixture ->
                fixture.images.put(FIRST_IMAGE, SECOND_IMAGE)
                // Black is written first and has no image, White is the default and has one.
                fixture.createMug(completeMugBody(withVariantImages = true, secondImage = false))
                assertEquals(listOf(FIRST_IMAGE), fixture.listedExampleImages())

                // The default keeps no image while an older variant has one: that one is shown.
                ArticleTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.article_mug_variants
                    SET example_image_filename =
                        CASE WHEN is_default THEN NULL ELSE '$SECOND_IMAGE' END
                    """
                        .trimIndent(),
                )
                assertEquals(listOf(SECOND_IMAGE), fixture.listedExampleImages())

                // Without any default, and with an image on both variants, the oldest answers.
                ArticleTestSchema.execute(
                    dataSource,
                    """
                    UPDATE voenix.article_mug_variants
                    SET is_default = FALSE,
                        example_image_filename =
                            CASE
                                WHEN id = (SELECT min(id) FROM voenix.article_mug_variants)
                                THEN '$FIRST_IMAGE'
                                ELSE '$SECOND_IMAGE'
                            END
                    """
                        .trimIndent(),
                )
                assertEquals(listOf(FIRST_IMAGE), fixture.listedExampleImages())

                // A mug without variants has no picture and counts none.
                fixture.createMug(draftMugBody("Draft mug"))
                assertEquals(listOf(FIRST_IMAGE, null), fixture.listedExampleImages())
                assertEquals(listOf(2, 0), fixture.listedVariantCounts())
            }
        }
    }

    @Test
    fun `the list resolves every supplier name in one batched lookup`() {
        migratedDataSource("article-mug-list-supplier-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(
                dataSource,
                "article-mug-list-supplier-integration-secret",
                // The second supplier exists in the database but not in the reader's answer.
                supplierNames = mapOf(1L to "Porcelain Ltd"),
            ) { fixture ->
                fixture.createMug(draftMugBody("First", supplierId = 1))
                fixture.createMug(draftMugBody("Second", supplierId = 1))
                fixture.createMug(draftMugBody("Third", supplierId = 2))
                fixture.createMug(draftMugBody("Fourth"))
                fixture.suppliers.requestedIds.clear()

                assertEquals(
                    listOf("Porcelain Ltd", "Porcelain Ltd", null, null),
                    fixture.listedSupplierNames(),
                )
                // One call for four rows, carrying the distinct ids only.
                assertEquals(listOf(setOf(1L, 2L)), fixture.suppliers.requestedIds.toList())
            }
        }
    }

    /**
     * The list may not read anything per row. Three mugs with variants, categories, and suppliers
     * must cost exactly the same SQL statements as one, which is what a query inside the row loop
     * would break.
     */
    @Test
    fun `the list runs the same statements for one mug and for three`() {
        migratedDataSource("article-mug-list-statements-test").use { dataSource ->
            seedCatalog(dataSource)
            val counting = CountingDataSource(dataSource)

            adminApplication(counting, "article-mug-list-statements-integration-secret") { fixture
                ->
                fixture.createMug(completeMugBody())
                counting.statements.clear()
                fixture.list()
                val forOneMug = counting.normalizedStatements()

                fixture.createMug(completeMugBody(name = "Second mug"))
                fixture.createMug(completeMugBody(name = "Third mug"))
                counting.statements.clear()
                assertEquals(3, listedIds(fixture.list().bodyAsText()).size)
                val forThreeMugs = counting.normalizedStatements()

                // The same statements, and only their `IN` lists grew — three rows, not three
                // round trips.
                assertEquals(
                    forOneMug,
                    forThreeMugs,
                    "The list must run the same statements regardless of how many mugs it answers",
                )
                assertEquals(LIST_STATEMENT_COUNT, forOneMug.size, "Statements: $forOneMug")
            }
        }
    }

    /**
     * The reorder answers the same rows the list answers, so it is labeled the same way: the whole
     * new order in one document, with every supplier name resolved in one batched lookup.
     */
    @Test
    fun `the reorder answers the complete order with its supplier names`() {
        migratedDataSource("article-mug-reorder-shape-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-reorder-shape-integration-secret") { fixture
                ->
                fixture.createMug(draftMugBody("First"))
                fixture.createMug(draftMugBody("Second", supplierId = 1))
                fixture.createMug(draftMugBody("Third", supplierId = 2))
                fixture.suppliers.requestedIds.clear()

                val reordered = fixture.reorder(sourceId = 3, targetId = 1)
                assertEquals(HttpStatusCode.OK, reordered.status, reordered.bodyAsText())
                val order = Json.parseToJsonElement(reordered.bodyAsText()).jsonArray
                assertEquals(
                    listOf(3L to 1, 1L to 2, 2L to 3),
                    order.map { item ->
                        item.jsonObject.getValue("id").jsonPrimitive.long to
                            item.jsonObject.getValue("position").jsonPrimitive.content.toInt()
                    },
                )
                assertEquals(
                    listOf("Glass Co", null, "Porcelain Ltd"),
                    order.map { item ->
                        item.jsonObject.getValue("supplierName").jsonPrimitive.contentOrNull
                    },
                )
                // One lookup for the whole answer, carrying the distinct ids only.
                assertEquals(listOf(setOf(1L, 2L)), fixture.suppliers.requestedIds.toList())
                assertEquals(
                    listOf("Third" to 1, "First" to 2, "Second" to 3),
                    ArticleTestSchema.orderedMugs(dataSource),
                )

                // An id that is not in the order is the same answer an unknown article gets.
                val missing = fixture.reorder(sourceId = 404, targetId = 1)
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals(
                    "Article not found",
                    Json.parseToJsonElement(missing.bodyAsText())
                        .jsonObject
                        .getValue("message")
                        .jsonPrimitive
                        .content,
                )
                assertEquals(
                    listOf("Third" to 1, "First" to 2, "Second" to 3),
                    ArticleTestSchema.orderedMugs(dataSource),
                )
            }
        }
    }

    @Test
    fun `the detail answers the full representation with its ordered variants`() {
        migratedDataSource("article-mug-detail-shape-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-detail-shape-integration-secret") { fixture ->
                fixture.images.put(FIRST_IMAGE)
                fixture.createMug(detailMugBody())

                val response = fixture.get(1)
                assertEquals(HttpStatusCode.OK, response.status)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

                // The whole document except the price, which pricing owns and locks itself.
                assertEquals(
                    Json.parseToJsonElement(DOCUMENTED_DETAIL).jsonObject,
                    JsonObject(body - "price"),
                )
                // Every field of the contract, and nothing else: no `priceId`, no `articleType`.
                assertEquals(DOCUMENTED_DETAIL_FIELDS, body.keys)
                assertNull(body["priceId"])
                assertNull(body["articleType"])

                val price = body.getValue("price").jsonObject
                assertEquals(
                    ArticleTestSchema.storedPriceIds(dataSource),
                    listOf(price.getValue("id").jsonPrimitive.long),
                )
                assertEquals(
                    1490,
                    price.getValue("salesTotalInputCents").jsonPrimitive.content.toInt(),
                )
            }
        }
    }

    @Test
    fun `the detail resolves its price in one lookup and reports a missing mug`() {
        migratedDataSource("article-mug-detail-price-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-detail-price-integration-secret") { fixture ->
                fixture.createMug(completeMugBody())
                fixture.createMug(draftMugBody("Without a price"))
                val priceId = ArticleTestSchema.storedPriceIds(dataSource).single()
                fixture.prices.requestedIds.clear()

                assertEquals(HttpStatusCode.OK, fixture.get(1).status)
                assertEquals(listOf(setOf(priceId)), fixture.prices.requestedIds.toList())

                // A mug without a price asks the price catalog nothing at all.
                fixture.prices.requestedIds.clear()
                val withoutPrice = Json.parseToJsonElement(fixture.get(2).bodyAsText()).jsonObject
                assertNull(withoutPrice.getValue("price").jsonPrimitive.contentOrNull)
                assertEquals(emptyList(), fixture.prices.requestedIds.toList())

                val missing = fixture.get(404)
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals(
                    "Article not found",
                    Json.parseToJsonElement(missing.bodyAsText())
                        .jsonObject
                        .getValue("message")
                        .jsonPrimitive
                        .content,
                )
            }
        }
    }

    private fun seedCatalog(dataSource: DataSource) {
        ArticleTestSchema.reset(dataSource)
        ArticleTestSchema.seedVat(dataSource)
        ArticleTestSchema.seedCategories(dataSource, "Mugs", "Posters")
        ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "Classic")
        ArticleTestSchema.seedSuppliers(dataSource, "Porcelain Ltd", "Glass Co")
    }

    private fun completeMugBody(
        name: String = "Classic mug",
        withVariantImages: Boolean = false,
        secondImage: Boolean = true,
    ): String {
        val whiteImage = if (withVariantImages) ""","exampleImageFilename":"$FIRST_IMAGE"""" else ""
        val blackImage =
            if (withVariantImages && secondImage) {
                ""","exampleImageFilename":"$SECOND_IMAGE""""
            } else {
                ""
            }
        return """{"name":"$name","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":true,"categoryId":1,"subcategoryId":1,"supplierId":1,""" +
            """"supplierArticleName":"Classic 300","supplierArticleNumber":"4711",""" +
            """"mugDetails":{"heightMm":95,"diameterMm":82,"printTemplateWidthMm":200,""" +
            """"printTemplateHeightMm":90,"dishwasherSafe":true,"fillingQuantity":"300 ml"},""" +
            """"mugVariants":[""" +
            """{"name":"Black","insideColorCode":"#000","outsideColorCode":"#000",""" +
            """"isDefault":false,"active":true$blackImage},""" +
            """{"name":"White","insideColorCode":"#fff","outsideColorCode":"#fff",""" +
            """"isDefault":true,"active":true$whiteImage}],""" +
            """"price":{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":500,""" +
            """"salesTotalInputCents":1490}}"""
    }

    /**
     * A mug whose variants prove the whole variant order at once: the default first, then by name,
     * and two variants of the same name by id.
     */
    private fun detailMugBody(): String =
        """{"name":"Classic mug","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":true,"categoryId":1,"subcategoryId":1,"supplierId":1,""" +
            """"supplierArticleName":"Classic 300","supplierArticleNumber":"4711",""" +
            """"mugDetails":{"heightMm":95,"diameterMm":82,"printTemplateWidthMm":200,""" +
            """"printTemplateHeightMm":90,"dishwasherSafe":true,"fillingQuantity":"300 ml"},""" +
            """"mugVariants":[""" +
            """{"name":"Blue","insideColorCode":"#00f","outsideColorCode":"#00f",""" +
            """"isDefault":false,"active":true},""" +
            """{"name":"Blue","insideColorCode":"#00e","outsideColorCode":"#00e",""" +
            """"isDefault":false,"active":false},""" +
            """{"name":"Amber","insideColorCode":"#fb0","outsideColorCode":"#fb0",""" +
            """"isDefault":false,"active":true},""" +
            """{"name":"Zebra","insideColorCode":"#fff","outsideColorCode":"#000",""" +
            """"isDefault":true,"active":true,"exampleImageFilename":"$FIRST_IMAGE"}],""" +
            """"price":{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":500,""" +
            """"salesTotalInputCents":1490}}"""

    private fun draftMugBody(
        name: String,
        supplierId: Long? = null,
    ): String {
        val supplier = supplierId?.let { value -> ""","supplierId":$value""" } ?: ""
        return """{"name":"$name","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":false$supplier}"""
    }

    private fun listedIds(body: String): List<Long> =
        Json.parseToJsonElement(body).jsonArray.map { item ->
            item.jsonObject.getValue("id").jsonPrimitive.long
        }

    /** Runs [block] against the real module installed on [dataSource], signed in as an admin. */
    private fun adminApplication(
        dataSource: DataSource,
        sessionSecret: String,
        supplierNames: Map<Long, String> = mapOf(1L to "Porcelain Ltd", 2L to "Glass Co"),
        block: suspend (ReadFixture) -> Unit,
    ) = testApplication {
        val images = RecordingPublicImageStorage()
        val suppliers = RecordingSupplierReader(supplierNames)
        lateinit var prices: CountingPriceCatalog
        application {
            installHttpRuntime()
            install(RequestValidation) { validateArticleRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            val database = Database.connect(datasource = dataSource)
            prices =
                CountingPriceCatalog(installPricingModule(database, installVatModule(database)))
            installArticleModule(database, images, prices, suppliers)
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
        block(ReadFixture(admin, token, images, suppliers, prices))
    }

    /** Everything a read test drives: the signed-in client and the doubles it counts calls on. */
    private class ReadFixture(
        val admin: HttpClient,
        val token: String,
        val images: RecordingPublicImageStorage,
        val suppliers: RecordingSupplierReader,
        val prices: CountingPriceCatalog,
    ) {
        suspend fun createMug(body: String) {
            val created =
                admin.post(BASE_PATH) {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        }

        suspend fun list(): HttpResponse =
            admin.get(BASE_PATH).also { response ->
                assertEquals(HttpStatusCode.OK, response.status)
            }

        suspend fun get(id: Long): HttpResponse = admin.get("$BASE_PATH/$id")

        suspend fun reorder(
            sourceId: Long,
            targetId: Long,
        ): HttpResponse =
            admin.put("$BASE_PATH/order") {
                header(AuthRouting.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody("""{"sourceId":$sourceId,"targetId":$targetId}""")
            }

        suspend fun listedItems(): List<JsonObject> =
            Json.parseToJsonElement(list().bodyAsText()).jsonArray.map { item -> item.jsonObject }

        suspend fun listedPositions(): List<Pair<Long, Int>> =
            listedItems().map { item ->
                item.getValue("id").jsonPrimitive.long to
                    item.getValue("position").jsonPrimitive.content.toInt()
            }

        suspend fun listedExampleImages(): List<String?> =
            listedItems().map { item ->
                item.getValue("exampleImageFilename").jsonPrimitive.contentOrNull
            }

        suspend fun listedVariantCounts(): List<Int> =
            listedItems().map { item ->
                item.getValue("variantCount").jsonPrimitive.content.toInt()
            }

        suspend fun listedSupplierNames(): List<String?> =
            listedItems().map { item -> item.getValue("supplierName").jsonPrimitive.contentOrNull }
    }

    private companion object {
        const val BASE_PATH = "/api/admin/articles/mugs"
        const val FIRST_IMAGE = RecordingPublicImageStorage.FIRST_FILENAME
        const val SECOND_IMAGE = RecordingPublicImageStorage.SECOND_FILENAME

        /** The mugs, their variants, and the two category levels — four statements, always. */
        const val LIST_STATEMENT_COUNT = 4

        val DOCUMENTED_LIST =
            """
            [
              {
                "id": 1,
                "position": 1,
                "name": "Classic mug",
                "active": true,
                "categoryId": 1,
                "categoryName": "Mugs",
                "subcategoryId": 1,
                "subcategoryName": "Classic",
                "supplierId": 1,
                "supplierName": "Porcelain Ltd",
                "variantCount": 2,
                "exampleImageFilename": "$FIRST_IMAGE"
              },
              {
                "id": 2,
                "position": 2,
                "name": "Draft mug",
                "active": false,
                "categoryId": null,
                "categoryName": null,
                "subcategoryId": null,
                "subcategoryName": null,
                "supplierId": null,
                "supplierName": null,
                "variantCount": 0,
                "exampleImageFilename": null
              }
            ]
            """
                .trimIndent()

        /** The detail without its price, whose shape the pricing module owns. */
        val DOCUMENTED_DETAIL =
            """
            {
              "id": 1,
              "position": 1,
              "name": "Classic mug",
              "descriptionShort": "Short",
              "descriptionLong": "Long",
              "active": true,
              "categoryId": 1,
              "subcategoryId": 1,
              "supplierId": 1,
              "supplierArticleName": "Classic 300",
              "supplierArticleNumber": "4711",
              "printAspectRatio": "16:9",
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
              "mugVariants": [
                {
                  "id": 4,
                  "name": "Zebra",
                  "insideColorCode": "#fff",
                  "outsideColorCode": "#000",
                  "isDefault": true,
                  "active": true,
                  "exampleImageFilename": "$FIRST_IMAGE"
                },
                {
                  "id": 3,
                  "name": "Amber",
                  "insideColorCode": "#fb0",
                  "outsideColorCode": "#fb0",
                  "isDefault": false,
                  "active": true,
                  "exampleImageFilename": null
                },
                {
                  "id": 1,
                  "name": "Blue",
                  "insideColorCode": "#00f",
                  "outsideColorCode": "#00f",
                  "isDefault": false,
                  "active": true,
                  "exampleImageFilename": null
                },
                {
                  "id": 2,
                  "name": "Blue",
                  "insideColorCode": "#00e",
                  "outsideColorCode": "#00e",
                  "isDefault": false,
                  "active": false,
                  "exampleImageFilename": null
                }
              ]
            }
            """
                .trimIndent()

        val DOCUMENTED_DETAIL_FIELDS =
            Json.parseToJsonElement(DOCUMENTED_DETAIL).jsonObject.keys + "price"
    }
}
