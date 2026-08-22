package shop.voenix.article.mug

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
import kotlinx.serialization.json.contentOrNull
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
 * The mug write slice against real Ktor routes and a real PostgreSQL database, including the real
 * pricing module — which is the point of most of these tests: an article and its price are one
 * transaction, so both failure directions have to be proven, not assumed.
 */
internal class MugArticleAdminIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create appends behind the last mug and answers with the stored price`() {
        migratedDataSource("article-mug-create-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-create-session-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                val created = admin.createMug(token, completeMugBody())
                assertEquals(HttpStatusCode.Created, created.status)
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals("Classic mug", body.text("name"))
                assertEquals("Short", body.text("descriptionShort"))
                assertEquals("Long", body.text("descriptionLong"))
                assertEquals(1, body.number("position"))
                assertEquals("true", body.text("active"))
                assertEquals(1, body.number("categoryId"))
                assertEquals(1, body.number("subcategoryId"))
                assertEquals(1, body.number("supplierId"))
                assertEquals("A-1", body.text("supplierArticleName"))
                assertEquals(
                    "/api/admin/articles/mugs/${body.number("id")}",
                    created.headers[HttpHeaders.Location],
                )
                assertEquals(95, body.getValue("mugDetails").jsonObject.number("heightMm"))
                assertEquals(
                    listOf("White", "Black"),
                    body.getValue("mugVariants").jsonArray.map { it.jsonObject.text("name") },
                )
                // The default comes first, whatever order the request had.
                assertEquals(
                    "true",
                    body.getValue("mugVariants").jsonArray.first().jsonObject.text("isDefault"),
                )
                // The price was minted by this write, and the article carries no separate price id.
                assertEquals(
                    ArticleTestSchema.storedPriceIds(dataSource),
                    listOf(body.getValue("price").jsonObject.number("id").toLong()),
                )
                assertNull(body["priceId"])

                assertEquals(
                    HttpStatusCode.Created,
                    admin.createMug(token, draftMugBody("Second mug")).status,
                )
                assertEquals(
                    listOf("Classic mug" to 1, "Second mug" to 2),
                    ArticleTestSchema.orderedMugs(dataSource),
                )
            }
        }
    }

    @Test
    fun `an omitted price keeps the stored row and a submitted one is written over it`() {
        migratedDataSource("article-mug-price-update-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-price-update-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)
                val created = admin.createMug(token, completeMugBody())
                val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id")
                val priceId = ArticleTestSchema.storedPriceIds(dataSource).single()

                val withoutPrice =
                    admin.updateMug(token, id.toLong(), draftMugBody("Renamed", withPrice = false))
                assertEquals(HttpStatusCode.OK, withoutPrice.status)
                val kept = Json.parseToJsonElement(withoutPrice.bodyAsText()).jsonObject
                assertEquals("Renamed", kept.text("name"))
                assertEquals(priceId, kept.getValue("price").jsonObject.number("id").toLong())
                assertEquals(
                    1000,
                    kept.getValue("price").jsonObject.number("salesTotalInputCents"),
                )

                val withPrice =
                    admin.updateMug(
                        token,
                        id.toLong(),
                        draftMugBody("Renamed", withPrice = true, salesTotalInputCents = 2500),
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
        migratedDataSource("article-mug-references-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-references-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                assertFieldError(
                    admin.createMug(token, draftMugBody("Ghost", categoryId = 404)),
                    "categoryId",
                    "Article category does not exist",
                )
                // The subcategory exists, but not inside the submitted category.
                assertFieldError(
                    admin.createMug(
                        token,
                        draftMugBody("Ghost", categoryId = 2, subcategoryId = 1),
                    ),
                    "subcategoryId",
                    "Article subcategory does not exist in this article category",
                )
                assertFieldError(
                    admin.createMug(token, draftMugBody("Ghost", supplierId = 404)),
                    "supplierId",
                    "Supplier does not exist",
                )
                assertEquals(emptyList(), ArticleTestSchema.orderedMugs(dataSource))
            }
        }
    }

    /**
     * The cross-row invariants the database cannot check on its own, and the one it can. None of
     * them is reachable through the API — every attempt is a field error, and nothing is stored.
     */
    @Test
    fun `the api cannot store a mug that breaks an invariant`() {
        migratedDataSource("article-mug-invariants-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-invariants-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                assertFieldError(
                    admin.createMug(token, completeMugBody(active = true, withPrice = false)),
                    "price",
                    "An active article requires a price",
                )
                assertEquals(
                    listOf(
                        "An active article requires complete mug details",
                        "An active article requires at least one active variant",
                    ),
                    fieldErrors(
                        admin.createMug(token, draftMugBody("Ghost", active = true, categoryId = 1))
                    )["active"],
                )
                val withoutCategory =
                    fieldErrors(admin.createMug(token, completeMugBody(categoryId = null)))
                assertEquals(
                    listOf("An active article requires a category"),
                    withoutCategory["active"],
                )
                val twoDefaults =
                    fieldErrors(admin.createMug(token, completeMugBody(twoDefaults = true)))
                assertEquals(
                    listOf("Exactly one variant must be marked as default"),
                    twoDefaults["mugVariants"],
                )
                assertEquals(emptyList(), ArticleTestSchema.orderedMugs(dataSource))
                assertEquals(emptyList(), ArticleTestSchema.storedPriceIds(dataSource))
            }
        }
    }

    @Test
    fun `a rejected price leaves no article and a rejected article leaves no price`() {
        migratedDataSource("article-mug-atomicity-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-atomicity-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                // The price is prepared before the transaction opens, so a rejected one never
                // reaches the article.
                assertFieldError(
                    admin.createMug(token, completeMugBody(salesVatId = 404)),
                    "price.salesVatId",
                    "Sales VAT not found",
                )
                assertEquals(emptyList(), ArticleTestSchema.orderedMugs(dataSource))
                assertEquals(emptyList(), ArticleTestSchema.storedPriceIds(dataSource))

                // The other direction: the price row is written first, inside the transaction the
                // article then fails in. Nothing may survive that rollback.
                assertFieldError(
                    admin.createMug(token, completeMugBody(supplierId = 404)),
                    "supplierId",
                    "Supplier does not exist",
                )
                assertEquals(emptyList(), ArticleTestSchema.orderedMugs(dataSource))
                assertEquals(emptyList(), ArticleTestSchema.storedPriceIds(dataSource))
                assertEquals(0, ArticleTestSchema.rowCount(dataSource, "article_identities"))
                assertEquals(
                    0,
                    ArticleTestSchema.rowCount(dataSource, "article_variant_identities"),
                )

                // And the same write succeeds once the reference is right.
                assertEquals(
                    HttpStatusCode.Created,
                    admin.createMug(token, completeMugBody()).status,
                )
                assertEquals(1, ArticleTestSchema.storedPriceIds(dataSource).size)
            }
        }
    }

    @Test
    fun `delete removes the article, its variants, its price, and its files`() {
        migratedDataSource("article-mug-delete-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-delete-integration-session-secret") {
                admin,
                images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE, SECOND_IMAGE)

                val created = admin.createMug(token, completeMugBody(withVariantImages = true))
                val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id")
                assertEquals(
                    HttpStatusCode.Created,
                    admin.createMug(token, draftMugBody("Second mug")).status,
                )

                val deleted =
                    admin.delete("$BASE_PATH/$id") { header(AuthRouting.CSRF_HEADER, token) }
                assertEquals(HttpStatusCode.NoContent, deleted.status)

                assertEquals(listOf("Second mug" to 1), ArticleTestSchema.orderedMugs(dataSource))
                assertEquals(emptyList(), ArticleTestSchema.storedPriceIds(dataSource))
                assertEquals(1, ArticleTestSchema.rowCount(dataSource, "article_identities"))
                assertEquals(
                    0,
                    ArticleTestSchema.rowCount(dataSource, "article_variant_identities"),
                )
                assertEquals(0, ArticleTestSchema.rowCount(dataSource, "article_mug_variants"))
                assertEquals(setOf(FIRST_IMAGE, SECOND_IMAGE), images.deleted.toSet())
            }
        }
    }

    /**
     * The shape a mug is printed in, through the whole write slice.
     *
     * It behaves like every other field of the contract except the price: a create stores what was
     * submitted, and an update that says nothing about it writes the ratio a mug is printed in by
     * default. A ratio this shop does not print is a field error, not a body that fails to parse.
     */
    @Test
    fun `the print aspect ratio is stored, answered, and bounded`() {
        migratedDataSource("article-mug-print-ratio-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-print-ratio-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                val created = admin.createMug(token, squareMugBody("Square mug"))
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals("1:1", body.text("printAspectRatio"))

                val id = body.number("id").toLong()
                val updated = admin.updateMug(token, id, draftMugBody("Square mug"))
                assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
                assertEquals(
                    "16:9",
                    Json.parseToJsonElement(updated.bodyAsText())
                        .jsonObject
                        .text("printAspectRatio"),
                    "An update replaces every stored value, so an omitted ratio is the default one",
                )

                assertFieldError(
                    admin.createMug(token, squareMugBody("Wide-screen mug", ratio = "4:3")),
                    "printAspectRatio",
                    "PrintAspectRatio must be one of 16:9, 1:1",
                )
            }
        }
    }

    /**
     * The legacy contract received a variant's `active` as a plain flag, so an omitted one means
     * "not active". It is the flag the activation rule of the article reads, which is why an
     * article whose variants say nothing about visibility cannot be active.
     */
    @Test
    fun `a variant that omits active is inactive`() {
        migratedDataSource("article-mug-variant-active-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-variant-active-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                assertFieldError(
                    admin.createMug(token, completeMugBody(omitVariantActive = true)),
                    "active",
                    "An active article requires at least one active variant",
                )

                val created =
                    admin.createMug(
                        token,
                        """{"name":"Draft mug","descriptionShort":"Short",""" +
                            """"descriptionLong":"Long","mugVariants":[""" +
                            """{"name":"White","insideColorCode":"#fff",""" +
                            """"outsideColorCode":"#fff","isDefault":true}]}""",
                    )
                assertEquals(HttpStatusCode.Created, created.status)
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals(
                    "false",
                    body.getValue("mugVariants").jsonArray.single().jsonObject.text("active"),
                )
                assertEquals(
                    listOf("White" to false),
                    ArticleTestSchema.storedVariantActivations(
                        dataSource,
                        body.number("id").toLong(),
                    ),
                )
            }
        }
    }

    /**
     * The cross-row activation rules on the update path. They are unreachable there too: switching
     * a complete article on while every variant it submits is inactive is a field error, and the
     * stored article keeps the state it had.
     */
    @Test
    fun `the api cannot update a mug into a broken invariant`() {
        migratedDataSource("article-mug-update-invariants-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(
                dataSource,
                "article-mug-update-invariants-integration-session-secret",
            ) { admin, _ ->
                val token = antiforgeryToken(admin)
                val created = admin.createMug(token, completeMugBody(active = false))
                assertEquals(HttpStatusCode.Created, created.status)
                val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.number("id")

                assertFieldError(
                    admin.updateMug(
                        token,
                        id.toLong(),
                        completeMugBody(active = true, omitVariantActive = true),
                    ),
                    "active",
                    "An active article requires at least one active variant",
                )

                val stored = admin.get("$BASE_PATH/$id")
                assertEquals(HttpStatusCode.OK, stored.status)
                assertEquals(
                    "false",
                    Json.parseToJsonElement(stored.bodyAsText()).jsonObject.text("active"),
                )
            }
        }
    }

    /**
     * The price of an article belongs to it by construction: no contract accepts a price id, so a
     * body that sends one anyway must not be able to attach a foreign price row.
     */
    @Test
    fun `a submitted price id is never honored`() {
        migratedDataSource("article-mug-price-id-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-price-id-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                // A first mug so that a foreign price row exists to point at.
                admin.createMug(token, completeMugBody())
                val foreignPriceId = ArticleTestSchema.storedPriceIds(dataSource).single()

                val created =
                    admin.createMug(
                        token,
                        """{"name":"Second mug","descriptionShort":"Short",""" +
                            """"descriptionLong":"Long","priceId":$foreignPriceId}""",
                    )
                assertEquals(HttpStatusCode.Created, created.status)
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertNull(body["priceId"])
                assertEquals(null, body["price"]?.jsonPrimitive?.contentOrNull)
                assertEquals(
                    listOf(foreignPriceId),
                    ArticleTestSchema.storedPriceIds(dataSource),
                )

                // The same on update, where a stored price could be redirected.
                val id = body.number("id").toLong()
                assertEquals(
                    HttpStatusCode.OK,
                    admin
                        .updateMug(
                            token,
                            id,
                            """{"name":"Second mug","descriptionShort":"Short",""" +
                                """"descriptionLong":"Long","priceId":$foreignPriceId}""",
                        )
                        .status,
                )
                assertNull(priceIdOf(dataSource, id), "A submitted price id must not be stored")
            }
        }
    }

    @Test
    fun `update and delete report a missing mug as not found`() {
        migratedDataSource("article-mug-missing-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-mug-missing-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.updateMug(token, id = 404, body = draftMugBody("Ghost")),
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
        ArticleTestSchema.seedCategories(dataSource, "Mugs", "Posters")
        ArticleTestSchema.seedSubcategories(dataSource, categoryId = 1, "Classic")
        ArticleTestSchema.seedSuppliers(dataSource, "Porcelain Ltd")
    }

    private fun completeMugBody(
        active: Boolean = true,
        categoryId: Long? = 1,
        supplierId: Long = 1,
        salesVatId: Long = 1,
        withPrice: Boolean = true,
        withVariantImages: Boolean = false,
        twoDefaults: Boolean = false,
        omitVariantActive: Boolean = false,
    ): String {
        val variantActive = if (omitVariantActive) "" else ""","active":true"""
        val category =
            if (categoryId == null) "" else ""","categoryId":$categoryId,"subcategoryId":1"""
        val firstImage = if (withVariantImages) ""","exampleImageFilename":"$FIRST_IMAGE"""" else ""
        val secondImage =
            if (withVariantImages) ""","exampleImageFilename":"$SECOND_IMAGE"""" else ""
        val price =
            if (withPrice) {
                ""","price":{"purchaseVatId":1,"salesVatId":$salesVatId,""" +
                    """"purchasePriceInputCents":500,"salesTotalInputCents":1000}"""
            } else {
                ""
            }
        return """{"name":"Classic mug","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":$active$category,"supplierId":$supplierId,""" +
            """"supplierArticleName":"A-1","supplierArticleNumber":"4711",""" +
            """"mugDetails":{"heightMm":95,"diameterMm":82,"printTemplateWidthMm":200,""" +
            """"printTemplateHeightMm":90,"dishwasherSafe":true,"fillingQuantity":"300 ml"},""" +
            """"mugVariants":[""" +
            """{"name":"Black","insideColorCode":"#000","outsideColorCode":"#000",""" +
            """"isDefault":$twoDefaults$variantActive$secondImage},""" +
            """{"name":"White","insideColorCode":"#fff","outsideColorCode":"#fff",""" +
            """"isDefault":true$variantActive$firstImage}]$price}"""
    }

    /** A draft that asks to be printed in [ratio] instead of the default one. */
    private fun squareMugBody(
        name: String,
        ratio: String = "1:1",
    ): String =
        """{"name":"$name","descriptionShort":"Short","descriptionLong":"Long",""" +
            """"active":false,"printAspectRatio":"$ratio"}"""

    private fun draftMugBody(
        name: String,
        active: Boolean = false,
        categoryId: Long? = null,
        subcategoryId: Long? = null,
        supplierId: Long? = null,
        withPrice: Boolean = false,
        salesTotalInputCents: Int = 1000,
    ): String {
        val references =
            listOfNotNull(
                    categoryId?.let { value -> ""","categoryId":$value""" },
                    subcategoryId?.let { value -> ""","subcategoryId":$value""" },
                    supplierId?.let { value -> ""","supplierId":$value""" },
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
            """"active":$active$references$price}"""
    }

    private fun priceIdOf(
        dataSource: DataSource,
        articleId: Long,
    ): Long? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement("SELECT price_id FROM voenix.article_mugs WHERE id = $articleId")
                .use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getLong("price_id").takeUnless { rows.wasNull() }
                    }
                }
        }

    private suspend fun HttpClient.createMug(
        token: String,
        body: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.updateMug(
        token: String,
        id: Long,
        body: String,
    ): HttpResponse =
        put("$BASE_PATH/$id") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
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
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Validation failed", body.text("message"))
        return body.getValue("errors").jsonObject.mapValues { (_, messages) ->
            messages.jsonArray.map { message -> message.jsonPrimitive.content }
        }
    }

    private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

    private fun JsonObject.number(field: String): Int = text(field).toInt()

    private companion object {
        const val BASE_PATH = "/api/admin/articles/mugs"
        const val FIRST_IMAGE = RecordingPublicImageStorage.FIRST_FILENAME
        const val SECOND_IMAGE = RecordingPublicImageStorage.SECOND_FILENAME
    }
}
