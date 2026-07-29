package shop.voenix.prompt.slot

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
import shop.voenix.prompt.installPromptModule
import shop.voenix.prompt.validatePromptRequests

internal class PromptSlotRouteSecurityAndValidationTest {
    @Test
    fun `admin subtree rejects before id binding or slot operations`() = testApplication {
        val slots = StubPromptSlotOperations()
        application { installPromptSlotTestApplication(slots) }

        listOf(
                client.get(BASE_PATH),
                client.get("$BASE_PATH/1"),
                client.get("$BASE_PATH/not-a-long"),
                client.post(BASE_PATH),
                client.put("$BASE_PATH/1"),
                client.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(0, slots.operationCalls)

        val customer = signedInClient("CUSTOMER")
        listOf(
                customer.get(BASE_PATH),
                customer.get("$BASE_PATH/1"),
                customer.post(BASE_PATH),
                customer.put("$BASE_PATH/1"),
                customer.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Forbidden, response.status) }
        assertEquals(0, slots.operationCalls)

        val admin = signedInClient("ADMIN")
        listOf(
                admin.post(BASE_PATH),
                admin.put("$BASE_PATH/1"),
                admin.delete("$BASE_PATH/1"),
            )
            .forEach { response ->
                assertApiError(response, HttpStatusCode.BadRequest, "Invalid CSRF token")
            }
        assertApiError(
            admin.get("$BASE_PATH/not-a-long"),
            HttpStatusCode.BadRequest,
            "Invalid prompt slot id",
        )
        assertEquals(0, slots.operationCalls)
    }

    @Test
    fun `http validation rejects before operations and a valid create preserves the contract`() =
        testApplication {
            val slots = StubPromptSlotOperations()
            application { installPromptSlotTestApplication(slots) }
            val admin = signedInClient("ADMIN")
            val token = antiforgeryToken(admin)

            assertApiError(
                admin.createSlot(token, """{"name":"   "}"""),
                HttpStatusCode.BadRequest,
                "Validation failed",
                linkedMapOf("name" to listOf("Name is required")),
            )
            assertEquals(0, slots.operationCalls)

            val created = admin.createSlot(token, """{"name":" Background "}""")
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("$BASE_PATH/42", created.headers[HttpHeaders.Location])
            assertEquals(PromptSlotInput(name = " Background "), slots.lastCreated)

            slots.createResult = OperationResult.Conflict
            assertApiError(
                admin.createSlot(token, """{"name":"Background"}"""),
                HttpStatusCode.Conflict,
                "Prompt slot name already exists",
            )
        }

    @Test
    fun `reads answer with a bare array and map the required api errors`() = testApplication {
        val slots = StubPromptSlotOperations()
        application { installPromptSlotTestApplication(slots) }
        val admin = signedInClient("ADMIN")

        val listed = admin.get(BASE_PATH)
        assertEquals(HttpStatusCode.OK, listed.status)
        val listedJson = Json.parseToJsonElement(listed.bodyAsText()).jsonArray
        assertEquals(1, listedJson.size)
        assertEquals(
            "Background",
            listedJson.single().jsonObject.getValue("name").jsonPrimitive.content,
        )
        assertEquals(
            2,
            listedJson.single().jsonObject.getValue("variantCount").jsonPrimitive.content.toInt(),
        )

        assertEquals(HttpStatusCode.OK, admin.get("$BASE_PATH/7").status)
        assertEquals(7L, slots.lastRequestedId)

        slots.getResult = OperationResult.NotFound
        assertApiError(
            admin.get("$BASE_PATH/404"),
            HttpStatusCode.NotFound,
            "Prompt slot not found",
        )

        slots.listResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.get(BASE_PATH),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    @Test
    fun `update and delete map their results to the required responses`() = testApplication {
        val slots = StubPromptSlotOperations()
        application { installPromptSlotTestApplication(slots) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        assertApiError(
            admin.updateSlot(token, """{}"""),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf("name" to listOf("Name is required")),
        )
        assertEquals(0, slots.operationCalls)

        assertEquals(HttpStatusCode.OK, admin.updateSlot(token, """{"name":"Style"}""").status)
        assertEquals(7L, slots.lastRequestedId)
        assertEquals(PromptSlotInput(name = "Style"), slots.lastUpdated)

        slots.updateResult = OperationResult.Conflict
        assertApiError(
            admin.updateSlot(token, """{"name":"Style"}"""),
            HttpStatusCode.Conflict,
            "Prompt slot name already exists",
        )

        val deleted = admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals("", deleted.bodyAsText())

        slots.deleteResult = OperationResult.Conflict
        assertApiError(
            admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) },
            HttpStatusCode.Conflict,
            "Prompt slot is used by slot variants and cannot be deleted",
        )
    }

    private suspend fun HttpClient.createSlot(
        token: String,
        body: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.updateSlot(
        token: String,
        body: String,
    ): HttpResponse =
        put("$BASE_PATH/7") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun Application.installPromptSlotTestApplication(slots: PromptSlotOperations) {
        installHttpRuntime()
        install(RequestValidation) { validatePromptRequests() }
        installAuthModule(AuthSettings("prompt-slot-route-contract-session-secret"))
        installPromptModule(slots)
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

    private class StubPromptSlotOperations : PromptSlotOperations {
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var lastRequestedId: Long? = null
        var lastCreated: PromptSlotInput? = null
        var lastUpdated: PromptSlotInput? = null
        var listResult: OperationResult<List<PromptSlot>>? = null
        var getResult: OperationResult<PromptSlot>? = null
        var createResult: OperationResult<PromptSlot>? = null
        var updateResult: OperationResult<PromptSlot>? = null
        var deleteResult: OperationResult<Unit>? = null

        val operationCalls: Int
            get() = listCalls + getCalls + createCalls + updateCalls + deleteCalls

        override suspend fun list(): OperationResult<List<PromptSlot>> {
            listCalls++
            return listResult ?: OperationResult.Success(listOf(slot(1)))
        }

        override suspend fun get(id: Long): OperationResult<PromptSlot> {
            getCalls++
            lastRequestedId = id
            return getResult ?: OperationResult.Success(slot(id))
        }

        override suspend fun create(input: PromptSlotInput): OperationResult<PromptSlot> {
            createCalls++
            lastCreated = input
            return createResult ?: OperationResult.Success(slot(42))
        }

        override suspend fun update(
            id: Long,
            input: PromptSlotInput,
        ): OperationResult<PromptSlot> {
            updateCalls++
            lastRequestedId = id
            lastUpdated = input
            return updateResult ?: OperationResult.Success(slot(id))
        }

        override suspend fun delete(id: Long): OperationResult<Unit> {
            deleteCalls++
            lastRequestedId = id
            return deleteResult ?: OperationResult.Success(Unit)
        }

        private fun slot(id: Long): PromptSlot =
            PromptSlot(id = id, name = "Background", position = 1, variantCount = 2)
    }

    private companion object {
        const val BASE_PATH = "/api/admin/prompts/slots"
        val apiErrorJson = Json { encodeDefaults = true }
    }
}
