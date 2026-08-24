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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleTestSchema
import shop.voenix.article.RecordingPublicImageStorage
import shop.voenix.article.RecordingSupplierReader
import shop.voenix.article.SyncedTshirtVariant
import shop.voenix.article.SyncedTshirts
import shop.voenix.article.antiforgeryToken
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
 * The t-shirt admin slice against real Ktor routes and a real PostgreSQL database, including the
 * real pricing module.
 *
 * Since ADR 0003 a shirt has two owners, and that is what most of this file is about: the fixtures
 * insert synced shirts the way the sync will (`SyncedTshirts`), and the write path may change the
 * shop's half of such a row and nothing else. The rules that need the *stored* article — an
 * activation without a price, an activation of a shirt the partner no longer lists, and a default
 * variant that is not an active variant of this article — can only be proven here, because the
 * input rules cannot see the row.
 */
internal class TshirtArticleAdminIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the update writes the shop half and leaves the synced half alone`() {
        migratedDataSource("article-tshirt-update-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedTshirts.insert(
                dataSource,
                id = 1,
                variants = listOf(SyncedTshirtVariant(id = 10, isDefault = true)),
            )

            adminApplication(dataSource, "article-tshirt-update-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                val updated =
                    admin.updateTshirt(
                        token,
                        id = 1,
                        body =
                            shopBody(
                                active = true,
                                categoryId = 1,
                                subcategoryId = 1,
                                defaultVariantId = 10,
                                printAspectRatio = "16:9",
                                withPrice = true,
                            ),
                    )

                assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
                val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
                assertEquals("true", body.text("active"))
                assertEquals(1, body.number("categoryId"))
                assertEquals(1, body.number("subcategoryId"))
                assertEquals("16:9", body.text("printAspectRatio"))
                assertEquals(25.0, body.getValue("printFrame").jsonObject.decimal("leftPct"))

                // The partner's half of the row is answered, not written: the body above named a
                // different name, supplier, and variant array, and none of it arrived.
                assertEquals("Shirt 1", body.text("name"))
                assertEquals(1, body.number("supplierId"))
                assertEquals(
                    listOf("Black / M"),
                    body.getValue("tshirtVariants").jsonArray.map { variant ->
                        variant.jsonObject.text("name")
                    },
                )
                val variant = body.getValue("tshirtVariants").jsonArray.single().jsonObject
                assertEquals("spod-variant-10", variant.text("spodVariantId"))
                assertEquals("true", variant.text("isDefault"))

                // The sync block says where the shirt comes from and how current it is.
                val sync = body.getValue("sync").jsonObject
                assertEquals("spod-article-1", sync.text("spodArticleId"))
                assertEquals("PRODUCTION", sync.text("environment"))
                assertEquals(SyncedTshirts.SYNCED_AT, sync.text("syncedAt"))
                assertEquals(JsonNull, sync.getValue("missingSince"))

                assertEquals(
                    listOf("Shirt 1" to 1),
                    ArticleTestSchema.orderedTshirts(dataSource),
                )
            }
        }
    }

    @Test
    fun `an omitted price keeps the stored row and a submitted one is written over it`() {
        migratedDataSource("article-tshirt-price-update-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedTshirts.insert(dataSource, id = 1)

            adminApplication(
                dataSource,
                "article-tshirt-price-update-integration-session-secret",
            ) { admin, _ ->
                val token = antiforgeryToken(admin)

                val minted = admin.updateTshirt(token, id = 1, body = shopBody(withPrice = true))
                assertEquals(HttpStatusCode.OK, minted.status, minted.bodyAsText())
                val priceId = ArticleTestSchema.storedPriceIds(dataSource).single()

                val withoutPrice = admin.updateTshirt(token, id = 1, body = shopBody())
                assertEquals(HttpStatusCode.OK, withoutPrice.status, withoutPrice.bodyAsText())
                val kept = Json.parseToJsonElement(withoutPrice.bodyAsText()).jsonObject
                assertEquals(priceId, kept.getValue("price").jsonObject.number("id").toLong())

                val withPrice =
                    admin.updateTshirt(
                        token,
                        id = 1,
                        body = shopBody(withPrice = true, salesTotalInputCents = 2500),
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
            SyncedTshirts.insert(dataSource, id = 1)

            adminApplication(dataSource, "article-tshirt-references-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                assertFieldError(
                    admin.updateTshirt(token, id = 1, body = shopBody(categoryId = 404)),
                    "categoryId",
                    "Article category does not exist",
                )
                // The subcategory exists, but not inside the submitted category.
                assertFieldError(
                    admin.updateTshirt(
                        token,
                        id = 1,
                        body = shopBody(categoryId = 2, subcategoryId = 1),
                    ),
                    "subcategoryId",
                    "Article subcategory does not exist in this article category",
                )
            }
        }
    }

    /**
     * The three refusals that need the stored article. None of them is reachable through the input
     * rules, because each one is a fact about the row rather than about the body.
     */
    @Test
    fun `activation needs a price, a present article, and an active default variant`() {
        migratedDataSource("article-tshirt-activation-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedTshirts.insert(
                dataSource,
                id = 1,
                variants =
                    listOf(
                        SyncedTshirtVariant(id = 10, isDefault = true),
                        SyncedTshirtVariant(
                            id = 11,
                            sizeLabel = "L",
                            spodSizeId = 92,
                            active = false,
                        ),
                    ),
            )
            SyncedTshirts.insert(
                dataSource,
                id = 2,
                missingSince = "2026-08-24T10:00:00Z",
                variants = listOf(SyncedTshirtVariant(id = 20, isDefault = true)),
            )

            adminApplication(dataSource, "article-tshirt-activation-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)
                val active = shopBody(active = true, categoryId = 1, defaultVariantId = 10)

                assertFieldError(
                    admin.updateTshirt(token, id = 1, body = active),
                    "price",
                    "An active article requires a price",
                )

                // A variant of another article, an inactive one, and one that does not exist are
                // the same answer: the client may only name an active variant of this shirt.
                listOf(20L, 11L, 404L).forEach { variantId ->
                    assertFieldError(
                        admin.updateTshirt(
                            token,
                            id = 1,
                            body =
                                shopBody(
                                    active = true,
                                    categoryId = 1,
                                    defaultVariantId = variantId,
                                    withPrice = true,
                                ),
                        ),
                        "defaultVariantId",
                        "The default variant is not an active variant of this article",
                    )
                }

                // The shirt the partner no longer lists cannot be switched on again.
                assertFieldError(
                    admin.updateTshirt(
                        token,
                        id = 2,
                        body =
                            shopBody(
                                active = true,
                                categoryId = 1,
                                defaultVariantId = 20,
                                withPrice = true,
                            ),
                    ),
                    "active",
                    "An article that is missing at Spreadconnect cannot be activated",
                )
                assertEquals(emptyList(), ArticleTestSchema.storedPriceIds(dataSource))
            }
        }
    }

    /**
     * The default variant is the one thing about the variant array the shop decides, and moving it
     * passes through a state the partial unique index forbids, so it is written in two statements.
     */
    @Test
    fun `the default variant moves from one variant to another`() {
        migratedDataSource("article-tshirt-default-variant-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedTshirts.insert(
                dataSource,
                id = 1,
                variants =
                    listOf(
                        SyncedTshirtVariant(id = 10, isDefault = true),
                        SyncedTshirtVariant(id = 11, sizeLabel = "L", spodSizeId = 92),
                    ),
            )

            adminApplication(dataSource, "article-tshirt-default-variant-session-secret") { admin, _
                ->
                val token = antiforgeryToken(admin)

                assertEquals(
                    HttpStatusCode.OK,
                    admin
                        .updateTshirt(token, id = 1, body = shopBody(defaultVariantId = 11))
                        .status,
                )
                assertEquals(
                    listOf("Black / M" to false, "Black / L" to true),
                    ArticleTestSchema.storedTshirtVariantDefaults(dataSource, articleId = 1),
                )

                // And a body that names none leaves the shirt without a default.
                assertEquals(
                    HttpStatusCode.OK,
                    admin.updateTshirt(token, id = 1, body = shopBody()).status,
                )
                assertEquals(
                    listOf("Black / M" to false, "Black / L" to false),
                    ArticleTestSchema.storedTshirtVariantDefaults(dataSource, articleId = 1),
                )
            }
        }
    }

    @Test
    fun `the list reports how current a shirt is and whether the partner still has it`() {
        migratedDataSource("article-tshirt-list-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedTshirts.insert(dataSource, id = 1)
            SyncedTshirts.insert(dataSource, id = 2, missingSince = "2026-08-24T10:00:00Z")

            adminApplication(dataSource, "article-tshirt-list-integration-session-secret") {
                admin,
                _ ->
                val listed = admin.get(BASE_PATH)
                assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
                assertEquals(
                    listOf(
                        SyncedTshirts.SYNCED_AT to "false",
                        SyncedTshirts.SYNCED_AT to "true",
                    ),
                    Json.parseToJsonElement(listed.bodyAsText()).jsonArray.map { item ->
                        item.jsonObject.text("syncedAt") to
                            item.jsonObject.text("missingAtSpreadconnect")
                    },
                )
                // The supplier of a synced shirt is the one behind its destination, and it is
                // labelled from the one batched lookup the list performs.
                assertEquals(
                    listOf("Spreadconnect", "Spreadconnect"),
                    Json.parseToJsonElement(listed.bodyAsText()).jsonArray.map { item ->
                        item.jsonObject.text("supplierName")
                    },
                )
            }
        }
    }

    @Test
    fun `delete removes the article, its variants, its price, and its files`() {
        migratedDataSource("article-tshirt-delete-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedTshirts.insert(
                dataSource,
                id = 1,
                sizeChartImageFilename = THIRD_IMAGE,
                variants =
                    listOf(
                        SyncedTshirtVariant(id = 10, exampleImageFilename = FIRST_IMAGE),
                        SyncedTshirtVariant(
                            id = 11,
                            sizeLabel = "L",
                            spodSizeId = 92,
                            exampleImageFilename = SECOND_IMAGE,
                        ),
                    ),
            )
            SyncedTshirts.insert(dataSource, id = 2, name = "Second tee")

            adminApplication(dataSource, "article-tshirt-delete-integration-session-secret") {
                admin,
                images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE, SECOND_IMAGE, THIRD_IMAGE)
                assertEquals(
                    HttpStatusCode.OK,
                    admin.updateTshirt(token, id = 1, body = shopBody(withPrice = true)).status,
                )

                val deleted =
                    admin.delete("$BASE_PATH/1") { header(AuthRouting.CSRF_HEADER, token) }
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
            (1..3).forEach { number ->
                SyncedTshirts.insert(dataSource, id = number.toLong(), name = "Tee $number")
            }

            adminApplication(dataSource, "article-tshirt-reorder-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

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
                    admin.updateTshirt(token, id = 404, body = shopBody()),
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
        SyncedTshirts.seedSpodDestination(dataSource)
    }

    /**
     * The shop's half of a shirt, as the reduced contract carries it. Every body also names the
     * partner's half, because every one of these tests is entitled to prove that it is ignored.
     */
    private fun shopBody(
        active: Boolean = false,
        categoryId: Long? = null,
        subcategoryId: Long? = null,
        defaultVariantId: Long? = null,
        printAspectRatio: String? = null,
        withPrice: Boolean = false,
        salesTotalInputCents: Int = 1000,
    ): String {
        val fields =
            listOfNotNull(
                    categoryId?.let { value -> ""","categoryId":$value""" },
                    subcategoryId?.let { value -> ""","subcategoryId":$value""" },
                    defaultVariantId?.let { value -> ""","defaultVariantId":$value""" },
                    printAspectRatio?.let { value -> ""","printAspectRatio":"$value"""" },
                    if (withPrice) {
                        ""","price":{"purchaseVatId":1,"salesVatId":1,""" +
                            """"purchasePriceInputCents":500,""" +
                            """"salesTotalInputCents":$salesTotalInputCents}"""
                    } else {
                        null
                    },
                )
                .joinToString("")
        return """{"active":$active,$PRINT_FRAME$fields,$SPOD_OWNED_FIELDS}"""
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
                RecordingSupplierReader(mapOf(1L to "Spreadconnect")),
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
        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Validation failed", body.text("message"))
        val errors =
            body.getValue("errors").jsonObject.mapValues { (_, messages) ->
                messages.jsonArray.map { entry -> entry.jsonPrimitive.content }
            }
        assertTrue(field in errors, "Expected an error on $field but got $errors")
        assertEquals(listOf(message), errors[field])
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
            """"printFrame":{"leftPct":25,"topPct":20,"widthPct":50,"heightPct":40}"""

        /** The partner's half of the article, which every body sends and no write path reads. */
        const val SPOD_OWNED_FIELDS =
            """"name":"Renamed by hand","descriptionShort":"Retyped","supplierId":404,""" +
                """"sizeChartImageFilename":"typed.webp","tshirtVariants":[]"""
    }
}
