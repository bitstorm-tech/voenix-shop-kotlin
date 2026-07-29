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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.pricing.installPricingModule
import shop.voenix.prompt.PromptTestSchema
import shop.voenix.prompt.RecordingPublicImageStorage
import shop.voenix.prompt.installPromptModule
import shop.voenix.prompt.validatePromptRequests
import shop.voenix.testing.PostgresIntegrationTest
import shop.voenix.vat.installVatModule

internal class PromptSlotAdminIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `create appends behind the last slot and normalizes its name`() {
        migratedDataSource("prompt-slot-create-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)

            adminApplication(dataSource, "prompt-slot-create-integration-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val created = admin.createSlot(token, """{"name":"  Background  "}""")
                assertEquals(HttpStatusCode.Created, created.status)
                val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                assertEquals("Background", body.text("name"))
                assertEquals(1, body.number("position"))
                assertEquals(0, body.number("variantCount"))
                assertEquals(
                    "/api/admin/prompts/slots/${body.number("id")}",
                    created.headers[HttpHeaders.Location],
                )

                assertEquals(
                    HttpStatusCode.Created,
                    admin.createSlot(token, """{"name":"Style"}""").status,
                )
                assertEquals(
                    listOf("Background" to 1, "Style" to 2),
                    PromptTestSchema.orderedSlots(dataSource),
                )

                val listed = Json.parseToJsonElement(admin.get(BASE_PATH).bodyAsText()).jsonArray
                assertEquals(
                    listOf("Background", "Style"),
                    listed.map { it.jsonObject.text("name") },
                )
            }
        }
    }

    @Test
    fun `a name that differs only in case is rejected as a conflict`() {
        migratedDataSource("prompt-slot-duplicate-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedSlots(dataSource, "Background", "Style")

            adminApplication(dataSource, "prompt-slot-duplicate-integration-session-secret") { admin
                ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.createSlot(token, """{"name":" bACKGROUND "}"""),
                    HttpStatusCode.Conflict,
                    NAME_CONFLICT_MESSAGE,
                )
                assertApiMessage(
                    admin.updateSlot(token, id = 2, body = """{"name":"BACKGROUND"}"""),
                    HttpStatusCode.Conflict,
                    NAME_CONFLICT_MESSAGE,
                )
                assertEquals(
                    listOf("Background" to 1, "Style" to 2),
                    PromptTestSchema.orderedSlots(dataSource),
                )
            }
        }
    }

    @Test
    fun `read update and delete report a missing slot as not found`() {
        migratedDataSource("prompt-slot-missing-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)

            adminApplication(dataSource, "prompt-slot-missing-integration-session-secret") { admin
                ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.get("$BASE_PATH/404"),
                    HttpStatusCode.NotFound,
                    NOT_FOUND_MESSAGE,
                )
                assertApiMessage(
                    admin.updateSlot(token, id = 404, body = """{"name":"Ghost"}"""),
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
    fun `update replaces the name and keeps the display position`() {
        migratedDataSource("prompt-slot-update-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedSlots(dataSource, "Background", "Style")

            adminApplication(dataSource, "prompt-slot-update-integration-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                val updated = admin.updateSlot(token, id = 2, body = """{"name":"  Mood  "}""")
                assertEquals(HttpStatusCode.OK, updated.status)
                val body = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
                assertEquals("Mood", body.text("name"))
                assertEquals(2, body.number("position"))
            }
        }
    }

    @Test
    fun `the variant count is part of every answer`() {
        migratedDataSource("prompt-slot-variant-count-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedSlots(dataSource, "Background", "Style")
            PromptTestSchema.seedVariants(dataSource, slotId = 1, "Watercolor", "Oil")

            adminApplication(dataSource, "prompt-slot-variant-count-session-secret") { admin ->
                val listed = Json.parseToJsonElement(admin.get(BASE_PATH).bodyAsText()).jsonArray
                assertEquals(
                    listOf(2, 0),
                    listed.map { row -> row.jsonObject.number("variantCount") },
                )

                val single = Json.parseToJsonElement(admin.get("$BASE_PATH/1").bodyAsText())
                assertEquals(2, single.jsonObject.number("variantCount"))
            }
        }
    }

    @Test
    fun `delete is blocked while variants reference the slot and never reuses the position`() {
        migratedDataSource("prompt-slot-delete-test").use { dataSource ->
            PromptTestSchema.reset(dataSource)
            PromptTestSchema.seedSlots(dataSource, "First", "Second", "Third")
            PromptTestSchema.seedVariants(dataSource, slotId = 3, "Watercolor")

            adminApplication(dataSource, "prompt-slot-delete-integration-session-secret") { admin ->
                val token = antiforgeryToken(admin)

                assertApiMessage(
                    admin.delete("$BASE_PATH/3") { header(AuthRouting.CSRF_HEADER, token) },
                    HttpStatusCode.Conflict,
                    "Prompt slot is used by slot variants and cannot be deleted",
                )

                val deleted =
                    admin.delete("$BASE_PATH/2") { header(AuthRouting.CSRF_HEADER, token) }
                assertEquals(HttpStatusCode.NoContent, deleted.status)

                // The gap the delete left is intentional and the next create appends behind the
                // last position instead of filling it.
                assertEquals(
                    listOf("First" to 1, "Third" to 3),
                    PromptTestSchema.orderedSlots(dataSource),
                )
                assertEquals(
                    HttpStatusCode.Created,
                    admin.createSlot(token, """{"name":"Fourth"}""").status,
                )
                assertEquals(
                    listOf("First" to 1, "Third" to 3, "Fourth" to 4),
                    PromptTestSchema.orderedSlots(dataSource),
                )
            }
        }
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
            val database = Database.connect(datasource = dataSource)
            installPromptModule(
                database,
                RecordingPublicImageStorage(),
                installPricingModule(database, installVatModule(database)),
            )
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
        const val BASE_PATH = "/api/admin/prompts/slots"
        const val NOT_FOUND_MESSAGE = "Prompt slot not found"
        const val NAME_CONFLICT_MESSAGE = "Prompt slot name already exists"
    }
}
