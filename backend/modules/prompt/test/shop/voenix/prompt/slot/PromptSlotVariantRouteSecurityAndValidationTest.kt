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
import shop.voenix.prompt.validatePromptRequests

internal class PromptSlotVariantRouteSecurityAndValidationTest {
    @Test
    fun `admin subtree rejects before id binding or variant operations`() = testApplication {
        val variants = StubPromptSlotVariantOperations()
        application { installPromptSlotVariantTestApplication(variants) }

        listOf(
                client.get(BASE_PATH),
                client.get("$BASE_PATH/1"),
                client.get("$BASE_PATH/not-a-long"),
                client.post(BASE_PATH),
                client.put("$BASE_PATH/1"),
                client.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Unauthorized, response.status) }
        assertEquals(0, variants.operationCalls)

        val customer = signedInClient("CUSTOMER")
        listOf(
                customer.get(BASE_PATH),
                customer.get("$BASE_PATH/1"),
                customer.post(BASE_PATH),
                customer.put("$BASE_PATH/1"),
                customer.delete("$BASE_PATH/1"),
            )
            .forEach { response -> assertEquals(HttpStatusCode.Forbidden, response.status) }
        assertEquals(0, variants.operationCalls)

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
            "Invalid prompt slot variant id",
        )
        assertEquals(0, variants.operationCalls)
    }

    @Test
    fun `the create carries the slot and the update cannot`() = testApplication {
        val variants = StubPromptSlotVariantOperations()
        application { installPromptSlotVariantTestApplication(variants) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        assertApiError(
            admin.createVariant(token, """{"name":"Watercolor","prompt":"in watercolor"}"""),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf("slotId" to listOf("Slot id is required")),
        )
        assertEquals(0, variants.operationCalls)

        val created =
            admin.createVariant(
                token,
                """{"slotId":3,"name":" Watercolor ","prompt":" in watercolor ","llm":"  "}""",
            )
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("$BASE_PATH/42", created.headers[HttpHeaders.Location])
        assertEquals(
            PromptSlotVariantInput(
                slotId = 3,
                name = " Watercolor ",
                prompt = " in watercolor ",
                llm = "  ",
            ),
            variants.lastCreated,
        )

        // A slot id in an update body is ignored: the update contract has no such field.
        assertEquals(
            HttpStatusCode.OK,
            admin
                .updateVariant(
                    token,
                    """{"slotId":9,"name":"Watercolor","prompt":"in watercolor"}""",
                )
                .status,
        )
        assertEquals(
            PromptSlotVariantUpdate(name = "Watercolor", prompt = "in watercolor"),
            variants.lastUpdated,
        )
        assertEquals(7L, variants.lastRequestedId)
    }

    @Test
    fun `an unknown slot is a field error and a duplicate name is a conflict`() = testApplication {
        val variants = StubPromptSlotVariantOperations()
        application { installPromptSlotVariantTestApplication(variants) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)
        val body = """{"slotId":404,"name":"Watercolor","prompt":"in watercolor"}"""

        variants.createResult =
            OperationResult.Invalid(mapOf("slotId" to listOf("Prompt slot does not exist")))
        assertApiError(
            admin.createVariant(token, body),
            HttpStatusCode.BadRequest,
            "Validation failed",
            linkedMapOf("slotId" to listOf("Prompt slot does not exist")),
        )

        variants.createResult = OperationResult.Conflict
        assertApiError(
            admin.createVariant(token, body),
            HttpStatusCode.Conflict,
            "Prompt slot variant name already exists",
        )
    }

    @Test
    fun `reads answer with a bare array and map the required api errors`() = testApplication {
        val variants = StubPromptSlotVariantOperations()
        application { installPromptSlotVariantTestApplication(variants) }
        val admin = signedInClient("ADMIN")

        val listed = admin.get(BASE_PATH)
        assertEquals(HttpStatusCode.OK, listed.status)
        val row = Json.parseToJsonElement(listed.bodyAsText()).jsonArray.single().jsonObject
        assertEquals("Watercolor", row.getValue("name").jsonPrimitive.content)
        assertEquals(3, row.getValue("slotId").jsonPrimitive.content.toInt())
        assertEquals("Background", row.getValue("slotName").jsonPrimitive.content)
        assertEquals(0, row.getValue("assignedPromptCount").jsonPrimitive.content.toInt())

        variants.getResult = OperationResult.NotFound
        assertApiError(
            admin.get("$BASE_PATH/404"),
            HttpStatusCode.NotFound,
            "Prompt slot variant not found",
        )

        variants.listResult = OperationResult.UnexpectedFailure
        assertApiError(
            admin.get(BASE_PATH),
            HttpStatusCode.InternalServerError,
            "Internal server error",
        )
    }

    @Test
    fun `delete answers without a body and reports the in-use conflict`() = testApplication {
        val variants = StubPromptSlotVariantOperations()
        application { installPromptSlotVariantTestApplication(variants) }
        val admin = signedInClient("ADMIN")
        val token = antiforgeryToken(admin)

        val deleted = admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals("", deleted.bodyAsText())

        variants.deleteResult = OperationResult.Conflict
        assertApiError(
            admin.delete("$BASE_PATH/9") { header(AuthRouting.CSRF_HEADER, token) },
            HttpStatusCode.Conflict,
            "Prompt slot variant is used by prompts and cannot be deleted",
        )
    }

    private suspend fun HttpClient.createVariant(
        token: String,
        body: String,
    ): HttpResponse =
        post(BASE_PATH) {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.updateVariant(
        token: String,
        body: String,
    ): HttpResponse =
        put("$BASE_PATH/7") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun Application.installPromptSlotVariantTestApplication(
        variants: PromptSlotVariantOperations
    ) {
        installHttpRuntime()
        install(RequestValidation) { validatePromptRequests() }
        installAuthModule(AuthSettings("prompt-slot-variant-route-contract-session-secret"))
        installPromptSlotVariantRoutes(variants)
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

    private class StubPromptSlotVariantOperations : PromptSlotVariantOperations {
        var listCalls = 0
        var getCalls = 0
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var lastRequestedId: Long? = null
        var lastCreated: PromptSlotVariantInput? = null
        var lastUpdated: PromptSlotVariantUpdate? = null
        var listResult: OperationResult<List<PromptSlotVariant>>? = null
        var getResult: OperationResult<PromptSlotVariant>? = null
        var createResult: OperationResult<PromptSlotVariant>? = null
        var updateResult: OperationResult<PromptSlotVariant>? = null
        var deleteResult: OperationResult<Unit>? = null

        val operationCalls: Int
            get() = listCalls + getCalls + createCalls + updateCalls + deleteCalls

        override suspend fun list(): OperationResult<List<PromptSlotVariant>> {
            listCalls++
            return listResult ?: OperationResult.Success(listOf(variant(1)))
        }

        override suspend fun get(id: Long): OperationResult<PromptSlotVariant> {
            getCalls++
            lastRequestedId = id
            return getResult ?: OperationResult.Success(variant(id))
        }

        override suspend fun create(
            input: PromptSlotVariantInput
        ): OperationResult<PromptSlotVariant> {
            createCalls++
            lastCreated = input
            return createResult ?: OperationResult.Success(variant(42))
        }

        override suspend fun update(
            id: Long,
            input: PromptSlotVariantUpdate,
        ): OperationResult<PromptSlotVariant> {
            updateCalls++
            lastRequestedId = id
            lastUpdated = input
            return updateResult ?: OperationResult.Success(variant(id))
        }

        override suspend fun delete(id: Long): OperationResult<Unit> {
            deleteCalls++
            lastRequestedId = id
            return deleteResult ?: OperationResult.Success(Unit)
        }

        private fun variant(id: Long): PromptSlotVariant =
            PromptSlotVariant(
                id = id,
                slotId = 3,
                slotName = "Background",
                name = "Watercolor",
                prompt = "in watercolor",
                description = null,
                llm = null,
                assignedPromptCount = 0,
            )
    }

    private companion object {
        const val BASE_PATH = "/api/admin/prompts/slot-variants"
        val apiErrorJson = Json { encodeDefaults = true }
    }
}
