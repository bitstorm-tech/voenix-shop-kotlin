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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.prompt.PromptTestSchema
import shop.voenix.prompt.installPromptModule
import shop.voenix.prompt.validatePromptRequests
import shop.voenix.testing.PostgresIntegrationTest

internal class PromptSlotVariantAdminIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create stores the normalized values and answers with the slot behind the id`() {
        migratedDataSource("prompt-slot-variant-create-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedSlots(dataSource, "Background", "Style")

            adminApplication(dataSource, "prompt-variant-create-integration-session-secret") { admin
                ->
                val token = antiforgeryToken(admin)

                val created =
                    admin.createVariant(
                        token,
                        """
                        {"slotId":2,"name":"  Watercolor  ","prompt":"  in watercolor  ",
                         "description":"   ","llm":"  gpt-image-1  "}
                        """
                            .trimIndent(),
                    )
                assertEquals(HttpStatusCode.Created, created.status)
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals("Watercolor", body.text("name"))
                assertEquals("in watercolor", body.text("prompt"))
                assertEquals(null, body["description"]?.jsonPrimitive?.contentOrNull)
                assertEquals("gpt-image-1", body.text("llm"))
                assertEquals(2, body.number("slotId"))
                assertEquals("Style", body.text("slotName"))
                assertEquals(0, body.number("assignedPromptCount"))
                assertEquals(
                    "/api/admin/prompts/slot-variants/${body.number("id")}",
                    created.headers[HttpHeaders.Location],
                )
                assertEquals(
                    listOf("Watercolor" to 2L),
                    PromptTestSchema.storedVariants(dataSource),
                )
            }
        }
    }

    @Test
    fun `an unknown slot is answered as a field error and stores nothing`() {
        migratedDataSource("prompt-slot-variant-unknown-slot-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)

            adminApplication(dataSource, "prompt-variant-unknown-slot-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val rejected =
                    admin.createVariant(
                        token,
                        """{"slotId":404,"name":"Watercolor","prompt":"in watercolor"}""",
                    )
                assertEquals(HttpStatusCode.BadRequest, rejected.status)
                val body = Json.parseToJsonElement(rejected.bodyAsText()).jsonObject
                assertEquals("Validation failed", body.text("message"))
                assertEquals(
                    listOf("Prompt slot does not exist"),
                    body.getValue("errors").jsonObject.getValue("slotId").jsonArray.map { message ->
                        message.jsonPrimitive.content
                    },
                )
                assertEquals(emptyList(), PromptTestSchema.storedVariants(dataSource))
            }
        }
    }

    @Test
    fun `variant names are unique across all slots and case-insensitively`() {
        migratedDataSource("prompt-slot-variant-duplicate-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedSlots(dataSource, "Background", "Style")
            PromptTestSchema.seedVariants(dataSource, slotId = 1, "Watercolor")
            PromptTestSchema.seedVariants(dataSource, slotId = 2, "Oil")

            adminApplication(dataSource, "prompt-variant-duplicate-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                // The duplicate is created in the *other* slot and is still a conflict.
                assertApiMessage(
                    admin.createVariant(
                        token,
                        """{"slotId":2,"name":" wATERCOLOR ","prompt":"in watercolor"}""",
                    ),
                    HttpStatusCode.Conflict,
                    NAME_CONFLICT_MESSAGE,
                )
                assertApiMessage(
                    admin.updateVariant(
                        token,
                        id = 2,
                        body = """{"name":"WATERCOLOR","prompt":"in oil"}""",
                    ),
                    HttpStatusCode.Conflict,
                    NAME_CONFLICT_MESSAGE,
                )
                assertEquals(
                    listOf("Watercolor" to 1L, "Oil" to 2L),
                    PromptTestSchema.storedVariants(dataSource),
                )
            }
        }
    }

    @Test
    fun `update replaces every value and never moves the variant to another slot`() {
        migratedDataSource("prompt-slot-variant-update-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedSlots(dataSource, "Background", "Style")
            PromptTestSchema.seedVariants(dataSource, slotId = 1, "Watercolor")

            adminApplication(dataSource, "prompt-variant-update-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val updated =
                    admin.updateVariant(
                        token,
                        id = 1,
                        // The slot id in the body is not part of the update contract.
                        body =
                            """{"slotId":2,"name":" Gouache ","prompt":" in gouache ","llm":"  "}""",
                    )
                assertEquals(HttpStatusCode.OK, updated.status)
                val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
                assertEquals("Gouache", body.text("name"))
                assertEquals("in gouache", body.text("prompt"))
                assertEquals(null, body["llm"]?.jsonPrimitive?.contentOrNull)
                assertEquals(1, body.number("slotId"))
                assertEquals("Background", body.text("slotName"))
                assertEquals(listOf("Gouache" to 1L), PromptTestSchema.storedVariants(dataSource))
            }
        }
    }

    @Test
    fun `the list is ordered by slot position then by variant name`() {
        migratedDataSource("prompt-slot-variant-order-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedSlots(dataSource, "Background", "Style")
            PromptTestSchema.seedVariants(dataSource, slotId = 2, "Zebra", "Alpaca")
            PromptTestSchema.seedVariants(dataSource, slotId = 1, "Meadow")

            adminApplication(dataSource, "prompt-variant-order-session-secret") { admin ->
                val listed = Json.parseToJsonElement(admin.get(BASE_PATH).bodyAsText()).jsonArray
                assertEquals(
                    listOf("Meadow", "Alpaca", "Zebra"),
                    listed.map { row -> row.jsonObject.text("name") },
                )
                assertEquals(
                    listOf("Background", "Style", "Style"),
                    listed.map { row -> row.jsonObject.text("slotName") },
                )
            }
        }
    }

    @Test
    fun `read update and delete report a missing variant as not found`() {
        migratedDataSource("prompt-slot-variant-missing-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)

            adminApplication(dataSource, "prompt-variant-missing-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.get("$BASE_PATH/404"),
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
                assertApiMessage(
                    admin.updateVariant(
                        token,
                        id = 404,
                        body = """{"name":"Ghost","prompt":"nothing"}""",
                    ),
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
                assertApiMessage(
                    admin.delete("$BASE_PATH/404") { header(AuthRouting.CSRF_HEADER, token) },
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
            }
        }
    }

    @Test
    fun `a variant a prompt uses is counted and cannot be deleted`() {
        migratedDataSource("prompt-slot-variant-in-use-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedSlots(dataSource, "Background")
            PromptTestSchema.seedVariants(dataSource, slotId = 1, "Watercolor", "Oil")
            PromptTestSchema.seedPromptUsing(dataSource, variantId = 1)

            adminApplication(dataSource, "prompt-variant-in-use-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val listed = Json.parseToJsonElement(admin.get(BASE_PATH).bodyAsText()).jsonArray
                assertEquals(
                    mapOf("Watercolor" to 1, "Oil" to 0),
                    listed.associate { row ->
                        row.jsonObject.text("name") to row.jsonObject.number("assignedPromptCount")
                    },
                )

                assertApiMessage(
                    admin.delete("$BASE_PATH/1") { header(AuthRouting.CSRF_HEADER, token) },
                    HttpStatusCode.Conflict,
                    "Prompt slot variant is used by prompts and cannot be deleted",
                )

                val deleted =
                    admin.delete("$BASE_PATH/2") { header(AuthRouting.CSRF_HEADER, token) }
                assertEquals(HttpStatusCode.NoContent, deleted.status)
                assertEquals(
                    listOf("Watercolor" to 1L),
                    PromptTestSchema.storedVariants(dataSource),
                )
            }
        }
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
        id: Long,
        body: String,
    ): HttpResponse =
        put("$BASE_PATH/$id") {
            header(AuthRouting.CSRF_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** Runs [block] against the real module installed on [dataSource], signed in as an admin. */
    private fun adminApplication(
        dataSource: DataSource,
        sessionSecret: String,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application {
            installHttpRuntime()
            install(RequestValidation) { validatePromptRequests() }
            installAuthModule(AuthSettings(sessionSecret))
            installPromptModule(Database.connect(datasource = dataSource))
            routing {
                post("/test/sign-in") {
                    call.sessions.set(UserSession(userId = "11", role = "ADMIN"))
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        val admin = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, admin.post("/test/sign-in").status)
        block(admin)
    }

    private suspend fun antiforgeryToken(client: HttpClient): String =
        Json.parseToJsonElement(client.get("/api/antiforgery/token").bodyAsText())
            .jsonObject
            .getValue("requestToken")
            .jsonPrimitive
            .content

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

    private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

    private fun JsonObject.number(field: String): Int = text(field).toInt()

    private companion object {
        const val BASE_PATH = "/api/admin/prompts/slot-variants"
        const val NOT_FOUND_MESSAGE = "Prompt slot variant not found"
        const val NAME_CONFLICT_MESSAGE = "Prompt slot variant name already exists"
    }
}
