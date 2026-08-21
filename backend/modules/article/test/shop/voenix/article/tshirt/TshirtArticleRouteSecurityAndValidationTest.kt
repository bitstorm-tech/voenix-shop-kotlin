package shop.voenix.article.tshirt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import shop.voenix.article.ExampleImage
import shop.voenix.article.PrintAspectRatio
import shop.voenix.article.ReorderInput
import shop.voenix.article.validateArticleRequests
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.ApiError
import shop.voenix.http.installHttpRuntime
import shop.voenix.image.ImageUpload
import shop.voenix.operation.OperationResult

/**
 * What the t-shirt routes answer, against stubbed operations: the security subtree, the id binding,
 * the validation that runs before any operation, and the mapping of every result the operations can
 * produce.
 *
 * The one route shape a mug does not have is the second pre-upload: a shirt uploads two kinds of
 * picture into two folders, so `variant-example-images` and `size-charts` are two routes that must
 * reach two different operations.
 */
internal class TshirtArticleRouteSecurityAndValidationTest {
    @Test
    fun `admin subtree rejects before id binding or tshirt operations`() = testApplication {
        val tshirts = StubTshirtArticleOperations()
        application { installTshirtTestApplication(tshirts) }

        listOf(
                client.get(BASE_PATH),
                client.get("$BASE_PATH/1"),
                client.post(BASE_PATH),
                client.post("$BASE_PATH/variant-example-images"),
                client.post("$BASE_PATH/size-charts"),
                client.put("$BASE_PATH/1"),
                client.put("$BASE_PATH/not-a-long"),
                client.put("$BASE_PATH/order"),
                client.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(0, tshirts.operationCalls)

        val customer = signedInClient("CUSTOMER")
        listOf(
                customer.get(BASE_PATH),
                customer.get("$BASE_PATH/1"),
                customer.post(BASE_PATH),
                customer.post("$BASE_PATH/variant-example-images"),
                customer.post("$BASE_PATH/size-charts"),
                customer.put("$BASE_PATH/1"),
                customer.put("$BASE_PATH/order"),
                customer.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Forbidden, response.status) }
        assertEquals(0, tshirts.operationCalls)

        val admin = signedInClient("ADMIN")
        listOf(
                admin.post(BASE_PATH),
                admin.post("$BASE_PATH/variant-example-images"),
                admin.post("$BASE_PATH/size-charts"),
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
        assertEquals(0, tshirts.operationCalls)
    }

    @Test
    fun `the read routes answer without a csrf token and map their results`() = testApplication {
        val tshirts = StubTshirtArticleOperations()
        application { installTshirtTestApplication(tshirts) }
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
        assertEquals(1, tshirts.listCalls)

        val read = admin.get("$BASE_PATH/7")
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(7L, tshirts.lastRequestedId)
        assertEquals(
            7L,
            Json.parseToJsonElement(read.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long,
        )

        tshirts.getResult = OperationResult.NotFound
        assertApiError(admin.get("$BASE_PATH/7"), HttpStatusCode.NotFound, "Article not found")

        tshirts.getResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.get("$BASE_PATH/7"),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )

        tshirts.listResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.get(BASE_PATH),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    @Test
    fun `http validation rejects before operations and a valid create preserves the contract`() =
        testApplication {
            val tshirts = StubTshirtArticleOperations()
            application { installTshirtTestApplication(tshirts) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            assertApiError(
                admin.createTshirt(token, """{"name":"  ","descriptionShort":"Short"}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf(
                    "name" to listOf("Name is required"),
                    "descriptionLong" to listOf("DescriptionLong is required"),
                    "printFrame" to listOf("PrintFrame is required"),
                ),
            )
            assertEquals(0, tshirts.operationCalls)

            val created = admin.createTshirt(token, VALID_BODY)
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            assertEquals("$BASE_PATH/42", created.headers[HttpHeaders.Location])
            // The route hands the body over untouched; trimming belongs to the service.
            assertEquals(" Classic ", tshirts.lastCreated?.name)
            assertEquals(3L, tshirts.lastCreated?.supplierId)
            assertEquals(1, tshirts.lastCreated?.tshirtVariants?.size)
            assertEquals(25.0, tshirts.lastCreated?.printFrame?.leftPct)
        }

    @Test
    fun `update and delete map their results to the required responses`() = testApplication {
        val tshirts = StubTshirtArticleOperations()
        application { installTshirtTestApplication(tshirts) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        assertEquals(HttpStatusCode.OK, admin.updateTshirt(token, id = 7).status)
        assertEquals(7L, tshirts.lastRequestedId)

        tshirts.updateResult = OperationResult.NotFound
        assertApiError(
            admin.updateTshirt(token, id = 7),
            HttpStatusCode.NotFound,
            "Article not found",
        )

        tshirts.updateResult =
            OperationResult.Invalid(mapOf("supplierId" to listOf("Supplier does not exist")))
        assertApiError(
            admin.updateTshirt(token, id = 7),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf("supplierId" to listOf("Supplier does not exist")),
        )

        tshirts.updateResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.updateTshirt(token, id = 7),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )

        val deleted = admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals("", deleted.bodyAsText())

        tshirts.deleteResult = OperationResult.NotFound
        assertApiError(
            admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) },
            HttpStatusCode.NotFound,
            "Article not found",
        )
    }

    @Test
    fun `the reorder route is a literal segment and maps its own conflict`() = testApplication {
        val tshirts = StubTshirtArticleOperations()
        application { installTshirtTestApplication(tshirts) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        // `order` is not bound as an article id: the item routes never see this request.
        val reordered = admin.reorder(token, """{"sourceId":43,"targetId":42}""")
        assertEquals(HttpStatusCode.OK, reordered.status)
        assertEquals(1, tshirts.reorderCalls)
        assertNull(tshirts.lastRequestedId)
        assertEquals(43L, tshirts.lastReordered?.sourceId)
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
        assertEquals(1, tshirts.reorderCalls)

        tshirts.reorderResult = OperationResult.NotFound
        assertApiError(
            admin.reorder(token, """{"sourceId":43,"targetId":42}"""),
            HttpStatusCode.NotFound,
            "Article not found",
        )

        // The one conflict of the shirt routes, with the stable message a client may retry on.
        tshirts.reorderResult = OperationResult.Conflict
        assertApiError(
            admin.reorder(token, """{"sourceId":43,"targetId":42}"""),
            HttpStatusCode.Conflict,
            "Article order changed concurrently, please retry",
        )

        tshirts.reorderResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.reorder(token, """{"sourceId":43,"targetId":42}"""),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    @Test
    fun `the two pre-uploads store their file part in their own folder`() = testApplication {
        val tshirts = StubTshirtArticleOperations()
        application { installTshirtTestApplication(tshirts) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        val storedVariantImage = admin.upload(token, "/variant-example-images", ByteArray(16) { 1 })
        assertEquals(HttpStatusCode.Created, storedVariantImage.status)
        assertEquals(
            ExampleImage("variant.webp"),
            Json.decodeFromString<ExampleImage>(storedVariantImage.bodyAsText()),
        )
        assertEquals("image/png", tshirts.lastUploadContentType)
        assertEquals(1, tshirts.exampleImageCalls)
        assertEquals(0, tshirts.sizeChartCalls)

        val storedSizeChart = admin.upload(token, "/size-charts", ByteArray(16) { 2 })
        assertEquals(HttpStatusCode.Created, storedSizeChart.status)
        assertEquals(
            ExampleImage("size-chart.webp"),
            Json.decodeFromString<ExampleImage>(storedSizeChart.bodyAsText()),
        )
        assertEquals(1, tshirts.sizeChartCalls)

        // Each route names the picture it is missing.
        assertApiError(
            admin.uploadWithoutFilePart(token, "/variant-example-images"),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf("file" to listOf("An example image file part is required")),
        )
        assertApiError(
            admin.uploadWithoutFilePart(token, "/size-charts"),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf("file" to listOf("A size chart file part is required")),
        )
        assertEquals(1, tshirts.exampleImageCalls)
        assertEquals(1, tshirts.sizeChartCalls)

        tshirts.storeResult =
            OperationResult.Invalid(
                mapOf("file" to listOf("Only JPEG, PNG, and WebP uploads are supported"))
            )
        assertApiError(
            admin.upload(token, "/size-charts", ByteArray(16)),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf("file" to listOf("Only JPEG, PNG, and WebP uploads are supported")),
        )
    }

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
    ): HttpResponse =
        put("$BASE_PATH/$id") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(VALID_BODY)
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

    private suspend fun HttpClient.upload(
        token: String,
        path: String,
        bytes: ByteArray,
    ): HttpResponse =
        post("$BASE_PATH$path") {
            header(AuthRouting.CSRF_HEADER, token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/png")
                                append(HttpHeaders.ContentDisposition, "filename=\"example.png\"")
                            },
                        )
                    }
                )
            )
        }

    private suspend fun HttpClient.uploadWithoutFilePart(
        token: String,
        path: String,
    ): HttpResponse =
        post("$BASE_PATH$path") {
            header(AuthRouting.CSRF_HEADER, token)
            setBody(MultiPartFormDataContent(formData { append("other", "not an image") }))
        }

    private fun Application.installTshirtTestApplication(tshirts: TshirtArticleOperations) {
        installHttpRuntime()
        install(RequestValidation) { validateArticleRequests() }
        installAuthModule(AuthSettings("article-tshirt-route-contract-session-secret"))
        installTshirtArticleRoutes(tshirts)
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

    private suspend fun antiforgeryToken(client: HttpClient): String =
        Json.parseToJsonElement(client.get("/api/antiforgery/token").bodyAsText())
            .jsonObject
            .getValue("requestToken")
            .jsonPrimitive
            .content

    private suspend fun assertApiError(
        response: HttpResponse,
        status: HttpStatusCode,
        message: String,
        errors: Map<String, List<String>> = emptyMap(),
    ) {
        assertEquals(status, response.status)
        assertEquals(
            apiErrorJson.encodeToJsonElement(ApiError(message, errors)).jsonObject,
            Json.parseToJsonElement(response.bodyAsText()).jsonObject,
        )
    }

    private class StubTshirtArticleOperations : TshirtArticleOperations {
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var reorderCalls = 0
        var exampleImageCalls = 0
        var sizeChartCalls = 0
        var lastRequestedId: Long? = null
        var lastCreated: TshirtArticleInput? = null
        var lastReordered: ReorderInput? = null
        var lastUploadContentType: String? = null
        var listResult: OperationResult<List<TshirtArticleListItem>>? = null
        var getResult: OperationResult<TshirtArticle>? = null
        var createResult: OperationResult<TshirtArticle>? = null
        var updateResult: OperationResult<TshirtArticle>? = null
        var deleteResult: OperationResult<Unit>? = null
        var reorderResult: OperationResult<List<TshirtArticleListItem>>? = null
        var storeResult: OperationResult<ExampleImage>? = null

        val operationCalls: Int
            get() = listCalls + getCalls + createCalls + updateCalls + deleteCalls + reorderCalls

        override suspend fun list(): OperationResult<List<TshirtArticleListItem>> {
            listCalls++
            return listResult ?: OperationResult.Success(listOf(listItem(42)))
        }

        override suspend fun get(id: Long): OperationResult<TshirtArticle> {
            getCalls++
            lastRequestedId = id
            return getResult ?: OperationResult.Success(tshirt(id))
        }

        override suspend fun create(input: TshirtArticleInput): OperationResult<TshirtArticle> {
            createCalls++
            lastCreated = input
            return createResult ?: OperationResult.Success(tshirt(42))
        }

        override suspend fun update(
            id: Long,
            input: TshirtArticleInput,
        ): OperationResult<TshirtArticle> {
            updateCalls++
            lastRequestedId = id
            return updateResult ?: OperationResult.Success(tshirt(id))
        }

        override suspend fun delete(id: Long): OperationResult<Unit> {
            deleteCalls++
            lastRequestedId = id
            return deleteResult ?: OperationResult.Success(Unit)
        }

        override suspend fun reorder(
            input: ReorderInput
        ): OperationResult<List<TshirtArticleListItem>> {
            reorderCalls++
            lastReordered = input
            return reorderResult
                ?: OperationResult.Success(listOf(listItem(42), listItem(43, position = 2)))
        }

        override suspend fun storeVariantExampleImage(
            upload: ImageUpload
        ): OperationResult<ExampleImage> {
            exampleImageCalls++
            lastUploadContentType = upload.contentType
            return storeResult ?: OperationResult.Success(ExampleImage("variant.webp"))
        }

        override suspend fun storeSizeChartImage(
            upload: ImageUpload
        ): OperationResult<ExampleImage> {
            sizeChartCalls++
            lastUploadContentType = upload.contentType
            return storeResult ?: OperationResult.Success(ExampleImage("size-chart.webp"))
        }

        private fun listItem(
            id: Long,
            position: Int = 1,
        ): TshirtArticleListItem =
            TshirtArticleListItem(
                id = id,
                position = position,
                name = "Classic",
                active = false,
                categoryId = null,
                categoryName = null,
                subcategoryId = null,
                subcategoryName = null,
                supplierId = null,
                supplierName = null,
                variantCount = 0,
                exampleImageFilename = null,
            )

        private fun tshirt(id: Long): TshirtArticle =
            TshirtArticle(
                id = id,
                position = 1,
                name = "Classic",
                descriptionShort = "Short",
                descriptionLong = "Long",
                active = false,
                categoryId = null,
                subcategoryId = null,
                supplierId = null,
                printAspectRatio = PrintAspectRatio.SQUARE,
                sizeChartImageFilename = null,
                printFrame =
                    PrintFrame(leftPct = 25.0, topPct = 20.0, widthPct = 50.0, heightPct = 40.0),
                tshirtVariants = emptyList(),
                price = null,
            )
    }

    private companion object {
        const val BASE_PATH = "/api/admin/articles/tshirts"

        val apiErrorJson = Json { encodeDefaults = true }

        /**
         * A body every field rule accepts, so that only the route's own behaviour is under test.
         */
        const val VALID_BODY =
            """{"name":" Classic ","descriptionShort":" Short ","descriptionLong":" Long ",""" +
                """"supplierId":3,"printFrame":{"leftPct":25,"topPct":20,"widthPct":50,""" +
                """"heightPct":40},"tshirtVariants":[{"colorName":"Black",""" +
                """"colorHex":"#000000","sizeLabel":"M","spodProductTypeId":812,""" +
                """"spodAppearanceId":5,"spodSizeId":91,"isDefault":true}]}"""
    }
}
