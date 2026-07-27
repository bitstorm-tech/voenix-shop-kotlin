package shop.voenix.article.taxonomy

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
import io.ktor.http.content.OutgoingContent
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
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.article.ExampleImage
import shop.voenix.article.ReorderInput
import shop.voenix.article.installArticleModule
import shop.voenix.article.validateArticleRequests
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.ApiError
import shop.voenix.http.installHttpRuntime
import shop.voenix.image.ImageUpload
import shop.voenix.operation.OperationResult

internal class ArticleSubcategoryRouteSecurityAndValidationTest {
    @Test
    fun `admin subtree rejects before id binding or subcategory operations`() = testApplication {
        val subcategories = StubArticleSubcategoryOperations()
        application { installArticleSubcategoryTestApplication(subcategories) }

        listOf(
                client.get(BASE_PATH),
                client.get("$BASE_PATH/1"),
                client.get("$BASE_PATH/not-a-long"),
                client.post(BASE_PATH),
                client.post("$BASE_PATH/example-images"),
                client.put("$BASE_PATH/order"),
                client.put("$BASE_PATH/1"),
                client.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(0, subcategories.operationCalls)

        val customer = signedInClient("CUSTOMER")
        listOf(
                customer.get(BASE_PATH),
                customer.get("$BASE_PATH/1"),
                customer.post(BASE_PATH),
                customer.post("$BASE_PATH/example-images"),
                customer.put("$BASE_PATH/order"),
                customer.put("$BASE_PATH/1"),
                customer.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Forbidden, response.status) }
        assertEquals(0, subcategories.operationCalls)

        val admin = signedInClient("ADMIN")
        listOf(
                admin.post(BASE_PATH),
                admin.post("$BASE_PATH/example-images"),
                admin.put("$BASE_PATH/order"),
                admin.put("$BASE_PATH/1"),
                admin.delete("$BASE_PATH/1"),
            )
            .forEach { response ->
                assertApiError(response, HttpStatusCode.BadRequest, "Invalid CSRF token")
            }
        assertApiError(
            admin.get("$BASE_PATH/not-a-long"),
            HttpStatusCode.BadRequest,
            "Invalid article subcategory id",
        )
        assertEquals(0, subcategories.operationCalls)
    }

    @Test
    fun `http validation rejects before operations and a valid create preserves the contract`() =
        testApplication {
            val subcategories = StubArticleSubcategoryOperations()
            application { installArticleSubcategoryTestApplication(subcategories) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            val invalid =
                admin.post(BASE_PATH) {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"   ","description":"${"a".repeat(1001)}"}""")
                }
            assertApiError(
                invalid,
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf(
                    "categoryId" to listOf("CategoryId is required"),
                    "name" to listOf("Name is required"),
                    "description" to listOf("Description must be at most 1000 characters"),
                ),
            )
            assertEquals(0, subcategories.operationCalls)

            val created =
                admin.post(BASE_PATH) {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"categoryId":3,"name":" Classic ","description":" Plain ",""" +
                            """"exampleImageFilename":"picture.webp","active":false}"""
                    )
                }
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("$BASE_PATH/42", created.headers[HttpHeaders.Location])
            assertEquals(
                ArticleSubcategoryInput(
                    categoryId = 3,
                    name = " Classic ",
                    description = " Plain ",
                    exampleImageFilename = "picture.webp",
                    active = false,
                ),
                subcategories.lastCreated,
            )

