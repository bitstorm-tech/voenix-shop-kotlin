package shop.voenix.prompt

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
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.ApiError
import shop.voenix.http.installHttpRuntime
import shop.voenix.image.ImageUpload
import shop.voenix.operation.OperationResult
import shop.voenix.pricing.PriceInput

/**
 * The prompt route contract against stubbed operations: who may call the routes, what the routes
 * reject before an operation runs, and how each outcome becomes a response.
 *
 * Two absences are asserted as deliberately as the present behavior: the admin subtree has no
 * delete route, because `archived` is the soft delete, and `PUT /order` is the only route that can
 * answer `409`.
 */
internal class PromptRouteSecurityAndValidationTest {
    @Test
    fun `admin subtree rejects before id binding or prompt operations`() = testApplication {
        val prompts = StubPromptOperations()
        application { installPromptTestApplication(prompts) }

        listOf(
                client.get(BASE_PATH),
                client.get("$BASE_PATH/1"),
                client.get("$BASE_PATH/not-a-long"),
                client.post(BASE_PATH),
                client.post("$BASE_PATH/example-images"),
                client.put("$BASE_PATH/1"),
                client.put("$BASE_PATH/order"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(0, prompts.operationCalls)

        val customer = signedInClient("CUSTOMER")
        listOf(
                customer.get(BASE_PATH),
                customer.get("$BASE_PATH/1"),
                customer.post(BASE_PATH),
                customer.post("$BASE_PATH/example-images"),
                customer.put("$BASE_PATH/1"),
                customer.put("$BASE_PATH/order"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Forbidden, response.status) }
        assertEquals(0, prompts.operationCalls)

        val admin = signedInClient("ADMIN")
        listOf(
                admin.post(BASE_PATH),
                admin.post("$BASE_PATH/example-images"),
                admin.put("$BASE_PATH/1"),
                admin.put("$BASE_PATH/order"),
            )
            .forEach { response ->
                assertApiError(response, HttpStatusCode.BadRequest, "Invalid CSRF token")
            }
        assertApiError(
            admin.get("$BASE_PATH/not-a-long"),
            HttpStatusCode.BadRequest,
            "Invalid prompt id",
        )
        assertEquals(0, prompts.operationCalls)
    }

    /**
     * The storefront route is the one prompt route without a session, and the absence of the admin
     * subtree around it is what makes it anonymous. Its only parameter is `categoryId`, and the two
     * ways it can go wrong are two different answers: a value that is not a number is rejected
     * before the operation runs, while a number this route cannot vouch for is simply passed on —
     * "there is no such category" is the empty list, not an error.
     */
    @Test
    fun `the public route is anonymous and rejects only an unparsable category id`() =
        testApplication {
            val publicPrompts = StubPublicPromptOperations()
            application { installPublicPromptTestApplication(publicPrompts) }

            assertEquals(HttpStatusCode.OK, client.get(PUBLIC_PATH).status)
            assertEquals(listOf<Long?>(null), publicPrompts.requestedCategoryIds)

            // A bare array of the public rows, and never the prompt text.
            val row =
                Json.parseToJsonElement(client.get(PUBLIC_PATH).bodyAsText())
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(
                setOf(
                    "id",
                    "position",
                    "title",
                    "category",
                    "subcategory",
                    "exampleImageFilename",
                    "llm",
                    "price",
                ),
                row.keys,
            )

            publicPrompts.requestedCategoryIds.clear()
            assertEquals(HttpStatusCode.OK, client.get("$PUBLIC_PATH?categoryId=7").status)
            // An empty parameter is what a form that always submits its fields sends: no filter.
            assertEquals(HttpStatusCode.OK, client.get("$PUBLIC_PATH?categoryId=").status)
            assertEquals(listOf(7L, null), publicPrompts.requestedCategoryIds)

            publicPrompts.requestedCategoryIds.clear()
            listOf("not-a-long", "1.5", "9999999999999999999999").forEach { value ->
                assertApiError(
                    client.get("$PUBLIC_PATH?categoryId=$value"),
                    HttpStatusCode.BadRequest,
                    "Invalid prompt category id",
                )
            }
            assertEquals(emptyList(), publicPrompts.requestedCategoryIds)

            publicPrompts.listResult = OperationResult.UnexpectedFailure
            assertApiError(
                client.get(PUBLIC_PATH),
                HttpStatusCode.InternalServerError,
                "Internal server error",
            )
        }

    /**
     * A prompt is never deleted; `archived` is the soft delete. There is therefore nothing to route
     * a `DELETE` to, even for an admin with a valid CSRF token, and no operation is asked about it
     * — because there is no operation to ask.
     *
     * The two answers differ only because of how Ktor resolves them: the collection path is
     * registered with other methods and reports `405`, while `/{id}` reaches no handler at all and
     * reports `404`. What both mean is the same thing.
     */
    @Test
    fun `there is no delete route`() = testApplication {
        val prompts = StubPromptOperations()
        application { installPromptTestApplication(prompts) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        assertEquals(
            HttpStatusCode.MethodNotAllowed,
            admin.delete(BASE_PATH) { header(AuthRouting.CSRF_HEADER, token) }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            admin.delete("$BASE_PATH/1") { header(AuthRouting.CSRF_HEADER, token) }.status,
        )
        assertEquals(0, prompts.operationCalls)
    }

    @Test
    fun `http validation rejects before operations and a valid create preserves the contract`() =
        testApplication {
            val prompts = StubPromptOperations()
            application { installPromptTestApplication(prompts) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            assertApiError(
                admin.createPrompt(token, """{"title":"Watercolor"}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf(
                    "promptText" to listOf("PromptText is required"),
                    "categoryId" to listOf("CategoryId is required"),
                    "slotVariantIds" to listOf("SlotVariantIds is required"),
                    "price" to listOf("Price is required"),
                ),
            )
            assertEquals(0, prompts.operationCalls)

            val created = admin.createPrompt(token, completeBody())
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("$BASE_PATH/42", created.headers[HttpHeaders.Location])
            // The route hands the body through untouched; trimming and dedup belong to the service.
            assertEquals(
                PromptInput(
                    title = "  Watercolor  ",
                    promptText = "Turn the photo into art.\n",
                    categoryId = 3,
                    subcategoryId = 7,
                    slotVariantIds = listOf(12, 9, 12),
                    llm = "gpt-image-1",
                    active = true,
                    price =
                        PriceInput(purchaseVatId = 1, salesVatId = 1, salesTotalInputCents = 499),
                ),
                prompts.lastCreated,
            )

            // Every reference a client can get wrong comes back as a field error, never as a 409.
            prompts.createResult =
                OperationResult.Invalid(
                    mapOf(
                        "slotVariantIds" to listOf("One or more prompt slot variants do not exist")
                    )
                )
            assertApiError(
                admin.createPrompt(token, completeBody()),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf(
                    "slotVariantIds" to listOf("One or more prompt slot variants do not exist")
                ),
            )
        }

    @Test
    fun `reads answer with a bare array of flat rows and map the required api errors`() =
        testApplication {
            val prompts = StubPromptOperations()
            application { installPromptTestApplication(prompts) }
            val admin = signedInClient("ADMIN")

            val listed = admin.get(BASE_PATH)
            assertEquals(HttpStatusCode.OK, listed.status)
            val row = Json.parseToJsonElement(listed.bodyAsText()).jsonArray.single().jsonObject
            assertEquals(
                setOf(
                    "id",
                    "position",
                    "title",
                    "categoryId",
                    "categoryName",
                    "subcategoryId",
                    "subcategoryName",
                    "exampleImageFilename",
                    "llm",
                    "active",
                    "archived",
                    "price",
                ),
                row.keys,
            )
            // The list price is the small projection, not the whole calculated price.
            assertEquals(
                setOf(
                    "salesTotalNet",
                    "salesTotalGross",
                    "salesTotalTax",
                    "salesVatRatePercent",
                ),
                row.getValue("price").jsonObject.keys,
            )

            val detail = admin.get("$BASE_PATH/7")
            assertEquals(HttpStatusCode.OK, detail.status)
            assertEquals(7L, prompts.lastRequestedId)
            val body = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
            assertTrue("promptText" in body.keys)
            assertTrue("slotVariantIds" in body.keys)
            assertTrue("priceId" !in body.keys, "A prompt never answers with a price id")

            prompts.getResult = OperationResult.NotFound
            assertApiError(admin.get("$BASE_PATH/404"), HttpStatusCode.NotFound, "Prompt not found")

            prompts.listResult = OperationResult.UnexpectedFailure
            assertApiError(
                admin.get(BASE_PATH),
                HttpStatusCode.InternalServerError,
                "Internal server error",
            )
        }

    @Test
    fun `update maps its results to the required responses`() = testApplication {
        val prompts = StubPromptOperations()
        application { installPromptTestApplication(prompts) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        assertApiError(
            admin.updatePrompt(token, """{"promptText":"text","categoryId":1,"price":{}}"""),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf(
                "title" to listOf("Title is required"),
                "slotVariantIds" to listOf("SlotVariantIds is required"),
            ),
        )
        assertEquals(0, prompts.operationCalls)

        assertEquals(HttpStatusCode.OK, admin.updatePrompt(token, completeBody()).status)
        assertEquals(7L, prompts.lastRequestedId)

        prompts.updateResult = OperationResult.NotFound
        assertApiError(
            admin.updatePrompt(token, completeBody()),
            HttpStatusCode.NotFound,
            "Prompt not found",
        )
    }

    /**
     * The reorder is the one prompt route that answers `409`, and the message it answers with is
     * part of the contract: a client retries on it. It is also the one route that answers a list
     * although the client moved a single prompt — the complete new order, in the rows of the list.
     */
    @Test
    fun `reorder answers the new order and the only conflict of this route group`() =
        testApplication {
            val prompts = StubPromptOperations()
            application { installPromptTestApplication(prompts) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            assertApiError(
                admin.reorder(token, """{"sourceId":5}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf("targetId" to listOf("TargetId is required")),
            )
            assertEquals(0, prompts.reorderCalls)

            val moved = admin.reorder(token, """{"sourceId":9,"targetId":1}""")
            assertEquals(HttpStatusCode.OK, moved.status)
            assertEquals(ReorderInput(sourceId = 9, targetId = 1), prompts.lastReorder)
            // The answer is a bare array of the same rows the list answers with.
            val row = Json.parseToJsonElement(moved.bodyAsText()).jsonArray.single().jsonObject
            assertTrue("position" in row.keys && "categoryName" in row.keys)

            prompts.reorderResult = OperationResult.Conflict
            assertApiError(
                admin.reorder(token, """{"sourceId":9,"targetId":1}"""),
                HttpStatusCode.Conflict,
                "Prompt order changed concurrently, please retry",
            )

            prompts.reorderResult = OperationResult.NotFound
            assertApiError(
                admin.reorder(token, """{"sourceId":9,"targetId":1}"""),
                HttpStatusCode.NotFound,
                "Prompt not found",
            )
        }

    @Test
    fun `the pre-upload stores the file part and reports what the storage rejected`() =
        testApplication {
            val prompts = StubPromptOperations()
            application { installPromptTestApplication(prompts) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            val stored = admin.uploadExampleImage(token, ByteArray(16) { 1 })
            assertEquals(HttpStatusCode.Created, stored.status)
            assertEquals(
                ExampleImage(STORED_FILENAME),
                Json.decodeFromString<ExampleImage>(stored.bodyAsText()),
            )
            assertEquals("image/png", prompts.lastUploadContentType)

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
            assertEquals(1, prompts.storeCalls)

            prompts.storeResult =
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
     * That the limit is enforced *while* the body is read is a property of the promoted reader and
     * is proven in the image module's `ExampleImageUploadTest`. What the route adds is the answer:
     * an oversized upload never reaches the image storage.
     */
    @Test
    fun `an oversized example image is rejected and never reaches the storage`() = testApplication {
        val prompts = StubPromptOperations()
        application { installPromptTestApplication(prompts) }
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
        assertEquals(0, prompts.storeCalls)

        // Exactly the maximum is still accepted, so the limit rejects only what exceeds it.
        assertEquals(
            HttpStatusCode.Created,
            admin.uploadExampleImage(token, ByteArray(ImageUpload.MAX_BYTES)).status,
        )
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

    private fun completeBody(): String =
        """{"title":"  Watercolor  ","promptText":"Turn the photo into art.\n","categoryId":3,""" +
            """"subcategoryId":7,"slotVariantIds":[12,9,12],"llm":"gpt-image-1","active":true,""" +
            """"price":{"purchaseVatId":1,"salesVatId":1,"salesTotalInputCents":499}}"""

    private suspend fun HttpClient.createPrompt(
        token: String,
        body: String,
    ): HttpResponse =
        post(BASE_PATH) {
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

    private suspend fun HttpClient.updatePrompt(
        token: String,
        body: String,
    ): HttpResponse =
        put("$BASE_PATH/7") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun Application.installPromptTestApplication(prompts: PromptOperations) {
        installHttpRuntime()
        install(RequestValidation) { validatePromptRequests() }
        installAuthModule(AuthSettings("prompt-route-contract-session-secret"))
        installPromptModule(prompts)
        routing {
            post("/test/sign-in/{role}") {
                call.sessions.set(
                    UserSession(userId = "11", roles = setOf(checkNotNull(call.parameters["role"])))
                )
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    /**
     * The storefront route on its own, without the auth module: nothing installs a session here, so
     * an answer proves that the route asks for none.
     */
    private fun Application.installPublicPromptTestApplication(
        publicPrompts: PublicPromptOperations
    ) {
        installHttpRuntime()
        installPromptModule(publicPrompts)
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
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        assertEquals(
            apiErrorJson.encodeToJsonElement(ApiError(message, errors)).jsonObject,
            Json.parseToJsonElement(response.bodyAsText()).jsonObject,
        )
    }

    /** A multipart body that keeps offering bytes until the server stops reading them. */
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
            const val BOUNDARY = "PromptExampleImageBoundary"
            val PROLOGUE =
                "--$BOUNDARY\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"huge.png\"\r\n" +
                    "Content-Type: image/png\r\n\r\n"
            val EPILOGUE = "\r\n--$BOUNDARY--\r\n"
        }
    }

    /** Remembers what the route parsed out of the query string, including "nothing at all". */
    private class StubPublicPromptOperations : PublicPromptOperations {
        val requestedCategoryIds: MutableList<Long?> = mutableListOf()
        var listResult: OperationResult<List<PublicPrompt>>? = null

        override suspend fun list(categoryId: Long?): OperationResult<List<PublicPrompt>> {
            requestedCategoryIds += categoryId
            return listResult ?: OperationResult.Success(listOf(publicPrompt()))
        }

        private fun publicPrompt(): PublicPrompt =
            PublicPrompt(
                id = 1,
                position = 1,
                title = "Watercolor",
                category = PromptCategoryReference(id = 3, name = "Portraits", position = 1),
                subcategory = PromptCategoryReference(id = 7, name = "Kids", position = 1),
                exampleImageFilename = null,
                llm = "gpt-image-1",
                price =
                    PromptPrice(
                        salesTotalNet = 419,
                        salesTotalGross = 499,
                        salesTotalTax = 80,
                        salesVatRatePercent = 19,
                    ),
            )
    }

    private class StubPromptOperations : PromptOperations {
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        var reorderCalls = 0
        var storeCalls = 0
        var lastRequestedId: Long? = null
        var lastCreated: PromptInput? = null
        var lastReorder: ReorderInput? = null
        var lastUploadContentType: String? = null
        var storeResult: OperationResult<ExampleImage>? = null
        var listResult: OperationResult<List<PromptListItem>>? = null
        var getResult: OperationResult<Prompt>? = null
        var createResult: OperationResult<Prompt>? = null
        var updateResult: OperationResult<Prompt>? = null
        var reorderResult: OperationResult<List<PromptListItem>>? = null

        val operationCalls: Int
            get() = listCalls + getCalls + createCalls + updateCalls + reorderCalls

        override suspend fun list(): OperationResult<List<PromptListItem>> {
            listCalls++
            return listResult ?: OperationResult.Success(listOf(listItem()))
        }

        override suspend fun get(id: Long): OperationResult<Prompt> {
            getCalls++
            lastRequestedId = id
            return getResult ?: OperationResult.Success(prompt(id))
        }

        override suspend fun create(input: PromptInput): OperationResult<Prompt> {
            createCalls++
            lastCreated = input
            return createResult ?: OperationResult.Success(prompt(42))
        }

        override suspend fun update(
            id: Long,
            input: PromptInput,
        ): OperationResult<Prompt> {
            updateCalls++
            lastRequestedId = id
            return updateResult ?: OperationResult.Success(prompt(id))
        }

        override suspend fun reorder(input: ReorderInput): OperationResult<List<PromptListItem>> {
            reorderCalls++
            lastReorder = input
            return reorderResult ?: OperationResult.Success(listOf(listItem()))
        }

        override suspend fun storeExampleImage(upload: ImageUpload): OperationResult<ExampleImage> {
            storeCalls++
            lastUploadContentType = upload.contentType
            return storeResult ?: OperationResult.Success(ExampleImage(STORED_FILENAME))
        }

        private fun prompt(id: Long): Prompt =
            Prompt(
                id = id,
                position = 1,
                title = "Watercolor",
                promptText = "Turn the photo into art.\n",
                categoryId = 3,
                subcategoryId = 7,
                slotVariantIds = listOf(9, 12),
                exampleImageFilename = null,
                llm = "gpt-image-1",
                active = true,
                archived = false,
                price = null,
            )

        private fun listItem(): PromptListItem =
            PromptListItem(
                id = 1,
                position = 1,
                title = "Watercolor",
                categoryId = 3,
                categoryName = "Portraits",
                subcategoryId = 7,
                subcategoryName = "Kids",
                exampleImageFilename = null,
                llm = "gpt-image-1",
                active = true,
                archived = false,
                price =
                    PromptPrice(
                        salesTotalNet = 419,
                        salesTotalGross = 499,
                        salesTotalTax = 80,
                        salesVatRatePercent = 19,
                    ),
            )
    }

    private companion object {
        const val BASE_PATH = "/api/admin/prompts"
        const val PUBLIC_PATH = "/api/prompts"
        const val STORED_FILENAME = "11111111-1111-4111-8111-111111111111.webp"
        const val OVERSIZED_BYTES = 24 * 1024 * 1024
        val apiErrorJson = Json { encodeDefaults = true }
    }
}
