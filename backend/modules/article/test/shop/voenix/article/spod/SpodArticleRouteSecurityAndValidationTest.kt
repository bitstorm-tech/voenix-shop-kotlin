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
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import shop.voenix.article.ReorderInput
import shop.voenix.article.antiforgeryToken
import shop.voenix.article.assertApiError
import shop.voenix.article.validateArticleRequests
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.operation.OperationResult

/**
 * What the SPOD article routes answer, against stubbed operations: the security subtree, the id
 * binding, the validation that runs before any operation, and the mapping of every result the
 * operations can produce.
 *
 * Three questions are about what is *gone*. The create route and the two pre-uploads do not exist,
 * because an article and its pictures come from the Spreadconnect backoffice; the old t-shirt paths
 * answer nothing at all; and the update body carries the shop's half of the article alone, so a
 * client that still sends the partner's half — or a print frame — is answered without it ever
 * reaching the write path.
 */
internal class SpodArticleRouteSecurityAndValidationTest {
    @Test
    fun `admin subtree rejects before id binding or article operations`() = testApplication {
        val articles = StubSpodArticleOperations()
        application { installSpodTestApplication(articles) }

        listOf(
                client.get(BASE_PATH),
                client.get("$BASE_PATH/1"),
                client.put("$BASE_PATH/1"),
                client.put("$BASE_PATH/not-a-long"),
                client.put("$BASE_PATH/order"),
                client.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(0, articles.operationCalls)

        val customer = signedInClient("CUSTOMER")
        listOf(
                customer.get(BASE_PATH),
                customer.get("$BASE_PATH/1"),
                customer.put("$BASE_PATH/1"),
                customer.put("$BASE_PATH/order"),
                customer.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Forbidden, response.status) }
        assertEquals(0, articles.operationCalls)

        val admin = signedInClient("ADMIN")
        listOf(
                admin.put("$BASE_PATH/1"),
                admin.put("$BASE_PATH/order"),
                admin.delete("$BASE_PATH/1"),
            )
            .forEach { response ->
                assertApiError(response, HttpStatusCode.BadRequest, "Invalid CSRF token")
            }

        val token = antiforgeryToken(admin)
        assertApiError(
            admin.delete("$BASE_PATH/not-a-long") { header(AuthRouting.CSRF_HEADER, token) },
            HttpStatusCode.BadRequest,
            "Invalid article id",
        )
        // Reading needs no CSRF token, so the id binding is what rejects this one.
        assertApiError(
            admin.get("$BASE_PATH/not-a-long"),
            HttpStatusCode.BadRequest,
            "Invalid article id",
        )
        assertEquals(0, articles.operationCalls)
    }

