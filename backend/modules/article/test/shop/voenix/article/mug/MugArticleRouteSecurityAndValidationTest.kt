package shop.voenix.article.mug

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import shop.voenix.article.ExampleImage
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

/**
 * What the mug routes answer, against stubbed operations: the security subtree, the id binding, the
 * validation that runs before any operation, and the mapping of every result the operations can
 * produce.
 */
internal class MugArticleRouteSecurityAndValidationTest {
    @Test
    fun `admin subtree rejects before id binding or mug operations`() = testApplication {
        val mugs = StubMugArticleOperations()
        application { installMugTestApplication(mugs) }

        listOf(
                client.get(BASE_PATH),
                client.get("$BASE_PATH/1"),
                client.post(BASE_PATH),
                client.post("$BASE_PATH/variant-example-images"),
                client.put("$BASE_PATH/1"),
                client.put("$BASE_PATH/not-a-long"),
                client.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(0, mugs.operationCalls)

        val customer = signedInClient("CUSTOMER")
        listOf(
                customer.get(BASE_PATH),
                customer.get("$BASE_PATH/1"),
                customer.post(BASE_PATH),
                customer.post("$BASE_PATH/variant-example-images"),
                customer.put("$BASE_PATH/1"),
                customer.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Forbidden, response.status) }
        assertEquals(0, mugs.operationCalls)

        val admin = signedInClient("ADMIN")
        listOf(
                admin.post(BASE_PATH),
                admin.post("$BASE_PATH/variant-example-images"),
                admin.put("$BASE_PATH/1"),
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
        assertEquals(0, mugs.operationCalls)
    }

    @Test
    fun `the read routes answer without a csrf token and map their results`() = testApplication {
        val mugs = StubMugArticleOperations()
        application { installMugTestApplication(mugs) }
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
        assertEquals(1, mugs.listCalls)

        val read = admin.get("$BASE_PATH/7")
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(7L, mugs.lastRequestedId)
        assertEquals(
            7L,
            Json.parseToJsonElement(read.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long,
        )

        mugs.getResult = OperationResult.NotFound
        assertApiError(admin.get("$BASE_PATH/7"), HttpStatusCode.NotFound, "Article not found")

        mugs.getResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.get("$BASE_PATH/7"),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )

        mugs.listResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.get(BASE_PATH),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    @Test
    fun `http validation rejects before operations and a valid create preserves the contract`() =
        testApplication {
            val mugs = StubMugArticleOperations()
            application { installMugTestApplication(mugs) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            assertApiError(
                admin.createMug(token, """{"name":"  ","descriptionShort":"Short"}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf(
                    "name" to listOf("Name is required"),
                    "descriptionLong" to listOf("DescriptionLong is required"),
                ),
            )
            assertEquals(0, mugs.operationCalls)

            val created =
                admin.createMug(
                    token,
                    """{"name":" Classic ","descriptionShort":" Short ",""" +
                        """"descriptionLong":" Long ","supplierId":3,""" +
                        """"mugVariants":[{"name":"White","insideColorCode":"#fff",""" +
                        """"outsideColorCode":"#fff","isDefault":true}]}""",
                )
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("$BASE_PATH/42", created.headers[HttpHeaders.Location])
            // The route hands the body over untouched; trimming belongs to the service.
            assertEquals(" Classic ", mugs.lastCreated?.name)
            assertEquals(3L, mugs.lastCreated?.supplierId)
            assertEquals(1, mugs.lastCreated?.mugVariants?.size)
        }

    @Test
    fun `update and delete map their results to the required responses`() = testApplication {
        val mugs = StubMugArticleOperations()
        application { installMugTestApplication(mugs) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        assertEquals(HttpStatusCode.OK, admin.updateMug(token, id = 7).status)
        assertEquals(7L, mugs.lastRequestedId)

        mugs.updateResult = OperationResult.NotFound
        assertApiError(
            admin.updateMug(token, id = 7),
            HttpStatusCode.NotFound,
            "Article not found",
        )

        mugs.updateResult =
            OperationResult.Invalid(mapOf("supplierId" to listOf("Supplier does not exist")))
        assertApiError(
            admin.updateMug(token, id = 7),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf("supplierId" to listOf("Supplier does not exist")),
        )

        mugs.updateResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.updateMug(token, id = 7),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )

        val deleted = admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals("", deleted.bodyAsText())

        mugs.deleteResult = OperationResult.NotFound
        assertApiError(
            admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) },
            HttpStatusCode.NotFound,
            "Article not found",
        )
    }

    @Test
    fun `the pre-upload stores the file part and reports what the storage rejected`() =
        testApplication {
            val mugs = StubMugArticleOperations()
            application { installMugTestApplication(mugs) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            val stored = admin.uploadExampleImage(token, ByteArray(16) { 1 })
            assertEquals(HttpStatusCode.Created, stored.status)
            assertEquals(
                ExampleImage("stored.webp"),
                Json.decodeFromString<ExampleImage>(stored.bodyAsText()),
            )
            assertEquals("image/png", mugs.lastUploadContentType)

            val withoutFilePart =
                admin.post("$BASE_PATH/variant-example-images") {
                    header(AuthRouting.CSRF_HEADER, token)
                    setBody(MultiPartFormDataContent(formData { append("other", "not an image") }))
                }
            assertApiError(
                withoutFilePart,
                HttpStatusCode.BadRequest,
                "An example image file part is required",
            )
            assertEquals(1, mugs.storeCalls)

            mugs.storeResult =
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
    ): HttpResponse =
        put("$BASE_PATH/$id") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Classic","descriptionShort":"Short","descriptionLong":"Long"}""")
        }

    private suspend fun HttpClient.uploadExampleImage(
        token: String,
        bytes: ByteArray,
    ): HttpResponse =
        post("$BASE_PATH/variant-example-images") {
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

    private fun Application.installMugTestApplication(mugs: MugArticleOperations) {
        installHttpRuntime()
        install(RequestValidation) { validateArticleRequests() }
        installAuthModule(AuthSettings("article-mug-route-contract-session-secret"))
        installArticleModule(mugs)
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

    private class StubMugArticleOperations : MugArticleOperations {
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var storeCalls = 0
        var lastRequestedId: Long? = null
        var lastCreated: MugArticleInput? = null
        var lastUploadContentType: String? = null
        var listResult: OperationResult<List<MugArticleListItem>>? = null
        var getResult: OperationResult<MugArticle>? = null
        var createResult: OperationResult<MugArticle>? = null
        var updateResult: OperationResult<MugArticle>? = null
        var deleteResult: OperationResult<Unit>? = null
        var storeResult: OperationResult<ExampleImage>? = null

        val operationCalls: Int
            get() = listCalls + getCalls + createCalls + updateCalls + deleteCalls

        override suspend fun list(): OperationResult<List<MugArticleListItem>> {
            listCalls++
            return listResult ?: OperationResult.Success(listOf(listItem(42)))
        }

        override suspend fun get(id: Long): OperationResult<MugArticle> {
            getCalls++
            lastRequestedId = id
            return getResult ?: OperationResult.Success(mug(id))
        }

        override suspend fun create(input: MugArticleInput): OperationResult<MugArticle> {
            createCalls++
            lastCreated = input
            return createResult ?: OperationResult.Success(mug(42))
        }

        override suspend fun update(
            id: Long,
            input: MugArticleInput,
        ): OperationResult<MugArticle> {
            updateCalls++
            lastRequestedId = id
            return updateResult ?: OperationResult.Success(mug(id))
        }

        override suspend fun delete(id: Long): OperationResult<Unit> {
            deleteCalls++
            lastRequestedId = id
            return deleteResult ?: OperationResult.Success(Unit)
        }

        override suspend fun storeVariantExampleImage(
            upload: ImageUpload
        ): OperationResult<ExampleImage> {
            storeCalls++
            lastUploadContentType = upload.contentType
            return storeResult ?: OperationResult.Success(ExampleImage("stored.webp"))
        }

        private fun listItem(id: Long): MugArticleListItem =
            MugArticleListItem(
                id = id,
                position = 1,
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

        private fun mug(id: Long): MugArticle =
            MugArticle(
                id = id,
                position = 1,
                name = "Classic",
                descriptionShort = "Short",
                descriptionLong = "Long",
                active = false,
                categoryId = null,
                subcategoryId = null,
                supplierId = null,
                supplierArticleName = null,
                supplierArticleNumber = null,
                mugDetails = null,
                mugVariants = emptyList(),
                price = null,
            )
    }

    private companion object {
        const val BASE_PATH = "/api/admin/articles/mugs"
        val apiErrorJson = Json { encodeDefaults = true }
    }
}
