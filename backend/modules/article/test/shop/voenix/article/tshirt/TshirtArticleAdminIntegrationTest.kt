package shop.voenix.article.tshirt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleTestSchema
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
 * The t-shirt write slice against real Ktor routes and a real PostgreSQL database, including the
 * real pricing module — which is the point of several of these tests: an article and its price are
 * one transaction, so both failure directions have to be proven, not assumed.
 *
 * The other half is what a shirt has and a mug has not: a variant that is named by its colour and
 * its size instead of storing a name, three printer ids that must agree across the article, a print
 * frame that is stored with two decimals, and a size chart image whose lifecycle is the article's
 * rather than a variant's.
 */
internal class TshirtArticleAdminIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create appends behind the last shirt and answers with the stored price`() {
        migratedDataSource("article-tshirt-create-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-tshirt-create-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                val created = admin.createTshirt(token, completeBody())
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals("Classic tee", body.text("name"))
                assertEquals(1, body.number("position"))
                assertEquals("true", body.text("active"))
                assertEquals(1, body.number("categoryId"))
                assertEquals(1, body.number("subcategoryId"))
                assertEquals(1, body.number("supplierId"))
                assertEquals(
                    "/api/admin/articles/tshirts/${body.number("id")}",
                    created.headers[HttpHeaders.Location],
                )
                // A shirt that says nothing about its ratio is printed square.
                assertEquals("1:1", body.text("printAspectRatio"))
                assertEquals(25.0, body.getValue("printFrame").jsonObject.decimal("leftPct"))
                assertEquals(40.5, body.getValue("printFrame").jsonObject.decimal("heightPct"))

                // The variant name is composed from the colour and the size, and the default comes
                // first whatever order the request had.
                assertEquals(
                    listOf("Black / M", "White / L"),
                    body.getValue("tshirtVariants").jsonArray.map { variant ->
                        variant.jsonObject.text("name")
                    },
                )
                assertEquals(
                    "true",
                    body.getValue("tshirtVariants").jsonArray.first().jsonObject.text("isDefault"),
                )
                assertEquals(
                    812,
                    body
                        .getValue("tshirtVariants")
                        .jsonArray
                        .first()
                        .jsonObject
                        .number("spodProductTypeId"),
                )

                // The price was minted by this write, and the article carries no separate price id.
                assertEquals(
                    ArticleTestSchema.storedPriceIds(dataSource),
                    listOf(body.getValue("price").jsonObject.number("id").toLong()),
                )
                assertNull(body["priceId"])

                assertEquals(
                    HttpStatusCode.Created,
                    admin.createTshirt(token, draftBody("Second tee")).status,
                )
                assertEquals(
                    listOf("Classic tee" to 1, "Second tee" to 2),
                    ArticleTestSchema.orderedTshirts(dataSource),
                )
            }
        }
    }

    @Test
    fun `an omitted price keeps the stored row and a submitted one is written over it`() {
        migratedDataSource("article-tshirt-price-update-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(
                dataSource,
                "article-tshirt-price-update-integration-session-secret",
            ) { admin, _ ->
                val token = antiforgeryToken(admin)
                val created = admin.createTshirt(token, completeBody())
                val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id")
                val priceId = ArticleTestSchema.storedPriceIds(dataSource).single()

                val withoutPrice =
                    admin.updateTshirt(token, id.toLong(), draftBody("Renamed", withPrice = false))
                assertEquals(HttpStatusCode.OK, withoutPrice.status, withoutPrice.bodyAsText())
                val kept = Json.parseToJsonElement(withoutPrice.bodyAsText()).jsonObject
                assertEquals("Renamed", kept.text("name"))
                assertEquals(priceId, kept.getValue("price").jsonObject.number("id").toLong())

                val withPrice =
                    admin.updateTshirt(
                        token,
                        id.toLong(),
                        draftBody("Renamed", withPrice = true, salesTotalInputCents = 2500),
                    )
                assertEquals(HttpStatusCode.OK, withPrice.status)
                val replaced =
                    Json.parseToJsonElement(withPrice.bodyAsText()).jsonObject.getValue("price")
                // The same row is rewritten, so the id a client already knows stays valid.
                assertEquals(priceId, replaced.jsonObject.number("id").toLong())
                assertEquals(2500, replaced.jsonObject.number("salesTotalInputCents"))
                assertEquals(listOf(priceId), ArticleTestSchema.storedPriceIds(dataSource))
            }
        }
    }

    @Test
    fun `unknown references are field errors instead of conflicts`() {
        migratedDataSource("article-tshirt-references-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-tshirt-references-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                assertFieldError(
                    admin.createTshirt(token, draftBody("Ghost", categoryId = 404)),
                    "categoryId",
                    "Article category does not exist",
                )
                // The subcategory exists, but not inside the submitted category.
                assertFieldError(
                    admin.createTshirt(
                        token,
                        draftBody("Ghost", categoryId = 2, subcategoryId = 1),
                    ),
                    "subcategoryId",
                    "Article subcategory does not exist in this article category",
                )
                assertFieldError(
                    admin.createTshirt(token, draftBody("Ghost", supplierId = 404)),
                    "supplierId",
                    "Supplier does not exist",
                )
                assertEquals(emptyList(), ArticleTestSchema.orderedTshirts(dataSource))
            }
        }
    }

    @Test
    fun `a rejected price leaves no article and a rejected article leaves no price`() {
        migratedDataSource("article-tshirt-atomicity-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-tshirt-atomicity-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                // The price is prepared before the transaction opens, so a rejected one never
                // reaches the article.
                assertFieldError(
                    admin.createTshirt(token, completeBody(salesVatId = 404)),
                    "price.salesVatId",
                    "Sales VAT not found",
                )
                assertEquals(emptyList(), ArticleTestSchema.orderedTshirts(dataSource))
                assertEquals(emptyList(), ArticleTestSchema.storedPriceIds(dataSource))

                // The other direction: the price row is written first, inside the transaction the
                // article then fails in. Nothing may survive that rollback.
                assertFieldError(
                    admin.createTshirt(token, completeBody(supplierId = 404)),
                    "supplierId",
                    "Supplier does not exist",
                )
                assertEquals(emptyList(), ArticleTestSchema.orderedTshirts(dataSource))
                assertEquals(emptyList(), ArticleTestSchema.storedPriceIds(dataSource))
                assertEquals(0, ArticleTestSchema.rowCount(dataSource, "article_identities"))
                assertEquals(
                    0,
                    ArticleTestSchema.rowCount(dataSource, "article_variant_identities"),
                )

                // And the same write succeeds once the reference is right.
                assertEquals(
                    HttpStatusCode.Created,
                    admin.createTshirt(token, completeBody()).status,
                )
                assertEquals(1, ArticleTestSchema.storedPriceIds(dataSource).size)
            }
        }
    }

    /**
     * The cross-row invariants the database cannot check on its own, and the one it can. None of
     * them is reachable through the API — every attempt is a field error, and nothing is stored.
     */
    @Test
    fun `the api cannot store a shirt that breaks an invariant`() {
        migratedDataSource("article-tshirt-invariants-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-tshirt-invariants-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                assertFieldError(
                    admin.createTshirt(token, completeBody(withPrice = false)),
                    "price",
                    "An active article requires a price",
                )
                val withoutCategory =
                    fieldErrors(admin.createTshirt(token, completeBody(categoryId = null)))
                assertEquals(
                    listOf("An active article requires a category"),
                    withoutCategory["active"],
                )
                val twoDefaults =
                    fieldErrors(admin.createTshirt(token, completeBody(twoDefaults = true)))
                assertEquals(
                    listOf("Exactly one variant must be marked as default"),
                    twoDefaults["tshirtVariants"],
                )
                val duplicate =
                    fieldErrors(admin.createTshirt(token, completeBody(duplicateVariant = true)))
                assertEquals(
                    listOf("Each color and size combination must appear only once"),
                    duplicate["tshirtVariants"],
                )
                val mixed =
                    fieldErrors(admin.createTshirt(token, completeBody(mixedProductTypes = true)))
                assertEquals(
                    listOf("All variants must share the same SpodProductTypeId"),
                    mixed["tshirtVariants"],
                )
                assertEquals(emptyList(), ArticleTestSchema.orderedTshirts(dataSource))
                assertEquals(emptyList(), ArticleTestSchema.storedPriceIds(dataSource))
            }
        }
    }

    @Test
    fun `delete removes the article, its variants, its price, and its files`() {
        migratedDataSource("article-tshirt-delete-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-tshirt-delete-integration-session-secret") {
                admin,
                images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE, SECOND_IMAGE, THIRD_IMAGE)

                val created =
                    admin.createTshirt(
                        token,
                        completeBody(withVariantImages = true, sizeChart = THIRD_IMAGE),
                    )
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id")
                assertEquals(
                    HttpStatusCode.Created,
                    admin.createTshirt(token, draftBody("Second tee")).status,
                )

                val deleted =
                    admin.delete("$BASE_PATH/$id") { header(AuthRouting.CSRF_HEADER, token) }
                assertEquals(HttpStatusCode.NoContent, deleted.status)

                assertEquals(
                    listOf("Second tee" to 1),
                    ArticleTestSchema.orderedTshirts(dataSource),
                )
                assertEquals(emptyList(), ArticleTestSchema.storedPriceIds(dataSource))
                assertEquals(1, ArticleTestSchema.rowCount(dataSource, "article_identities"))
                assertEquals(
                    0,
                    ArticleTestSchema.rowCount(dataSource, "article_variant_identities"),
                )
                assertEquals(0, ArticleTestSchema.rowCount(dataSource, "article_tshirt_variants"))
                assertEquals(
                    setOf(FIRST_IMAGE, SECOND_IMAGE, THIRD_IMAGE),
                    images.deleted.toSet(),
                )
            }
        }
    }

    /**
     * The reorder answers the complete new order, and it refuses a stored sequence that already had
     * a gap: repairing it would move every row a client sees although it asked to move one.
     */
    @Test
    fun `reorder answers the new order and refuses a gapped sequence`() {
        migratedDataSource("article-tshirt-reorder-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-tshirt-reorder-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)
                (1..3).forEach { number ->
                    assertEquals(
                        HttpStatusCode.Created,
                        admin.createTshirt(token, draftBody("Tee $number")).status,
                    )
                }

                val moved = admin.reorder(token, sourceId = 3, targetId = 1)
                assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
                assertEquals(
                    listOf("Tee 3" to 1, "Tee 1" to 2, "Tee 2" to 3),
                    ArticleTestSchema.orderedTshirts(dataSource),
                )

                // A writer that ignored the type anchor — a manual fix, for instance — left a gap.
                ArticleTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.article_tshirts SET position = 5 WHERE position = 3",
                )
                val refused = admin.reorder(token, sourceId = 1, targetId = 2)
                assertEquals(HttpStatusCode.Conflict, refused.status)
                assertEquals(
                    "Article order changed concurrently, please retry",
                    Json.parseToJsonElement(refused.bodyAsText()).jsonObject.text("message"),
                )
                // The broken sequence is not quietly repaired: nothing moved.
                assertEquals(
                    listOf("Tee 3" to 1, "Tee 1" to 2, "Tee 2" to 5),
                    ArticleTestSchema.orderedTshirts(dataSource),
                )
            }
        }
    }

    @Test
    fun `update and delete report a missing shirt as not found`() {
        migratedDataSource("article-tshirt-missing-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-tshirt-missing-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.updateTshirt(token, id = 404, body = draftBody("Ghost")),
                    HttpStatusCode.NotFound,
                    "Article not found",
                )
                assertApiMessage(
                    admin.delete("$BASE_PATH/404") { header(AuthRouting.CSRF_HEADER, token) },
                    HttpStatusCode.NotFound,
                    "Article not found",
                )
            }
        }
    }

    private fun seedCatalog(dataSource: DataSource) {
        ArticleTestSchema.reset(dataSource)
        ArticleTestSchema.seedVat(dataSource)
        ArticleTestSchema.seedCategories(dataSource, "Shirts", "Posters")
        ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "Classic")
        ArticleTestSchema.seedSuppliers(dataSource, "Spreadconnect")
    }

    /** An active shirt with everything an active shirt needs. */
    private fun completeBody(
        categoryId: Long? = 1,
        supplierId: Long = 1,
        salesVatId: Long = 1,
        withPrice: Boolean = true,
        withVariantImages: Boolean = false,
        sizeChart: String? = null,
        twoDefaults: Boolean = false,
        duplicateVariant: Boolean = false,
        mixedProductTypes: Boolean = false,
    ): String {
        val category =
            if (categoryId == null) "" else ""","categoryId":$categoryId,"subcategoryId":1"""
        val price =
            if (withPrice) {
                ""","price":{"purchaseVatId":1,"salesVatId":$salesVatId,""" +
                    """"purchasePriceInputCents":500,"salesTotalInputCents":1000}"""
            } else {
                ""
            }
        val second =
            when {
                duplicateVariant ->
                    variantBody("Black", "M", isDefault = twoDefaults, active = true)
                mixedProductTypes ->
                    variantBody(
                        "White",
                        "L",
                        isDefault = twoDefaults,
                        active = true,
                        productTypeId = 813,
                    )
                else ->
                    variantBody(
                        "White",
                        "L",
                        isDefault = twoDefaults,
                        active = true,
                        image = if (withVariantImages) SECOND_IMAGE else null,
                    )
            }
        val first =
            variantBody(
                "Black",
                "M",
                isDefault = true,
                active = true,
                image = if (withVariantImages) FIRST_IMAGE else null,
            )
        return """{"name":"Classic tee","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":true$category,"supplierId":$supplierId,$PRINT_FRAME""" +
            (sizeChart?.let { chart -> ""","sizeChartImageFilename":"$chart"""" } ?: "") +
            ""","tshirtVariants":[$first,$second]$price}"""
    }

    /** A draft shirt: nothing but its texts, its frame, and what the test is about. */
    private fun draftBody(
        name: String,
        categoryId: Long? = null,
        subcategoryId: Long? = null,
        supplierId: Long? = null,
        sizeChart: String? = null,
        withPrice: Boolean = false,
        salesTotalInputCents: Int = 1000,
        variants: List<String> = emptyList(),
    ): String {
        val references =
            listOfNotNull(
                    categoryId?.let { value -> ""","categoryId":$value""" },
                    subcategoryId?.let { value -> ""","subcategoryId":$value""" },
                    supplierId?.let { value -> ""","supplierId":$value""" },
                    sizeChart?.let { chart -> ""","sizeChartImageFilename":"$chart"""" },
                )
                .joinToString("")
        val price =
            if (withPrice) {
                ""","price":{"purchaseVatId":1,"salesVatId":1,"purchasePriceInputCents":500,""" +
                    """"salesTotalInputCents":$salesTotalInputCents}"""
            } else {
                ""
            }
        return """{"name":"$name","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":false$references,$PRINT_FRAME,""" +
            """"tshirtVariants":[${variants.joinToString(",")}]$price}"""
    }

    private fun variantBody(
        colorName: String,
        sizeLabel: String,
        isDefault: Boolean,
        active: Boolean = false,
        image: String? = null,
        id: Long? = null,
        productTypeId: Long = 812,
    ): String =
        """{${id?.let { value -> """"id":$value,""" } ?: ""}"colorName":"$colorName",""" +
            """"colorHex":"#101010","sizeLabel":"$sizeLabel",""" +
            """"spodProductTypeId":$productTypeId,"spodAppearanceId":5,""" +
            """"spodSizeId":${sizeLabel.first().code},"isDefault":$isDefault,"active":$active""" +
            (image?.let { file -> ""","exampleImageFilename":"$file"""" } ?: "") +
            "}"

    private suspend fun HttpClient.createTshirt(
        token: String,
        body: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.updateTshirt(
        token: String,
        id: Long,
        body: String,
    ): HttpResponse =
        put("$BASE_PATH/$id") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.reorder(
        token: String,
        sourceId: Long,
        targetId: Long,
    ): HttpResponse =
        put("$BASE_PATH/order") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"sourceId":$sourceId,"targetId":$targetId}""")
        }

    /** Runs [block] against the real module installed on [dataSource], signed in as an admin. */
    private fun adminApplication(
        dataSource: DataSource,
        sessionSecret: String,
        block: suspend (HttpClient, RecordingPublicImageStorage) -> Unit,
    ) = testApplication {
        val images = RecordingPublicImageStorage()
        application {
            installHttpRuntime()
            install(RequestValidation) { validateArticleRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            val database = Database.connect(datasource = dataSource)
            installArticleModule(
                database,
                images,
                installPricingModule(database, installVatModule(database)),
                RecordingSupplierReader(),
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
        block(admin, images)
    }

    private suspend fun antiforgeryToken(client: HttpClient): String =
        Json.parseToJsonElement(client.get("/api/antiforgery/token").bodyAsText())
            .jsonObject
            .getValue("requestToken")
            .jsonPrimitive
            .content

    private suspend fun assertApiMessage(
        response: HttpResponse,
        status: HttpStatusCode,
        message: String,
    ) {
        assertEquals(status, response.status)
        assertEquals(
            message,
            Json.parseToJsonElement(response.bodyAsText()).jsonObject.text("message"),
        )
    }

    private suspend fun assertFieldError(
        response: HttpResponse,
        field: String,
        message: String,
    ) {
        val errors = fieldErrors(response)
        assertTrue(field in errors, "Expected an error on $field but got $errors")
        assertEquals(listOf(message), errors[field])
    }

    private suspend fun fieldErrors(response: HttpResponse): Map<String, List<String>> {
        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Validation failed", body.text("message"))
        return body.getValue("errors").jsonObject.mapValues { (_, messages) ->
            messages.jsonArray.map { message -> message.jsonPrimitive.content }
        }
    }

    private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

    private fun JsonObject.number(field: String): Int = text(field).toInt()

    private fun JsonObject.decimal(field: String): Double = text(field).toDouble()

    private companion object {
        const val BASE_PATH = "/api/admin/articles/tshirts"
        const val FIRST_IMAGE = RecordingPublicImageStorage.FIRST_FILENAME
        const val SECOND_IMAGE = RecordingPublicImageStorage.SECOND_FILENAME
        const val THIRD_IMAGE = "33333333-3333-4333-8333-333333333333.webp"

        /** The frame every body carries, because a shirt without one is not a described shirt. */
        const val PRINT_FRAME =
            """"printFrame":{"leftPct":25,"topPct":20,"widthPct":50,"heightPct":40.5}"""
    }
}