    /**
     * The three routes a synced article has no use for: it is not created here, and none of its
     * pictures is uploaded here. An admin with a valid token gets nothing from them.
     *
     * The two answers are both Ktor describing the tree correctly. The base path is still a
     * resource — it answers `GET` — so a `POST` to it is a method it does not have; the two upload
     * paths are no paths at all any more.
     */
    @Test
    fun `creating an article and uploading its pictures are no routes`() = testApplication {
        val articles = StubSpodArticleOperations()
        application { installSpodTestApplication(articles) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        listOf(
                BASE_PATH to HttpStatusCode.MethodNotAllowed,
                "$BASE_PATH/variant-example-images" to HttpStatusCode.NotFound,
                "$BASE_PATH/size-charts" to HttpStatusCode.NotFound,
            )
            .forEach { (path, expected) ->
                val response =
                    admin.post(path) {
                        header(AuthRouting.CSRF_HEADER, token)
                        contentType(ContentType.Application.Json)
                        setBody(VALID_BODY)
                    }
                assertEquals(expected, response.status, "POST $path")
            }
        assertEquals(0, articles.operationCalls)
    }

    /** The article type is `SPOD` now, and the t-shirt paths went with the `TSHIRT` type. */
    @Test
    fun `the t-shirt paths of the old type are gone`() = testApplication {
        val articles = StubSpodArticleOperations()
        application { installSpodTestApplication(articles) }
        val admin = signedInClient("ADMIN")

        assertEquals(HttpStatusCode.NotFound, admin.get("/api/admin/articles/tshirts").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/admin/articles/tshirts/7").status)
        assertEquals(0, articles.operationCalls)
    }

    @Test
    fun `the read routes answer without a csrf token and map their results`() = testApplication {
        val articles = StubSpodArticleOperations()
        application { installSpodTestApplication(articles) }
        val admin = signedInClient("ADMIN")

        val listed = admin.get(BASE_PATH)
        assertEquals(HttpStatusCode.OK, listed.status)
        // The list is a bare array, not an object with an `items` member.
        assertEquals(
            listOf(42L),
            Json.parseToJsonElement(listed.bodyAsText()).jsonArray.map { item ->
                item.jsonObject.getValue("id").jsonPrimitive.long
            },
        )
        assertEquals(1, articles.listCalls)

        val read = admin.get("$BASE_PATH/7")
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(7L, articles.lastRequestedId)
        assertEquals(
            7L,
            Json.parseToJsonElement(read.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long,
        )

        articles.getResult = OperationResult.NotFound
        assertApiError(admin.get("$BASE_PATH/7"), HttpStatusCode.NotFound, "Article not found")

        articles.getResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.get("$BASE_PATH/7"),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )

        articles.listResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.get(BASE_PATH),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    /**
     * The update body is the shop's half of the article. The HTTP runtime ignores unknown keys, so
     * a client that still sends the partner's half is not rejected — the values simply never reach
     * the operation, which is the whole point of the reduced contract.
     */
    @Test
    fun `the update body drops every field the sync owns`() = testApplication {
        val articles = StubSpodArticleOperations()
        application { installSpodTestApplication(articles) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        val updated = admin.updateArticle(token, id = 7, body = BODY_WITH_SPOD_FIELDS)
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        val input = checkNotNull(articles.lastUpdated)
        assertEquals(true, input.active)
        assertEquals(1L, input.categoryId)
        assertEquals(SpodArticleInput(active = true, categoryId = 1, defaultVariantId = 2), input)
    }

    @Test
    fun `http validation rejects an incomplete or contradictory update before operations`() =
        testApplication {
            val articles = StubSpodArticleOperations()
            application { installSpodTestApplication(articles) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            assertApiError(
                admin.updateArticle(token, id = 7, body = """{"subcategoryId":3}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf("subcategoryId" to listOf("SubcategoryId requires CategoryId")),
            )
            // The activation rules of the article as a whole both report on `active`.
            assertApiError(
                admin.updateArticle(token, id = 7, body = """{"active":true}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf(
                    "active" to
                        listOf(
                            "An active article requires an active default variant",
                            "An active article requires a category",
                        )
                ),
            )
            assertEquals(0, articles.operationCalls)
        }

    @Test
    fun `update and delete map their results to the required responses`() = testApplication {
        val articles = StubSpodArticleOperations()
        application { installSpodTestApplication(articles) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        assertEquals(HttpStatusCode.OK, admin.updateArticle(token, id = 7).status)
        assertEquals(7L, articles.lastRequestedId)

        articles.updateResult = OperationResult.NotFound
        assertApiError(
            admin.updateArticle(token, id = 7),
            HttpStatusCode.NotFound,
            "Article not found",
        )

        articles.updateResult =
            OperationResult.Invalid(
                mapOf(
                    "defaultVariantId" to
                        listOf("The default variant is not an active variant of this article")
                )
            )
        assertApiError(
            admin.updateArticle(token, id = 7),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf(
                "defaultVariantId" to
                    listOf("The default variant is not an active variant of this article")
            ),
        )

        articles.updateResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.updateArticle(token, id = 7),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )

        val deleted = admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals("", deleted.bodyAsText())

        articles.deleteResult = OperationResult.NotFound
        assertApiError(
            admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) },
            HttpStatusCode.NotFound,
            "Article not found",
        )
    }

    @Test
    fun `the reorder route is a literal segment and maps its own conflict`() = testApplication {
        val articles = StubSpodArticleOperations()
        application { installSpodTestApplication(articles) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        // `order` is not bound as an article id: the item routes never see this request.
        val reordered = admin.reorder(token, """{"sourceId":43,"targetId":42}""")
        assertEquals(HttpStatusCode.OK, reordered.status)
        assertEquals(1, articles.reorderCalls)
        assertNull(articles.lastRequestedId)
        assertEquals(43L, articles.lastReordered?.sourceId)
        assertEquals(
            listOf(42L, 43L),
            Json.parseToJsonElement(reordered.bodyAsText()).jsonArray.map { item ->
                item.jsonObject.getValue("id").jsonPrimitive.long
            },
        )

        // The two ids are checked before any operation runs.
        assertApiError(
            admin.reorder(token, """{"sourceId":1,"targetId":1}"""),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf("targetId" to listOf("TargetId must be different from SourceId")),
        )
        assertEquals(1, articles.reorderCalls)

        articles.reorderResult = OperationResult.NotFound
        assertApiError(
            admin.reorder(token, """{"sourceId":43,"targetId":42}"""),
            HttpStatusCode.NotFound,
            "Article not found",
        )

        // The one conflict of the SPOD article routes, with the stable message a client may retry
        // on.
        articles.reorderResult = OperationResult.Conflict
        assertApiError(
            admin.reorder(token, """{"sourceId":43,"targetId":42}"""),
            HttpStatusCode.Conflict,
            "Article order changed concurrently, please retry",
        )

        articles.reorderResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.reorder(token, """{"sourceId":43,"targetId":42}"""),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    private suspend fun HttpClient.updateArticle(
        token: String,
        id: Long,
        body: String = VALID_BODY,
    ): HttpResponse =
        put("$BASE_PATH/$id") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.reorder(
        token: String,
        body: String,
    ): HttpResponse =
        put("$BASE_PATH/order") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun Application.installSpodTestApplication(articles: SpodArticleOperations) {
        installHttpRuntime()
        install(RequestValidation) { validateArticleRequests() }
        installAuthModule(AuthSettings("article-spod-route-contract-session-secret"))
        installSpodArticleRoutes(articles)
        routing {
            post("/test/sign-in/{role}") {
                call.sessions.set(
                    UserSession(userId = "11", roles = setOf(checkNotNull(call.parameters["role"])))
                )
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.signedInClient(role: String): HttpClient =
        createClient {
            install(HttpCookies)
        }
        .also { client ->
            assertEquals(HttpStatusCode.OK, client.post("/test/sign-in/$role").status)
        }

    private class StubSpodArticleOperations : SpodArticleOperations {
        var listCalls = 0
        var getCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var reorderCalls = 0
        var lastRequestedId: Long? = null
        var lastUpdated: SpodArticleInput? = null
        var lastReordered: ReorderInput? = null
        var listResult: OperationResult<List<SpodArticleListItem>>? = null
        var getResult: OperationResult<SpodArticle>? = null
        var updateResult: OperationResult<SpodArticle>? = null
        var deleteResult: OperationResult<Unit>? = null
        var reorderResult: OperationResult<List<SpodArticleListItem>>? = null

        val operationCalls: Int
            get() = listCalls + getCalls + updateCalls + deleteCalls + reorderCalls

        override suspend fun list(): OperationResult<List<SpodArticleListItem>> {
            listCalls++
            return listResult ?: OperationResult.Success(listOf(listItem(42)))
        }

        override suspend fun get(id: Long): OperationResult<SpodArticle> {
            getCalls++
            lastRequestedId = id
            return getResult ?: OperationResult.Success(article(id))
        }

        override suspend fun update(
            id: Long,
            input: SpodArticleInput,
        ): OperationResult<SpodArticle> {
            updateCalls++
            lastRequestedId = id
            lastUpdated = input
            return updateResult ?: OperationResult.Success(article(id))
        }

        override suspend fun delete(id: Long): OperationResult<Unit> {
            deleteCalls++
            lastRequestedId = id
            return deleteResult ?: OperationResult.Success(Unit)
        }

        override suspend fun reorder(
            input: ReorderInput
        ): OperationResult<List<SpodArticleListItem>> {
            reorderCalls++
            lastReordered = input
            return reorderResult
                ?: OperationResult.Success(listOf(listItem(42), listItem(43, position = 2)))
        }

        private fun listItem(
            id: Long,
            position: Int = 1,
        ): SpodArticleListItem =
            SpodArticleListItem(
                id = id,
                position = position,
                name = "Classic",
                active = false,
                categoryId = null,
                categoryName = null,
                subcategoryId = null,
                subcategoryName = null,
                supplierId = 3,
                supplierName = null,
                variantCount = 0,
                exampleImageFilename = null,
                syncedAt = SYNCED_AT,
                missingAtSpreadconnect = false,
            )

        private fun article(id: Long): SpodArticle =
            SpodArticle(
                id = id,
                position = 1,
                name = "Classic",
                descriptionShort = "Short",
                descriptionLong = "Long",
                active = false,
                categoryId = null,
                subcategoryId = null,
                supplierId = 3,
                productTypeName = "T-Shirt",
                sizeChartImageFilename = null,
                printFrame = null,
                hotspot = null,
                appearances = emptyList(),
                spodVariants = emptyList(),
                price = null,
                sync =
                    SpodArticleSync(
                        spodArticleId = "spod-article-1",
                        environment = "PRODUCTION",
                        syncedAt = SYNCED_AT,
                    ),
            )
    }

    private companion object {
        const val BASE_PATH = "/api/admin/articles/spod"
        val SYNCED_AT: Instant = Instant.parse("2026-08-24T09:00:00Z")

        /**
         * A body every field rule accepts, so that only the route's own behaviour is under test.
         */
        const val VALID_BODY = """{"active":false}"""

        /**
         * An update with the partner's half of the article and a print frame added to it, as an
         * admin client written against the old t-shirt contract would still send it.
         */
        const val BODY_WITH_SPOD_FIELDS =
            """{"name":"Renamed by hand","descriptionShort":"Short","descriptionLong":"Long",""" +
                """"supplierId":9,"sizeChartImageFilename":"chart.webp",""" +
                """"spodVariants":[{"colorName":"Black","sizeLabel":"M"}],""" +
                """"active":true,"categoryId":1,"defaultVariantId":2,""" +
                """"printAspectRatio":"16:9",""" +
                """"printFrame":{"leftPct":25,"topPct":20,"widthPct":50,"heightPct":40}}"""
    }
}
