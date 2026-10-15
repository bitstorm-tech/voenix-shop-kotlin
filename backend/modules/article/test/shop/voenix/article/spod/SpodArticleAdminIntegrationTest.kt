package shop.voenix.article.spod

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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleTestSchema
import shop.voenix.article.RecordingPublicImageStorage
import shop.voenix.article.RecordingSupplierReader
import shop.voenix.article.SyncedSpodArticles
import shop.voenix.article.SyncedSpodVariant
import shop.voenix.article.antiforgeryToken
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
 * The SPOD article admin slice against real Ktor routes and a real PostgreSQL database, including
 * the real pricing module.
 *
 * A SPOD article has two owners, and that is what most of this file is about: the fixtures insert
 * synced articles the way the sync does (`SyncedSpodArticles`), and the write path may change the
 * shop's half of such a row and nothing else. The rules that need the *stored* article — an
 * activation without a price, without a measured print frame, or of an article the partner no
 * longer lists, and a default variant that is not an active variant of this article — can only be
 * proven here, because the input rules cannot see the row.
 */
internal class SpodArticleAdminIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the update writes the shop half and leaves the synced half alone`() {
        migratedDataSource("article-spod-update-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedSpodArticles.insert(
                dataSource,
                id = 1,
                variants = listOf(SyncedSpodVariant(id = 10, isDefault = true)),
            )

            adminApplication(dataSource, "article-spod-update-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                val updated =
                    admin.updateArticle(
                        token,
                        id = 1,
                        body =
                            shopBody(
                                active = true,
                                categoryId = 1,
                                subcategoryId = 1,
                                defaultVariantId = 10,
                                withPrice = true,
                            ),
                    )

                assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
                val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
                assertEquals("true", body.text("active"))
                assertEquals(1, body.number("categoryId"))
                assertEquals(1, body.number("subcategoryId"))
                // The measured frame and its hotspot are the sync's: the body's frame never
                // arrived.
                assertEquals(
                    Json.parseToJsonElement(
                        """{"leftPct":25.0,"topPct":20.0,"widthPct":50.0,"heightPct":40.0}"""
                    ),
                    body.getValue("printFrame"),
                )
                assertEquals("MEDIUM_FRONT", body.text("hotspot"))
                assertEquals("T-Shirt", body.text("productTypeName"))

                // The partner's half of the row is answered, not written: the body above named a
                // different name, supplier, and variant array, and none of it arrived.
                assertEquals("Shirt 1", body.text("name"))
                assertEquals(1, body.number("supplierId"))
                assertEquals(
                    listOf("Black / M"),
                    body.getValue("spodVariants").jsonArray.map { variant ->
                        variant.jsonObject.text("name")
                    },
                )
                val variant = body.getValue("spodVariants").jsonArray.single().jsonObject
                assertEquals("spod-variant-10", variant.text("spodVariantId"))
                assertEquals("true", variant.text("isDefault"))
                assertEquals(5, variant.number("spodAppearanceId"))
                // The colour is a row of its own, and the variant names it.
                val appearance = body.getValue("appearances").jsonArray.single().jsonObject
                assertEquals(variant.number("appearanceId"), appearance.number("id"))
                assertEquals("Black", appearance.text("colorName"))
                assertEquals("#000000", appearance.text("colorHex"))
                assertEquals(1, appearance.number("position"))
                assertEquals(JsonNull, appearance.getValue("mockupImageFilename"))

                // The sync block says where the shirt comes from and how current it is.
                val sync = body.getValue("sync").jsonObject
                assertEquals("spod-article-1", sync.text("spodArticleId"))
                assertEquals("PRODUCTION", sync.text("environment"))
                assertEquals(SyncedSpodArticles.SYNCED_AT, sync.text("syncedAt"))
                assertEquals(JsonNull, sync.getValue("missingSince"))

                assertEquals(
                    listOf("Shirt 1" to 1),
                    ArticleTestSchema.orderedSpodArticles(dataSource),
                )
            }
        }
    }

    @Test
    fun `an omitted price keeps the stored row and a submitted one is written over it`() {
        migratedDataSource("article-spod-price-update-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedSpodArticles.insert(dataSource, id = 1)

            adminApplication(
                dataSource,
                "article-spod-price-update-integration-session-secret",
            ) { admin, _ ->
                val token = antiforgeryToken(admin)

                val minted = admin.updateArticle(token, id = 1, body = shopBody(withPrice = true))
                assertEquals(HttpStatusCode.OK, minted.status, minted.bodyAsText())
                val priceId = ArticleTestSchema.storedPriceIds(dataSource).single()

                val withoutPrice = admin.updateArticle(token, id = 1, body = shopBody())
                assertEquals(HttpStatusCode.OK, withoutPrice.status, withoutPrice.bodyAsText())
                val kept = Json.parseToJsonElement(withoutPrice.bodyAsText()).jsonObject
                assertEquals(priceId, kept.getValue("price").jsonObject.number("id").toLong())

                val withPrice =
                    admin.updateArticle(
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
        migratedDataSource("article-spod-references-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedSpodArticles.insert(dataSource, id = 1)

            adminApplication(dataSource, "article-spod-references-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                assertFieldError(
                    admin.updateArticle(token, id = 1, body = shopBody(categoryId = 404)),
                    "categoryId",
                    "Article category does not exist",
                )
                // The subcategory exists, but not inside the submitted category.
                assertFieldError(
                    admin.updateArticle(
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
     * An article no sync run has measured a frame for yet cannot be shown: the storefront could not
     * place a motif on it, and the database would refuse the row as well.
     */
    @Test
    fun `an article without a measured print frame cannot be activated`() {
        migratedDataSource("article-spod-unmeasured-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedSpodArticles.insert(
                dataSource,
                id = 1,
                measured = false,
                variants = listOf(SyncedSpodVariant(id = 10, isDefault = true)),
            )

            adminApplication(dataSource, "article-spod-unmeasured-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)
                val activation =
                    shopBody(
                        active = true,
                        categoryId = 1,
                        defaultVariantId = 10,
                        withPrice = true,
                    )

                assertFieldError(
                    admin.updateArticle(token, id = 1, body = activation),
                    "active",
                    "An article without a measured print frame cannot be activated",
                )
                // The same article may still be filed and priced while it waits for its frame.
                val draft =
                    admin.updateArticle(
                        token,
                        id = 1,
                        body = shopBody(categoryId = 1, defaultVariantId = 10, withPrice = true),
                    )
                assertEquals(HttpStatusCode.OK, draft.status, draft.bodyAsText())
                val body = Json.parseToJsonElement(draft.bodyAsText()).jsonObject
                assertEquals(JsonNull, body.getValue("printFrame"))
                assertEquals(JsonNull, body.getValue("hotspot"))
            }
        }
    }

    /**
     * The three refusals that need the stored article. None of them is reachable through the input
     * rules, because each one is a fact about the row rather than about the body.
     */
    @Test
    fun `activation needs a price, a present article, and an active default variant`() {
        migratedDataSource("article-spod-activation-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedSpodArticles.insert(
                dataSource,
                id = 1,
                variants =
                    listOf(
                        SyncedSpodVariant(id = 10, isDefault = true),
                        SyncedSpodVariant(
                            id = 11,
                            sizeLabel = "L",
                            spodSizeId = 92,
                            active = false,
                        ),
                    ),
            )
            SyncedSpodArticles.insert(
                dataSource,
                id = 2,
                missingSince = "2026-08-24T10:00:00Z",
                variants = listOf(SyncedSpodVariant(id = 20, isDefault = true)),
            )

            adminApplication(dataSource, "article-spod-activation-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)
                val active = shopBody(active = true, categoryId = 1, defaultVariantId = 10)

                assertFieldError(
                    admin.updateArticle(token, id = 1, body = active),
                    "price",
                    "An active article requires a price",
                )

                // A variant of another article, an inactive one, and one that does not exist are
                // the same answer: the client may only name an active variant of this article.
                listOf(20L, 11L, 404L).forEach { variantId ->
                    assertFieldError(
                        admin.updateArticle(
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

                // The article the partner no longer lists cannot be switched on again.
                assertFieldError(
                    admin.updateArticle(
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
        migratedDataSource("article-spod-default-variant-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedSpodArticles.insert(
                dataSource,
                id = 1,
                variants =
                    listOf(
                        SyncedSpodVariant(id = 10, isDefault = true),
                        SyncedSpodVariant(id = 11, sizeLabel = "L", spodSizeId = 92),
                    ),
            )

            adminApplication(dataSource, "article-spod-default-variant-session-secret") { admin, _
                ->
                val token = antiforgeryToken(admin)

                assertEquals(
                    HttpStatusCode.OK,
                    admin
                        .updateArticle(token, id = 1, body = shopBody(defaultVariantId = 11))
                        .status,
                )
                assertEquals(
                    listOf("Black / M" to false, "Black / L" to true),
                    ArticleTestSchema.storedSpodVariantDefaults(dataSource, articleId = 1),
                )

                // And a body that names none leaves the article without a default.
                assertEquals(
                    HttpStatusCode.OK,
                    admin.updateArticle(token, id = 1, body = shopBody()).status,
                )
                assertEquals(
                    listOf("Black / M" to false, "Black / L" to false),
                    ArticleTestSchema.storedSpodVariantDefaults(dataSource, articleId = 1),
                )
            }
        }
    }

    @Test
    fun `the list reports how current an article is and whether the partner still has it`() {
        migratedDataSource("article-spod-list-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedSpodArticles.insert(
                dataSource,
                id = 1,
                variants =
                    listOf(
                        SyncedSpodVariant(id = 10, listingImageFilename = FIRST_IMAGE),
                        SyncedSpodVariant(
                            id = 11,
                            colorName = "White",
                            colorHex = "#ffffff",
                            spodAppearanceId = 6,
                            isDefault = true,
                            listingImageFilename = SECOND_IMAGE,
                        ),
                    ),
            )
            SyncedSpodArticles.insert(dataSource, id = 2, missingSince = "2026-08-24T10:00:00Z")

            adminApplication(dataSource, "article-spod-list-integration-session-secret") { admin, _
                ->
                val listed = admin.get(BASE_PATH)
                assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
                assertEquals(
                    listOf(
                        SyncedSpodArticles.SYNCED_AT to "false",
                        SyncedSpodArticles.SYNCED_AT to "true",
                    ),
                    Json.parseToJsonElement(listed.bodyAsText()).jsonArray.map { item ->
                        item.jsonObject.text("syncedAt") to
                            item.jsonObject.text("missingAtSpreadconnect")
                    },
                )
                // The picture of a row is the first listing image of its default variant's colour.
                assertEquals(
                    listOf(SECOND_IMAGE, null),
                    Json.parseToJsonElement(listed.bodyAsText()).jsonArray.map { item ->
                        item.jsonObject.getValue("exampleImageFilename").jsonPrimitive.contentOrNull
                    },
                )
                // The supplier of a synced article is the one behind its destination, and it is
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
        migratedDataSource("article-spod-delete-test").use { dataSource ->
            seedCatalog(dataSource)
            SyncedSpodArticles.insert(
                dataSource,
                id = 1,
                sizeChartImageFilename = THIRD_IMAGE,
                variants =
                    listOf(
                        SyncedSpodVariant(id = 10, listingImageFilename = FIRST_IMAGE),
                        SyncedSpodVariant(
                            id = 11,
                            colorName = "White",
                            colorHex = "#ffffff",
                            spodAppearanceId = 6,
                            listingImageFilename = SECOND_IMAGE,
                        ),
                    ),
            )
            // The blank front image of the first colour, which the sync stores from T3 on.
            ArticleTestSchema.execute(
                dataSource,
                "INSERT INTO voenix.article_spod_images " +
                    "(appearance_id, role, position, filename) " +
                    "VALUES (1, 'MOCKUP', 1, '$MOCKUP_IMAGE')",
            )
            SyncedSpodArticles.insert(dataSource, id = 2, name = "Second tee")

            adminApplication(dataSource, "article-spod-delete-integration-session-secret") {
                admin,
                images ->
                val token = antiforgeryToken(admin)
                images.put(FIRST_IMAGE, SECOND_IMAGE, THIRD_IMAGE, MOCKUP_IMAGE)
                assertEquals(
                    HttpStatusCode.OK,
                    admin.updateArticle(token, id = 1, body = shopBody(withPrice = true)).status,
                )

                val deleted =
                    admin.delete("$BASE_PATH/1") { header(AuthRouting.CSRF_HEADER, token) }
                assertEquals(HttpStatusCode.NoContent, deleted.status)

                assertEquals(
                    listOf("Second tee" to 1),
                    ArticleTestSchema.orderedSpodArticles(dataSource),
                )
                assertEquals(emptyList(), ArticleTestSchema.storedPriceIds(dataSource))
                assertEquals(1, ArticleTestSchema.rowCount(dataSource, "article_identities"))
                assertEquals(
                    0,
                    ArticleTestSchema.rowCount(dataSource, "article_variant_identities"),
                )
                assertEquals(0, ArticleTestSchema.rowCount(dataSource, "article_spod_variants"))
                assertEquals(0, ArticleTestSchema.rowCount(dataSource, "article_spod_appearances"))
                assertEquals(0, ArticleTestSchema.rowCount(dataSource, "article_spod_images"))
                assertEquals(
                    setOf(FIRST_IMAGE, SECOND_IMAGE, THIRD_IMAGE, MOCKUP_IMAGE),
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
        migratedDataSource("article-spod-reorder-test").use { dataSource ->
            seedCatalog(dataSource)
            (1..3).forEach { number ->
                SyncedSpodArticles.insert(dataSource, id = number.toLong(), name = "Tee $number")
            }

            adminApplication(dataSource, "article-spod-reorder-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                val moved = admin.reorder(token, sourceId = 3, targetId = 1)
                assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
                assertEquals(
                    listOf("Tee 3" to 1, "Tee 1" to 2, "Tee 2" to 3),
                    ArticleTestSchema.orderedSpodArticles(dataSource),
                )

                // A writer that ignored the type anchor — a manual fix, for instance — left a gap.
                ArticleTestSchema.execute(
                    dataSource,
                    "UPDATE voenix.article_spod_articles SET position = 5 WHERE position = 3",
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
                    ArticleTestSchema.orderedSpodArticles(dataSource),
                )
            }
        }
    }

    @Test
    fun `update and delete report a missing article as not found`() {
        migratedDataSource("article-spod-missing-test").use { dataSource ->
            seedCatalog(dataSource)

            adminApplication(dataSource, "article-spod-missing-integration-session-secret") {
                admin,
                _ ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.updateArticle(token, id = 404, body = shopBody()),
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
        SyncedSpodArticles.seedSpodDestination(dataSource)
    }

    /**
     * The shop's half of an article, as the reduced contract carries it. Every body also names the
     * partner's half and a print frame, because every one of these tests is entitled to prove that
     * they are ignored.
     */
    private fun shopBody(
        active: Boolean = false,
        categoryId: Long? = null,
        subcategoryId: Long? = null,
        defaultVariantId: Long? = null,
        withPrice: Boolean = false,
        salesTotalInputCents: Int = 1000,
    ): String {
        val fields =
            listOfNotNull(
                    categoryId?.let { value -> ""","categoryId":$value""" },
                    subcategoryId?.let { value -> ""","subcategoryId":$value""" },
                    defaultVariantId?.let { value -> ""","defaultVariantId":$value""" },
                    if (withPrice) {
                        ""","price":{"purchaseVatId":1,"salesVatId":1,""" +
                            """"purchasePriceInputCents":500,""" +
                            """"salesTotalInputCents":$salesTotalInputCents}"""
                    } else {
                        null
                    },
                )
                .joinToString("")
        return """{"active":$active$fields,$SPOD_OWNED_FIELDS}"""
    }

    private suspend fun HttpClient.updateArticle(
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

    private companion object {
        const val BASE_PATH = "/api/admin/articles/spod"
        const val FIRST_IMAGE = RecordingPublicImageStorage.FIRST_FILENAME
        const val SECOND_IMAGE = RecordingPublicImageStorage.SECOND_FILENAME
        const val THIRD_IMAGE = "33333333-3333-4333-8333-333333333333.webp"
        const val MOCKUP_IMAGE = "44444444-4444-4444-8444-444444444444.webp"

        /** The partner's half of the article, which every body sends and no write path reads. */
        const val SPOD_OWNED_FIELDS =
            """"name":"Renamed by hand","descriptionShort":"Retyped","supplierId":404,""" +
                """"sizeChartImageFilename":"typed.webp","spodVariants":[],""" +
                """"printAspectRatio":"16:9",""" +
                """"printFrame":{"leftPct":1,"topPct":2,"widthPct":3,"heightPct":4}"""
    }
}
