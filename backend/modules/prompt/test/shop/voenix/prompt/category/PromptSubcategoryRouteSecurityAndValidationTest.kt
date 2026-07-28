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

internal class PromptSubcategoryRouteSecurityAndValidationTest {
    @Test
    fun `admin subtree rejects before id binding or subcategory operations`() = testApplication {
        val subcategories = StubPromptSubcategoryOperations()
        application { installPromptSubcategoryTestApplication(subcategories) }

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
        assertEquals(0, subcategories.operationCalls)

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
        assertEquals(0, subcategories.operationCalls)

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
            "Invalid prompt subcategory id",
        )
        assertEquals(0, subcategories.operationCalls)
    }

    @Test
    fun `http validation rejects before operations and a valid create preserves the contract`() =
        testApplication {
            val subcategories = StubPromptSubcategoryOperations()
            application { installPromptSubcategoryTestApplication(subcategories) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            assertApiError(
                admin.createSubcategory(token, """{"name":"Kids"}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf("categoryId" to listOf("CategoryId is required")),
            )
            assertEquals(0, subcategories.operationCalls)

            val created = admin.createSubcategory(token, """{"categoryId":3,"name":" Kids "}""")
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("$BASE_PATH/42", created.headers[HttpHeaders.Location])
            assertEquals(
                PromptSubcategoryInput(categoryId = 3, name = " Kids "),
                subcategories.lastCreated,
            )

            // An unknown category is a field error, not a conflict.
            subcategories.createResult =
                OperationResult.Invalid(
                    mapOf("categoryId" to listOf("Prompt category does not exist"))
                )
            assertApiError(
                admin.createSubcategory(token, """{"categoryId":404,"name":"Kids"}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf("categoryId" to listOf("Prompt category does not exist")),
            )
        }

    @Test
    fun `reads answer with a bare array of flat rows and map the required api errors`() =
        testApplication {
            val subcategories = StubPromptSubcategoryOperations()
            application { installPromptSubcategoryTestApplication(subcategories) }
            val admin = signedInClient("ADMIN")

            val listed = admin.get(BASE_PATH)
            assertEquals(HttpStatusCode.OK, listed.status)
            val row = Json.parseToJsonElement(listed.bodyAsText()).jsonArray.single().jsonObject
            assertEquals(
                setOf("id", "categoryId", "name", "description", "position", "active"),
                row.keys,
            )
            assertEquals(3, row.getValue("categoryId").jsonPrimitive.content.toInt())

            assertEquals(HttpStatusCode.OK, admin.get("$BASE_PATH/7").status)
            assertEquals(7L, subcategories.lastRequestedId)

            subcategories.getResult = OperationResult.NotFound
            assertApiError(
                admin.get("$BASE_PATH/404"),
                HttpStatusCode.NotFound,
                "Prompt subcategory not found",
            )

            subcategories.listResult = OperationResult.UnexpectedFailure
            assertApiError(
                admin.get(BASE_PATH),
                HttpStatusCode.InternalServerError,
                "Internal server error",
            )
        }

    @Test
    fun `the reorder route validates its body and answers with the new order of one category`() =
        testApplication {
            val subcategories = StubPromptSubcategoryOperations()
            application { installPromptSubcategoryTestApplication(subcategories) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            assertApiError(
                admin.reorder(token, """{"sourceId":-1,"targetId":2}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf("sourceId" to listOf("SourceId must be positive")),
            )
            assertEquals(0, subcategories.operationCalls)

            val reordered = admin.reorder(token, """{"sourceId":3,"targetId":1}""")
            assertEquals(HttpStatusCode.OK, reordered.status)
            assertEquals(ReorderInput(sourceId = 3, targetId = 1), subcategories.lastReorder)
            assertEquals(2, Json.parseToJsonElement(reordered.bodyAsText()).jsonArray.size)

            // A target from another category is as unknown as a missing id.
            subcategories.reorderResult = OperationResult.NotFound
            assertApiError(
                admin.reorder(token, """{"sourceId":3,"targetId":1}"""),
                HttpStatusCode.NotFound,
                "Prompt subcategory not found",
            )

            subcategories.reorderResult = OperationResult.Conflict
            assertApiError(
                admin.reorder(token, """{"sourceId":3,"targetId":1}"""),
                HttpStatusCode.Conflict,
                "Prompt subcategory order changed concurrently, please retry",
            )
        }

    @Test
    fun `update and delete map their results to the required responses`() = testApplication {
        val subcategories = StubPromptSubcategoryOperations()
        application { installPromptSubcategoryTestApplication(subcategories) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        assertApiError(
            admin.updateSubcategory(token, """{}"""),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf(
                "categoryId" to listOf("CategoryId is required"),
                "name" to listOf("Name is required"),
            ),
        )
        assertEquals(0, subcategories.operationCalls)

        assertEquals(
            HttpStatusCode.OK,
            admin.updateSubcategory(token, """{"categoryId":3,"name":"Kids"}""").status,
        )
        assertEquals(7L, subcategories.lastRequestedId)
        assertEquals(
            PromptSubcategoryInput(categoryId = 3, name = "Kids"),
            subcategories.lastUpdated,
        )

        subcategories.updateResult = OperationResult.Conflict
        assertApiError(
            admin.updateSubcategory(token, """{"categoryId":3,"name":"Kids"}"""),
            HttpStatusCode.Conflict,
            "Prompt subcategory name already exists in this prompt category",
        )

        val deleted = admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals("", deleted.bodyAsText())

        subcategories.deleteResult = OperationResult.Conflict
        assertApiError(
            admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) },
            HttpStatusCode.Conflict,
            "Prompt subcategory is used by prompts and cannot be deleted",
        )
    }

    private suspend fun HttpClient.createSubcategory(
        token: String,
        body: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.updateSubcategory(
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

    private fun Application.installPromptSubcategoryTestApplication(
        subcategories: PromptSubcategoryOperations
    ) {
        installHttpRuntime()
        install(RequestValidation) { validatePromptRequests() }
        installAuthModule(AuthSettings("prompt-subcategory-route-contract-session-secret"))
        installPromptModule(subcategories)
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

    private class StubPromptSubcategoryOperations : PromptSubcategoryOperations {
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var reorderCalls = 0
        var lastRequestedId: Long? = null
        var lastCreated: PromptSubcategoryInput? = null
        var lastUpdated: PromptSubcategoryInput? = null
        var lastReorder: ReorderInput? = null
        var listResult: OperationResult<List<PromptSubcategory>>? = null
        var getResult: OperationResult<PromptSubcategory>? = null
        var createResult: OperationResult<PromptSubcategory>? = null
        var updateResult: OperationResult<PromptSubcategory>? = null
        var deleteResult: OperationResult<Unit>? = null
        var reorderResult: OperationResult<List<PromptSubcategory>>? = null

        val operationCalls: Int
            get() = listCalls + getCalls + createCalls + updateCalls + deleteCalls + reorderCalls

        override suspend fun list(): OperationResult<List<PromptSubcategory>> {
            listCalls++
            return listResult ?: OperationResult.Success(listOf(subcategory(1)))
        }

        override suspend fun get(id: Long): OperationResult<PromptSubcategory> {
            getCalls++
            lastRequestedId = id
            return getResult ?: OperationResult.Success(subcategory(id))
        }

        override suspend fun create(
            input: PromptSubcategoryInput
        ): OperationResult<PromptSubcategory> {
            createCalls++
            lastCreated = input
            return createResult ?: OperationResult.Success(subcategory(42))
        }

        override suspend fun update(
            id: Long,
            input: PromptSubcategoryInput,
        ): OperationResult<PromptSubcategory> {
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
        ): OperationResult<List<PromptSubcategory>> {
            reorderCalls++
            lastReorder = input
            return reorderResult
                ?: OperationResult.Success(
                    listOf(subcategory(3), subcategory(1).copy(position = 2))
                )
        }

        private fun subcategory(id: Long): PromptSubcategory =
            PromptSubcategory(
                id = id,
                categoryId = 3,
                name = "Kids",
                description = null,
                position = 1,
                active = true,
            )
    }

    private companion object {
        const val BASE_PATH = "/api/admin/prompts/subcategories"
        val apiErrorJson = Json { encodeDefaults = true }
    }
}