            subcategories.createResult = OperationResult.Conflict
            assertApiError(
                admin.post(BASE_PATH) {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"categoryId":3,"name":"Classic"}""")
                },
                HttpStatusCode.Conflict,
                NAME_CONFLICT_MESSAGE,
            )
        }

    @Test
    fun `reads answer with a bare array and map the required api errors`() = testApplication {
        val subcategories = StubArticleSubcategoryOperations()
        application { installArticleSubcategoryTestApplication(subcategories) }
        val admin = signedInClient("ADMIN")

        val listed = admin.get(BASE_PATH)
        assertEquals(HttpStatusCode.OK, listed.status)
        val listedJson = Json.parseToJsonElement(listed.bodyAsText()).jsonArray
        assertEquals(1, listedJson.size)
        assertEquals(
            "Classic",
            listedJson.single().jsonObject.getValue("name").jsonPrimitive.content,
        )

        assertEquals(HttpStatusCode.OK, admin.get("$BASE_PATH/7").status)
        assertEquals(7L, subcategories.lastRequestedId)

        subcategories.getResult = OperationResult.NotFound
        assertApiError(admin.get("$BASE_PATH/404"), HttpStatusCode.NotFound, NOT_FOUND_MESSAGE)

        subcategories.listResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.get(BASE_PATH),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    @Test
    fun `update delete and reorder map their results to the required responses`() =
        testApplication {
            val subcategories = StubArticleSubcategoryOperations()
            application { installArticleSubcategoryTestApplication(subcategories) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            assertApiError(
                admin.put("$BASE_PATH/7") {
                    header(AuthRouting.CSRF_HEADER, token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"categoryId":1,"description":"No name"}""")
                },
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf("name" to listOf("Name is required")),
            )
            assertEquals(0, subcategories.operationCalls)

            assertEquals(HttpStatusCode.OK, admin.putValidSubcategory(token).status)
            assertEquals(7L, subcategories.lastRequestedId)
            assertEquals(
                ArticleSubcategoryInput(categoryId = 1, name = "Classic"),
                subcategories.lastUpdated,
            )

            subcategories.updateResult = OperationResult.Conflict
            assertApiError(
                admin.putValidSubcategory(token),
                HttpStatusCode.Conflict,
                NAME_CONFLICT_MESSAGE,
            )

            subcategories.updateResult =
                OperationResult.Invalid(
                    mapOf("categoryId" to listOf("Article category does not exist"))
                )
            assertApiError(
                admin.putValidSubcategory(token),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf("categoryId" to listOf("Article category does not exist")),
            )

            val deleted = admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertEquals("", deleted.bodyAsText())

            subcategories.deleteResult = OperationResult.Conflict
            assertApiError(
                admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) },
                HttpStatusCode.Conflict,
                "Article subcategory is used by articles and cannot be deleted",
            )

            val callsBeforeInvalidReorder = subcategories.operationCalls
            assertApiError(
                admin.reorder(token, """{"sourceId":3,"targetId":3}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf("targetId" to listOf("TargetId must be different from SourceId")),
            )
            assertEquals(callsBeforeInvalidReorder, subcategories.operationCalls)

            assertEquals(
                HttpStatusCode.OK,
                admin.reorder(token, """{"sourceId":3,"targetId":1}""").status,
            )
            assertEquals(ReorderInput(sourceId = 3, targetId = 1), subcategories.lastReordered)

            subcategories.reorderResult = OperationResult.NotFound
            assertApiError(
                admin.reorder(token, """{"sourceId":3,"targetId":1}"""),
                HttpStatusCode.NotFound,
                NOT_FOUND_MESSAGE,
            )

            subcategories.reorderResult = OperationResult.Conflict
            assertApiError(
                admin.reorder(token, """{"sourceId":3,"targetId":1}"""),
                HttpStatusCode.Conflict,
                "Article subcategory order changed concurrently, please retry",
            )
        }

    @Test
    fun `the pre-upload stores the file part and reports what the storage rejected`() =
        testApplication {
            val subcategories = StubArticleSubcategoryOperations()
            application { installArticleSubcategoryTestApplication(subcategories) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            val stored = admin.uploadExampleImage(token, ByteArray(16) { 1 })
            assertEquals(HttpStatusCode.Created, stored.status)
            assertEquals(
                ExampleImage("stored.webp"),
                Json.decodeFromString<ExampleImage>(stored.bodyAsText()),
            )
            assertEquals("image/png", subcategories.lastUploadContentType)

            val withoutFilePart =
                admin.post("$BASE_PATH/example-images") {
                    header(AuthRouting.CSRF_HEADER, token)
                    setBody(MultiPartFormDataContent(formData { append("other", "not an image") }))
                }
            assertApiError(
                withoutFilePart,
                HttpStatusCode.BadRequest,
                "An example image file part is required",
            )
            assertEquals(1, subcategories.storeCalls)

            subcategories.storeResult =
                OperationResult.Invalid(
                    mapOf("image" to listOf("Only JPEG, PNG, and WebP uploads are supported"))
                )
            assertApiError(
                admin.uploadExampleImage(token, ByteArray(16)),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf("image" to listOf("Only JPEG, PNG, and WebP uploads are supported")),
            )
        }

    /**
     * That the limit is enforced *while* the body is read is a property of the reader and is proven
     * in `ExampleImageUploadTest`. What the route adds is the answer: an oversized upload never
     * reaches the image storage.
     */
    @Test
    fun `an oversized example image is rejected and never reaches the storage`() = testApplication {
        val subcategories = StubArticleSubcategoryOperations()
        application { installArticleSubcategoryTestApplication(subcategories) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        val response =
            admin.post("$BASE_PATH/example-images") {
                header(AuthRouting.CSRF_HEADER, token)
                setBody(OversizedFileUpload(OVERSIZED_BYTES))
            }

        assertApiError(
            response,
            HttpStatusCode.PayloadTooLarge,
            "Example image must not exceed 10 MiB",
        )
        assertEquals(0, subcategories.storeCalls)

        // Exactly the maximum is still accepted, so the limit rejects only what exceeds it.
        assertEquals(
            HttpStatusCode.Created,
            admin.uploadExampleImage(token, ByteArray(ImageUpload.MAX_BYTES)).status,
        )
    }

    private suspend fun HttpClient.putValidSubcategory(token: String): HttpResponse =
        put("$BASE_PATH/7") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"categoryId":1,"name":"Classic"}""")
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

    private suspend fun HttpClient.uploadExampleImage(
        token: String,
        bytes: ByteArray,
    ): HttpResponse =
        post("$BASE_PATH/example-images") {
            header(AuthRouting.CSRF_HEADER, token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/png")
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "filename=\"example.png\"",
                                )
                            },
                        )
                    }
                )
            )
        }

    private fun Application.installArticleSubcategoryTestApplication(
        subcategories: ArticleSubcategoryOperations
    ) {
        installHttpRuntime()
        install(RequestValidation) { validateArticleRequests() }
        installAuthModule(AuthSettings("article-subcategory-route-contract-session-secret"))
        installArticleModule(subcategories)
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

    /** A multipart body whose file part is produced lazily instead of held in one array. */
    private class OversizedFileUpload(private val totalBytes: Int) :
        OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType =
            ContentType.MultiPart.FormData.withParameter("boundary", BOUNDARY)

        @Suppress("TooGenericExceptionCaught")
        override suspend fun writeTo(channel: ByteWriteChannel) {
            try {
                channel.writeFully(PROLOGUE.toByteArray())
                val chunk = ByteArray(CHUNK_BYTES)
                var remaining = totalBytes
                while (remaining > 0) {
                    val size = minOf(remaining, chunk.size)
                    channel.writeFully(chunk, 0, size)
                    channel.flush()
                    remaining -= size
                }
                channel.writeFully(EPILOGUE.toByteArray())
                channel.flush()
            } catch (_: Exception) {
                // The server stopped reading, which is what the limit is supposed to make it do.
            }
        }

        private companion object {
            const val CHUNK_BYTES = 64 * 1024
            const val BOUNDARY = "ArticleSubcategoryExampleImageBoundary"
            val PROLOGUE =
                "--$BOUNDARY\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"huge.png\"\r\n" +
                    "Content-Type: image/png\r\n\r\n"
            val EPILOGUE = "\r\n--$BOUNDARY--\r\n"
        }
    }

    private class StubArticleSubcategoryOperations : ArticleSubcategoryOperations {
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var reorderCalls = 0
        var storeCalls = 0
        var lastRequestedId: Long? = null
        var lastCreated: ArticleSubcategoryInput? = null
        var lastUpdated: ArticleSubcategoryInput? = null
        var lastReordered: ReorderInput? = null
        var lastUploadContentType: String? = null
        var listResult: OperationResult<List<ArticleSubcategory>>? = null
        var getResult: OperationResult<ArticleSubcategory>? = null
        var createResult: OperationResult<ArticleSubcategory>? = null
        var updateResult: OperationResult<ArticleSubcategory>? = null
        var deleteResult: OperationResult<Unit>? = null
        var reorderResult: OperationResult<List<ArticleSubcategory>>? = null
        var storeResult: OperationResult<ExampleImage>? = null

        val operationCalls: Int
            get() = listCalls + getCalls + createCalls + updateCalls + deleteCalls + reorderCalls

        override suspend fun list(): OperationResult<List<ArticleSubcategory>> {
            listCalls++
            return listResult ?: OperationResult.Success(listOf(subcategory(1)))
        }

        override suspend fun get(id: Long): OperationResult<ArticleSubcategory> {
            getCalls++
            lastRequestedId = id
            return getResult ?: OperationResult.Success(subcategory(id))
        }

        override suspend fun create(
            input: ArticleSubcategoryInput
        ): OperationResult<ArticleSubcategory> {
            createCalls++
            lastCreated = input
            return createResult ?: OperationResult.Success(subcategory(42))
        }

        override suspend fun update(
            id: Long,
            input: ArticleSubcategoryInput,
        ): OperationResult<ArticleSubcategory> {
            updateCalls++
            lastRequestedId = id
            lastUpdated = input
            return updateResult ?: OperationResult.Success(subcategory(id))
        }

        override suspend fun delete(id: Long): OperationResult<Unit> {
            deleteCalls++
            lastRequestedId = id
            return deleteResult ?: OperationResult.Success(Unit)
        }

        override suspend fun reorder(
            input: ReorderInput
        ): OperationResult<List<ArticleSubcategory>> {
            reorderCalls++
            lastReordered = input
            return reorderResult ?: OperationResult.Success(listOf(subcategory(1)))
        }

        override suspend fun storeExampleImage(upload: ImageUpload): OperationResult<ExampleImage> {
            storeCalls++
            lastUploadContentType = upload.contentType
            return storeResult ?: OperationResult.Success(ExampleImage("stored.webp"))
        }

        private fun subcategory(id: Long): ArticleSubcategory =
            ArticleSubcategory(
                id = id,
                categoryId = 1,
                name = "Classic",
                description = null,
                exampleImageFilename = null,
                position = 1,
                active = true,
            )
    }

    private companion object {
        const val BASE_PATH = "/api/admin/articles/subcategories"
        const val NOT_FOUND_MESSAGE = "Article subcategory not found"
        const val NAME_CONFLICT_MESSAGE =
            "Article subcategory name already exists in this article category"
        const val OVERSIZED_BYTES = 24 * 1024 * 1024
        val apiErrorJson = Json { encodeDefaults = true }
    }
}
