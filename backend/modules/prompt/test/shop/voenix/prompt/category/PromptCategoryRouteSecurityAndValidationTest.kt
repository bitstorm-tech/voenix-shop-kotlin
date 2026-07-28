package shop.voenix.prompt.category

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
import shop.voenix.operation.OperationResult
import shop.voenix.prompt.ReorderInput
import shop.voenix.prompt.installPromptModule
import shop.voenix.prompt.validatePromptRequests

internal class PromptCategoryRouteSecurityAndValidationTest {
    @Test
    fun `admin subtree rejects before id binding or category operations`() = testApplication {
        val categories = StubPromptCategoryOperations()
        application { installPromptCategoryTestApplication(categories) }

        listOf(
                client.get(BASE_PATH),
                client.get("$BASE_PATH/1"),
                client.get("$BASE_PATH/not-a-long"),
                client.post(BASE_PATH),
                client.put("$BASE_PATH/order"),
                client.put("$BASE_PATH/1"),
                client.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(0, categories.operationCalls)

        val customer = signedInClient("CUSTOMER")
        listOf(
                customer.get(BASE_PATH),
                customer.get("$BASE_PATH/1"),
                customer.post(BASE_PATH),
                customer.put("$BASE_PATH/order"),
                customer.put("$BASE_PATH/1"),
                customer.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Forbidden, response.status) }
        assertEquals(0, categories.operationCalls)

        val admin = signedInClient("ADMIN")
        listOf(
                admin.post(BASE_PATH),
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
            "Invalid prompt category id",
        )
        assertEquals(0, categories.operationCalls)
    }

    @Test
    fun `http validation rejects before operations and a valid create preserves the contract`() =
        testApplication {
            val categories = StubPromptCategoryOperations()
            application { installPromptCategoryTestApplication(categories) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            assertApiError(
                admin.createCategory(token, """{"name":"   "}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf("name" to listOf("Name is required")),
            )
            assertEquals(0, categories.operationCalls)

            val created = admin.createCategory(token, """{"name":" Portraits "}""")
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("$BASE_PATH/42", created.headers[HttpHeaders.Location])
            assertEquals(PromptCategoryInput(name = " Portraits "), categories.lastCreated)

            categories.createResult = OperationResult.Conflict
            assertApiError(
                admin.createCategory(token, """{"name":"Portraits"}"""),
                HttpStatusCode.Conflict,
                "Prompt category name already exists",
            )
        }

    @Test
    fun `reads answer with a bare array and map the required api errors`() = testApplication {
        val categories = StubPromptCategoryOperations()
        application { installPromptCategoryTestApplication(categories) }
        val admin = signedInClient("ADMIN")

        val listed = admin.get(BASE_PATH)
        assertEquals(HttpStatusCode.OK, listed.status)
        val listedJson = Json.parseToJsonElement(listed.bodyAsText()).jsonArray
        assertEquals(1, listedJson.size)
        assertEquals(
            "Portraits",
            listedJson.single().jsonObject.getValue("name").jsonPrimitive.content,
        )

        assertEquals(HttpStatusCode.OK, admin.get("$BASE_PATH/7").status)
        assertEquals(7L, categories.lastRequestedId)

        categories.getResult = OperationResult.NotFound
        assertApiError(
            admin.get("$BASE_PATH/404"),
            HttpStatusCode.NotFound,
            "Prompt category not found",
        )

        categories.listResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.get(BASE_PATH),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    @Test
    fun `the reorder route validates its body and answers with the complete new order`() =
        testApplication {
            val categories = StubPromptCategoryOperations()
            application { installPromptCategoryTestApplication(categories) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            assertApiError(
                admin.reorder(token, """{"sourceId":7,"targetId":7}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf("targetId" to listOf("TargetId must be different from SourceId")),
            )
            assertEquals(0, categories.operationCalls)

            val reordered = admin.reorder(token, """{"sourceId":3,"targetId":1}""")
            assertEquals(HttpStatusCode.OK, reordered.status)
            assertEquals(ReorderInput(sourceId = 3, targetId = 1), categories.lastReorder)
            assertEquals(2, Json.parseToJsonElement(reordered.bodyAsText()).jsonArray.size)

            // An unknown id is a not-found, not the conflict the legacy backend answered with.
            categories.reorderResult = OperationResult.NotFound
            assertApiError(
                admin.reorder(token, """{"sourceId":3,"targetId":1}"""),
                HttpStatusCode.NotFound,
                "Prompt category not found",
            )

            categories.reorderResult = OperationResult.Conflict
            assertApiError(
                admin.reorder(token, """{"sourceId":3,"targetId":1}"""),
                HttpStatusCode.Conflict,
                "Prompt category order changed concurrently, please retry",
            )
        }

    @Test
    fun `update and delete map their results to the required responses`() = testApplication {
        val categories = StubPromptCategoryOperations()
        application { installPromptCategoryTestApplication(categories) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        assertApiError(
            admin.updateCategory(token, """{}"""),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf("name" to listOf("Name is required")),
        )
        assertEquals(0, categories.operationCalls)

        assertEquals(
            HttpStatusCode.OK,
            admin.updateCategory(token, """{"name":"Animals"}""").status,
        )
        assertEquals(7L, categories.lastRequestedId)
        assertEquals(PromptCategoryInput(name = "Animals"), categories.lastUpdated)

        categories.updateResult = OperationResult.Conflict
        assertApiError(
            admin.updateCategory(token, """{"name":"Animals"}"""),
            HttpStatusCode.Conflict,
            "Prompt category name already exists",
        )

        val deleted = admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals("", deleted.bodyAsText())

        categories.deleteResult = OperationResult.Conflict
        assertApiError(
            admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) },
            HttpStatusCode.Conflict,
            "Prompt category is used by subcategories or prompts and cannot be deleted",
        )
    }

    private suspend fun HttpClient.createCategory(
        token: String,
        body: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.updateCategory(
        token: String,
        body: String,
    ): HttpResponse =
        put("$BASE_PATH/7") {
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

    private fun Application.installPromptCategoryTestApplication(
        categories: PromptCategoryOperations
    ) {
        installHttpRuntime()
        install(RequestValidation) { validatePromptRequests() }
        installAuthModule(AuthSettings("prompt-category-route-contract-session-secret"))
        installPromptModule(categories)
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
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        assertEquals(
            apiErrorJson.encodeToJsonElement(ApiError(message, errors)).jsonObject,
            Json.parseToJsonElement(response.bodyAsText()).jsonObject,
        )
    }

    private class StubPromptCategoryOperations : PromptCategoryOperations {
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var reorderCalls = 0
        var lastRequestedId: Long? = null
        var lastCreated: PromptCategoryInput? = null
        var lastUpdated: PromptCategoryInput? = null
        var lastReorder: ReorderInput? = null
        var listResult: OperationResult<List<PromptCategory>>? = null
        var getResult: OperationResult<PromptCategory>? = null
        var createResult: OperationResult<PromptCategory>? = null
        var updateResult: OperationResult<PromptCategory>? = null
        var deleteResult: OperationResult<Unit>? = null
        var reorderResult: OperationResult<List<PromptCategory>>? = null

        val operationCalls: Int
            get() = listCalls + getCalls + createCalls + updateCalls + deleteCalls + reorderCalls

        override suspend fun list(): OperationResult<List<PromptCategory>> {
            listCalls++
            return listResult ?: OperationResult.Success(listOf(category(1)))
        }

        override suspend fun get(id: Long): OperationResult<PromptCategory> {
            getCalls++
            lastRequestedId = id
            return getResult ?: OperationResult.Success(category(id))
        }

        override suspend fun create(input: PromptCategoryInput): OperationResult<PromptCategory> {
            createCalls++
            lastCreated = input
            return createResult ?: OperationResult.Success(category(42))
        }

        override suspend fun update(
            id: Long,
            input: PromptCategoryInput,
        ): OperationResult<PromptCategory> {
            updateCalls++
            lastRequestedId = id
            lastUpdated = input
            return updateResult ?: OperationResult.Success(category(id))
        }

        override suspend fun delete(id: Long): OperationResult<Unit> {
            deleteCalls++
            lastRequestedId = id
            return deleteResult ?: OperationResult.Success(Unit)
        }

        override suspend fun reorder(input: ReorderInput): OperationResult<List<PromptCategory>> {
            reorderCalls++
            lastReorder = input
            return reorderResult
                ?: OperationResult.Success(listOf(category(3), category(1).copy(position = 2)))
        }

        private fun category(id: Long): PromptCategory =
            PromptCategory(id = id, name = "Portraits", position = 1, active = true)
    }

    private companion object {
        const val BASE_PATH = "/api/admin/prompts/categories"
        val apiErrorJson = Json { encodeDefaults = true }
    }
}
